package com.roccoapps.bloccoapp

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Modalità base (senza Device Owner): quando si apre un'app bloccata, si torna alla home
 * e compare la schermata "App bloccata".
 */
class BlockAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null

    /** Ogni 30 s ricalcola i limiti anche se il servizio in primo piano è stato chiuso dal sistema,
     *  e butta fuori dall'app aperta se ha appena finito il tempo. */
    private val loop = object : Runnable {
        override fun run() {
            runCatching {
                if (Limits.tick(this@BlockAccessibilityService)) Policy.apply(this@BlockAccessibilityService)
                current?.let { if (it in Policy.targets(this@BlockAccessibilityService)) block(it) }
                GuardService.start(this@BlockAccessibilityService)
            }
            handler.postDelayed(this, 30_000)
        }
    }

    // ---- countdown in sovraimpressione per le app con limite
    private var overlay: CountdownOverlay? = null
    private var countPkg: String? = null
    private var endAt = 0L            // elapsedRealtime in cui finisce il tempo
    private var lastTick = 0L

    private val countdown = object : Runnable {
        override fun run() {
            val pkg = countPkg
            if (pkg == null || current != pkg) { stopCountdown(); return }
            val now = SystemClock.elapsedRealtime()
            // Schermo spento: il tempo non scorre (Android non lo conta come utilizzo).
            if (!getSystemService(PowerManager::class.java).isInteractive) endAt += now - lastTick
            lastTick = now
            val rem = endAt - now
            when {
                rem <= 0 -> {
                    stopCountdown()
                    val day = Limits.today()
                    Prefs.setOverLimit(this@BlockAccessibilityService, day, Prefs.overLimit(this@BlockAccessibilityService, day) + pkg)
                    Policy.apply(this@BlockAccessibilityService)
                    block(pkg)
                    return
                }
                rem <= 60_000 -> overlay().show("⏱ ${(rem + 999) / 1000} s", urgent = true)
                rem <= WARN_MS -> overlay().show("⏱ ${(rem + 59_999) / 60_000} min", urgent = false)
                else -> overlay?.hide()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun overlay() = overlay ?: CountdownOverlay(this).also { overlay = it }

    private fun startCountdown(pkg: String) {
        val lim = Prefs.limits(this)[pkg] ?: return
        val used = Limits.usageToday(this, setOf(pkg))[pkg] ?: 0L
        handler.removeCallbacks(countdown)
        countPkg = pkg
        lastTick = SystemClock.elapsedRealtime()
        endAt = lastTick + (lim * 60_000L - used)
        handler.post(countdown)
    }

    private fun stopCountdown() {
        handler.removeCallbacks(countdown)
        countPkg = null
        overlay?.hide()
    }

    /** Eventi da ignorare: tastiera e interfaccia di sistema non sono un "cambio app". */
    private fun isNoise(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ime.substringBefore('/') == pkg
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(loop)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        stopCountdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isNoise(pkg)) return
        if (pkg == packageName) { current = pkg; stopCountdown(); return }
        val changed = pkg != current
        current = pkg
        if (pkg in Prefs.limits(this)) runCatching { Limits.tick(this) }
        if (pkg in Policy.targets(this)) { stopCountdown(); block(pkg); return }
        if (changed) {
            if (pkg in Prefs.limits(this)) startCountdown(pkg) else stopCountdown()
        }
    }

    private fun block(pkg: String) {
        current = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        /** Da quanto prima della fine compare il conto alla rovescia. */
        const val WARN_MS = 5 * 60_000L

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, BlockAccessibilityService::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
