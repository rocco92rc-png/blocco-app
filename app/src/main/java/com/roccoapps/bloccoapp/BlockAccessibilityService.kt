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
import android.app.ActivityManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
                Prefs.setDiag(this@BlockAccessibilityService, "acc_loop", System.currentTimeMillis().toString())
                if (Limits.tick(this@BlockAccessibilityService)) Policy.apply(this@BlockAccessibilityService)
                current?.let { if (it in Policy.targets(this@BlockAccessibilityService)) block(it) }
                GuardService.start(this@BlockAccessibilityService)
            }.onFailure { Prefs.setDiag(this@BlockAccessibilityService, "err", "acc loop: $it") }
            handler.postDelayed(this, 30_000)
        }
    }

    // ---- countdown in sovraimpressione per le app con limite
    private var overlay: CountdownOverlay? = null
    private var countPkg: String? = null
    private var endAt = 0L            // elapsedRealtime in cui finisce il tempo
    private var lastTick = 0L
    private var lastSync = 0L

    /** Ogni 5 s: quale app è davvero in primo piano (dati di utilizzo, non eventi). */
    private val watcher = object : Runnable {
        override fun run() {
            runCatching {
                closePip()
                val fg = Limits.foregroundApp(this@BlockAccessibilityService)
                Prefs.setDiag(this@BlockAccessibilityService, "fg", fg ?: "(nessuna)")
                Prefs.setDiag(this@BlockAccessibilityService, "acc_loop", System.currentTimeMillis().toString())
                val limited = fg != null && fg in Prefs.limits(this@BlockAccessibilityService)
                when {
                    !limited -> if (countPkg != null) stopCountdown()
                    fg in Policy.targets(this@BlockAccessibilityService) -> { stopCountdown(); block(fg!!) }
                    fg != countPkg || SystemClock.elapsedRealtime() - lastSync > 30_000 -> startCountdown(fg!!)
                }
            }.onFailure { Prefs.setDiag(this@BlockAccessibilityService, "err", "watcher: $it") }
            handler.postDelayed(this, 5_000)
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            val pkg = countPkg ?: return
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

    /** Avvia o riallinea il conto alla rovescia col tempo reale rimasto. */
    private fun startCountdown(pkg: String) {
        val lim = Prefs.limits(this)[pkg] ?: return
        val used = Limits.usageToday(this, setOf(pkg))[pkg] ?: 0L
        handler.removeCallbacks(countdown)
        countPkg = pkg
        lastTick = SystemClock.elapsedRealtime()
        lastSync = lastTick
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
        Prefs.setDiag(this, "acc_conn", System.currentTimeMillis().toString())
        handler.post(loop)
        handler.post(watcher)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        handler.removeCallbacks(watcher)
        stopCountdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            runCatching { closePip() }
            return
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isNoise(pkg)) return
        current = pkg
        if (pkg == packageName) return
        if (pkg in Prefs.limits(this)) runCatching { Limits.tick(this) }
        if (pkg in Policy.targets(this)) { stopCountdown(); block(pkg); return }
        // Avvio immediato quando si entra in un'app con limite (il watcher poi lo tiene allineato).
        if (pkg in Prefs.limits(this) && pkg != countPkg) startCountdown(pkg)
    }

    private fun block(pkg: String) {
        current = null
        // NIENTE tasto Home: con Home app come YouTube vanno in picture-in-picture.
        // Si apre la schermata di blocco sopra l'app, che così finisce in background "ferma".
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .putExtra(BlockedActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        // Poi si chiude il processo (stop anche all'audio).
        handler.postDelayed({ kill(pkg) }, 1_200)
        handler.postDelayed({ kill(pkg) }, 4_000)
    }

    private fun kill(pkg: String) {
        runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
    }

    /** Se un'app bloccata è rimpicciolita in un riquadro (PiP), lo chiude. */
    private var lastPip = 0L

    private fun closePip() {
        val targets = Policy.targets(this)
        if (targets.isEmpty()) return
        if (SystemClock.elapsedRealtime() - lastPip < 3_000) return
        val pip = runCatching { windows }.getOrNull()?.firstOrNull { w ->
            w.isInPictureInPictureMode && w.root?.packageName?.toString() in targets
        } ?: return
        val pkg = pip.root?.packageName?.toString() ?: return
        lastPip = SystemClock.elapsedRealtime()
        // 1) tocco sul riquadro -> compare il menu di sistema con la X
        pip.root?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            if (!clickClose()) {
                // 2) se la X non si trova: riporta l'app a schermo intero e la blocca normalmente
                block(pkg)
            } else handler.postDelayed({ kill(pkg) }, 800)
        }, 700)
    }

    private val closeWords = Regex("(?i)^(chiudi|close|ignora|dismiss|esci)\\b.*")

    private fun clickClose(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        for (w in ws) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != "com.android.systemui") continue
            val node = find(root) { n ->
                val label = (n.contentDescription ?: n.text)?.toString() ?: return@find false
                closeWords.matches(label.trim())
            } ?: continue
            var c: AccessibilityNodeInfo? = node
            while (c != null && !c.isClickable) c = c.parent
            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    private fun find(n: AccessibilityNodeInfo, depth: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        if (depth > 25) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            find(c, depth + 1, pred)?.let { return it }
        }
        return null
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
