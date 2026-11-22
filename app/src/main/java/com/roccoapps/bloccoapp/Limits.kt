package com.roccoapps.bloccoapp

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar

/**
 * Limiti di tempo giornalieri. Il tempo si legge dalle statistiche di utilizzo di Android
 * (serve il permesso "Accesso ai dati di utilizzo", da dare una volta).
 * Se il permesso viene tolto, le app con limite restano BLOCCATE (non si aggira togliendolo).
 */
object Limits {

    fun today(): Int = Calendar.getInstance().let {
        it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH)
    }

    private fun midnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** App da tenere sospese oggi per via dei limiti. */
    fun suspendedToday(ctx: Context): Set<String> {
        val limits = Prefs.limits(ctx)
        if (limits.isEmpty()) return emptySet()
        if (!hasUsageAccess(ctx)) return limits.keys // fail-closed
        return Prefs.overLimit(ctx, today()) intersect limits.keys
    }

    /** Millisecondi in primo piano oggi per ciascuna app richiesta. */
    fun usageToday(ctx: Context, pkgs: Set<String>): Map<String, Long> {
        val res = HashMap<String, Long>()
        if (pkgs.isEmpty() || !hasUsageAccess(ctx)) return res
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return res
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(midnight(), now) ?: return res
        val open = HashMap<String, Long>()
        val e = UsageEvents.Event()
        fun close(p: String, t: Long) {
            open.remove(p)?.let { start -> res[p] = (res[p] ?: 0L) + (t - start).coerceAtLeast(0) }
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            when (e.eventType) {
                1 -> if (p in pkgs && p !in open) open[p] = e.timeStamp            // ACTIVITY_RESUMED
                2, 23 -> if (p in open) close(p, e.timeStamp)                       // PAUSED / STOPPED
                16, 26 -> open.keys.toList().forEach { close(it, e.timeStamp) }     // schermo spento / spegnimento
            }
        }
        open.keys.toList().forEach { close(it, now) }
        return res
    }

    /** Ricalcola chi ha superato il limite. true = l'elenco delle app sospese è cambiato. */
    fun tick(ctx: Context): Boolean {
        val limits = Prefs.limits(ctx)
        if (limits.isEmpty()) return false
        val day = today()
        val before = Prefs.overLimit(ctx, day)
        val used = usageToday(ctx, limits.keys)
        val over = before + limits.filter { (p, min) -> (used[p] ?: 0L) >= min * 60_000L }.keys
        if (over != before) Prefs.setOverLimit(ctx, day, over)
        return over != before
    }
}
