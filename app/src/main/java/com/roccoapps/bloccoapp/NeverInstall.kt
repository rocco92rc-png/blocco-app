package com.roccoapps.bloccoapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.roccoapps.bloccoapp.update.InstallStatusReceiver

/**
 * Lista "Mai installare": se una di queste app compare sul telefono (Play Store, APK, ecc.)
 * viene subito sospesa e disinstallata. La lista si può solo allungare.
 */
object NeverInstall {

    /** App che non si possono mettere in lista (si bloccherebbe il telefono). */
    val PROTECTED = setOf(
        "com.android.settings", "com.android.vending", "com.google.android.gms",
        "com.android.systemui", "com.google.android.dialer", "com.android.phone",
    )

    private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** Estrae il nome pacchetto da un link Play Store o da un nome già pulito. */
    fun parse(text: String): String? {
        val t = text.trim()
        Regex("[?&]id=([a-zA-Z0-9_.]+)").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { PKG.matches(it) }
    }

    fun canAdd(ctx: Context, pkg: String) = pkg != ctx.packageName && pkg !in PROTECTED

    private fun installed(ctx: Context, pkg: String) = runCatching {
        if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    fun enforce(ctx: Context) {
        if (!Policy.isOwner(ctx)) return
        val dpm = Policy.dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        val present = Prefs.neverInstall(ctx).filter { canAdd(ctx, it) && installed(ctx, it) }
        if (present.isEmpty()) return
        runCatching { dpm.setPackagesSuspended(admin, present.toTypedArray(), true) }
        present.forEach { p ->
            runCatching {
                dpm.setUninstallBlocked(admin, p, false)
                val pi = PendingIntent.getBroadcast(
                    ctx, p.hashCode(), Intent(ctx, InstallStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                )
                ctx.packageManager.packageInstaller.uninstall(p, pi.intentSender)
            }
        }
    }
}
