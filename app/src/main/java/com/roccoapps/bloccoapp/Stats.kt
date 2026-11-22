package com.roccoapps.bloccoapp

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Statistiche di utilizzo calcolate dagli eventi di Android (stesso metodo del Benessere digitale). */
data class UsageReport(
    val dayStarts: List<Long>,                 // inizio di ciascuno degli ultimi N giorni (l'ultimo = oggi)
    val dayTotals: List<Long>,                 // ms totali per giorno
    val hourToday: LongArray,                  // ms per ora di oggi (24)
    val appToday: Map<String, Long>,           // ms per app oggi
    val opensToday: Map<String, Int>,          // aperture per app oggi
    val app7d: Map<String, Long>,              // ms per app negli N giorni
) {
    val today get() = dayTotals.lastOrNull() ?: 0L
    val yesterday get() = dayTotals.getOrNull(dayTotals.size - 2) ?: 0L
    val opensTotal get() = opensToday.values.sum()
}

object Stats {

    private fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextHour(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.HOUR_OF_DAY, 1)
    }.timeInMillis

    private fun hourOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    /** Pacchetti da non contare (launcher, interfaccia di sistema, questa app). */
    private fun ignored(ctx: Context): Set<String> {
        val home = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }
        return home.toSet() + setOf(ctx.packageName, "com.android.systemui", "android")
    }

    fun report(ctx: Context, days: Int = 7): UsageReport? {
        if (!Limits.hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val starts = (days - 1 downTo 0).map { startOfDay(it) }
        val dayTotals = LongArray(days)
        val hourToday = LongArray(24)
        val appToday = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val app7d = HashMap<String, Long>()
        val skip = ignored(ctx)
        val now = System.currentTimeMillis()
        val todayStart = starts.last()

        fun dayIndex(t: Long): Int {
            for (i in starts.indices.reversed()) if (t >= starts[i]) return i
            return -1
        }

        fun add(pkg: String, s0: Long, e: Long) {
            var s = s0.coerceAtLeast(starts.first())
            while (s < e) {
                val seg = minOf(e, nextHour(s))
                val ms = seg - s
                val d = dayIndex(s)
                if (d >= 0) {
                    dayTotals[d] += ms
                    app7d[pkg] = (app7d[pkg] ?: 0L) + ms
                    if (s >= todayStart) {
                        hourToday[hourOf(s)] += ms
                        appToday[pkg] = (appToday[pkg] ?: 0L) + ms
                    }
                }
                s = seg
            }
        }

        val events = usm.queryEvents(starts.first(), now) ?: return null
        val open = HashMap<String, Long>()
        val lastClose = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            when (e.eventType) {
                1 -> if (p !in skip && p !in open) {
                    open[p] = e.timeStamp
                    // cambio schermata dentro la stessa app (<5 s) = non è una nuova apertura
                    if (e.timeStamp >= todayStart && e.timeStamp - (lastClose[p] ?: 0L) > 5_000)
                        opens[p] = (opens[p] ?: 0) + 1
                }
                2, 23 -> open.remove(p)?.let { add(p, it, e.timeStamp); lastClose[p] = e.timeStamp }
                16, 26 -> { open.forEach { (k, v) -> add(k, v, e.timeStamp) }; open.clear() }
            }
        }
        open.forEach { (k, v) -> add(k, v, now) }
        return UsageReport(starts, dayTotals.toList(), hourToday, appToday, opens, app7d)
    }

    fun fmt(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "<1 min"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}
