package com.roccoapps.bloccoapp

import android.content.Context
import android.content.Intent

/**
 * App "libere": niente pausa, non contano nella sessione lunga né nel tempo totale.
 * ytmp3 (musica) viene riconosciuta in automatico dal nome; Maps e Waze sono sempre libere.
 */
object Free {
    private val AUTO = Regex("(?i)ytmp3")
    /** Navigatori: mai intromettersi (anche mentre si guida). */
    val NAV = setOf("com.google.android.apps.maps", "com.waze", "com.google.android.apps.mapslite")
    @Volatile private var autoCache: Set<String>? = null
    @Volatile private var cacheAt = 0L

    /** Pacchetti riconosciuti dal nome (cache 10 min). */
    fun auto(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        autoCache?.let { if (now - cacheAt < 600_000) return it }
        val pm = ctx.packageManager
        val found = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { AUTO.containsMatchIn(it.loadLabel(pm).toString()) || AUTO.containsMatchIn(it.activityInfo.packageName) }
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        autoCache = found; cacheAt = now
        return found
    }

    /** Claude e app di chiamata (telefono, schermata chiamata in corso di vari produttori). */
    val ALWAYS = setOf(
        "com.anthropic.claude",
        "com.google.android.dialer", "com.android.dialer", "com.android.incallui",
        "com.android.server.telecom", "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.oplus.dialer", "com.coloros.dialer", "com.oppo.dialer", "com.android.contacts",
        "com.miui.voip", "com.android.phone",
    )

    fun all(ctx: Context): Set<String> = auto(ctx) + NAV + ALWAYS + ctx.packageName + Prefs.freeApps(ctx)

    /** Chiamata in corso (telefono o VoIP tipo WhatsApp), senza permessi extra. */
    fun inCall(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        am.mode == android.media.AudioManager.MODE_IN_CALL ||
            am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == android.media.AudioManager.MODE_RINGTONE
    }.getOrDefault(false)
    fun isFree(ctx: Context, pkg: String) = pkg in all(ctx)
}
