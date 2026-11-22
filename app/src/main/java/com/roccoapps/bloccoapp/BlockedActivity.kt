package com.roccoapps.bloccoapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Schermata mostrata quando si prova ad aprire un'app bloccata (modalità base). */
class BlockedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val why = when {
            pkg in Prefs.blocked(this) -> "è bloccata."
            pkg in Limits.suspendedToday(this) -> "ha finito il tempo di oggi. Riprova domani."
            else -> "è nella lista \"Mai installare\". Disinstallala."
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B3261E"))
            setPadding(60, 60, 60, 60)
        }
        root.addView(TextView(this).apply {
            text = "🔒\n\n$label $why"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "Chiudi"
            setOnClickListener { goHome() }
        })
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object { const val EXTRA_PKG = "pkg" }
}
