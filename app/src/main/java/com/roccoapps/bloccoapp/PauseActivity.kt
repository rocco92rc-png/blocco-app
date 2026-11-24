package com.roccoapps.bloccoapp

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** "Perché la stai aprendo?" — 10 secondi prima di poter entrare nell'app. */
class PauseActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var left = SECONDS
    private lateinit var openBtn: Button
    private lateinit var pkg: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1F2A44"))
            setPadding((28 * d).toInt(), (28 * d).toInt(), (28 * d).toInt(), (28 * d).toInt())
        }
        fun tv(t: String, size: Float, alpha: Float = 1f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); this.alpha = alpha; gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(tv("🫁", 48f))
        root.addView(tv("Perché stai aprendo $label?", 24f))
        root.addView(tv("Hai un motivo preciso o è noia?\nRespira e decidi.", 16f, 0.8f))
        root.addView(Button(this).apply {
            text = "Lascia perdere"
            textSize = 18f
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(Color.parseColor("#2E7D32")) }
            setTextColor(Color.WHITE)
            setOnClickListener { giveUp() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt()).apply {
            topMargin = (32 * d).toInt()
        })
        openBtn = Button(this).apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                Pause.passed(this@PauseActivity, pkg)
                // torna all'app (che è sotto)
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                finish()
            }
        }
        root.addView(openBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * d).toInt()).apply {
            topMargin = (12 * d).toInt()
        })
        setContentView(root)
        tickDown()
    }

    private fun tickDown() {
        if (left > 0) {
            openBtn.text = "Apri comunque ($left)"
            left--
            handler.postDelayed({ tickDown() }, 1_000)
        } else {
            openBtn.text = "Apri comunque"
            openBtn.isEnabled = true
            openBtn.alpha = 1f
        }
    }

    private fun giveUp() {
        Pause.reset(pkg) // se la riapri subito, la pausa ricompare
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val p = pkg
        handler.postDelayed({
            runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(p) }
        }, 1_000)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = giveUp()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val SECONDS = 10
    }
}

/** Regole della pausa prima di aprire. */
object Pause {
    private val passedAt = HashMap<String, Long>()
    private val leftAt = HashMap<String, Long>()

    /** App con pausa = scelte + tutte quelle con limite, tranne le libere. */
    fun apps(ctx: android.content.Context): Set<String> =
        (Prefs.pauseApps(ctx) + Prefs.limits(ctx).keys) - Free.all(ctx) - ctx.packageName

    fun passed(ctx: android.content.Context, pkg: String) { passedAt[pkg] = System.currentTimeMillis() }
    fun left(pkg: String) { leftAt[pkg] = System.currentTimeMillis() }
    fun reset(pkg: String) { leftAt.remove(pkg); passedAt.remove(pkg); resetAfter[pkg] = System.currentTimeMillis() }
    private val resetAfter = HashMap<String, Long>()

    /** Serve la pausa entrando in [pkg]? No se appena superata o se si è usciti da meno di 1 minuto. */
    fun needed(ctx: android.content.Context, pkg: String): Boolean {
        if (pkg !in apps(ctx)) return false
        val now = System.currentTimeMillis()
        if (now - (passedAt[pkg] ?: 0L) < 15_000) return false
        val left = leftAt[pkg] ?: 0L
        if (now - left < 60_000 && left > (resetAfter[pkg] ?: 0L) + 3_000) return false
        return true
    }
}
