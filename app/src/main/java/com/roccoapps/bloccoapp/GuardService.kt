package com.roccoapps.bloccoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Servizio sempre attivo (notifica silenziosa):
 *  - ogni 20 s controlla i limiti di tempo e sospende le app che li hanno finiti;
 *  - a mezzanotte le riattiva;
 *  - appena viene installata un'app, riapplica i blocchi (lista "Mai installare", app bloccate).
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastDay = 0

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Policy.apply(c) }
    }

    private val loop = object : Runnable {
        override fun run() {
            runCatching {
                val day = Limits.today()
                val changed = Limits.tick(this@GuardService)
                if (changed || day != lastDay) { lastDay = day; Policy.apply(this@GuardService) }
            }
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        runCatching { unregisterReceiver(pkgReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Protezione attiva", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Blocco App attivo")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)
    }

    companion object {
        private const val CH = "guard"
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, GuardService::class.java)) }
        }
    }
}
