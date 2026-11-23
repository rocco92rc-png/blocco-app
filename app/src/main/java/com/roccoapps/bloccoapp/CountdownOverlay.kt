package com.roccoapps.bloccoapp

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Pillola in sovraimpressione con il tempo rimasto (solo dal servizio di Accessibilità). */
class CountdownOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private val d = ctx.resources.displayMetrics.density

    private val bg = GradientDrawable().apply { cornerRadius = 40 * d }

    fun show(text: String, urgent: Boolean) {
        val v = view ?: TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((16 * d).toInt(), (7 * d).toInt(), (16 * d).toInt(), (7 * d).toInt())
            background = bg
            elevation = 8 * d
        }.also { tv ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (36 * d).toInt()
            }
            runCatching { wm.addView(tv, lp) }
                .onFailure { Prefs.setDiag(ctx, "err", "overlay: $it") }
            view = tv
        }
        bg.setColor(if (urgent) Color.parseColor("#E6C62828") else Color.parseColor("#E6333333"))
        v.text = text
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
