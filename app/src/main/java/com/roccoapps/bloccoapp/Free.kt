package com.roccoapps.bloccoapp

import android.content.Context
import android.content.Intent

/**
 * App "libere": niente pausa, non contano nella sessione lunga né nel tempo totale.
 * ytmp3 (musica) viene riconosciuta in automatico dal nome; altre si aggiungono a mano.
 */
object Free {
    private val AUTO = Regex("(?i)ytmp3")
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

    fun all(ctx: Context): Set<String> = auto(ctx) + Prefs.freeApps(ctx)
    fun isFree(ctx: Context, pkg: String) = pkg in all(ctx)
}
