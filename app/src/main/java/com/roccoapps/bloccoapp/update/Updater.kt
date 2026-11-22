package com.roccoapps.bloccoapp.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.roccoapps.bloccoapp.BuildConfig
import com.roccoapps.bloccoapp.Policy
import com.roccoapps.bloccoapp.Prefs
import com.roccoapps.bloccoapp.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento automatico.
 * La CI pubblica nella release "latest" del repo pubblico [BuildConfig.UPDATE_REPO]:
 *  - version.json  { versionCode, versionName, notes }
 *  - app-release.apk (firmato sempre con la stessa chiave)
 * Essendo Device Owner, l'installazione avviene in silenzio, anche in background.
 */
object Updater {
    data class Info(val code: Int, val name: String, val notes: String)

    private val base get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/latest"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000; readTimeout = 60_000
            useCaches = false
            setRequestProperty("User-Agent", "blocco-app-updater")
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Versione pubblicata se più nuova di quella installata, altrimenti null. */
    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        try {
            val c = open("$base/version.json?t=${System.currentTimeMillis()}")
            if (c.responseCode != 200) return@withContext null
            val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            Info(o.optInt("versionCode"), o.optString("versionName"), o.optString("notes"))
                .takeIf { it.code > BuildConfig.VERSION_CODE }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(ctx: Context, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val c = open("$base/app-release.apk")
        if (c.responseCode != 200) throw Exception("Download non riuscito (${c.responseCode})")
        val total = c.contentLengthLong
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val tmp = File(dir, "update.part")
        val out = File(dir, "app-release.apk")
        c.inputStream.use { inp ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                }
            }
        }
        out.delete(); tmp.renameTo(out); out
    }

    /** Dal worker in background: solo se Device Owner (installazione silenziosa garantita). */
    suspend fun autoUpdate(ctx: Context) {
        if (!Policy.isOwner(ctx)) return
        Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis())
        check() ?: return
        install(ctx, download(ctx))
    }

    /** Installa con PackageInstaller (silenzioso da Device Owner); se non va, installer classico. */
    fun install(ctx: Context, apk: File) {
        val ok = runCatching {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("blocco.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, id,
                    Intent(ctx, InstallStatusReceiver::class.java).putExtra(EXTRA_APK, apk.absolutePath),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                )
                s.commit(pi.intentSender)
            }
        }.isSuccess
        if (!ok) installClassic(ctx, apk)
    }

    fun installClassic(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    const val EXTRA_APK = "apk"
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> if (st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore $st"
                runCatching { Toast.makeText(context, "Aggiornamento non riuscito: $msg", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Dopo un aggiornamento: riapplica blocchi e lavoro periodico. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Policy.apply(context)
        runCatching { Work.schedule(context) }
        com.roccoapps.bloccoapp.GuardService.start(context)
    }
}
