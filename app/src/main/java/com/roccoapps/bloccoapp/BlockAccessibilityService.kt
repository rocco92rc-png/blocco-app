package com.roccoapps.bloccoapp

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Handler
import android.os.Looper
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(loop)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        current = pkg
        if (pkg in Prefs.limits(this)) runCatching { Limits.tick(this) }
        if (pkg !in Policy.targets(this)) return
        block(pkg)
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
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, BlockAccessibilityService::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
