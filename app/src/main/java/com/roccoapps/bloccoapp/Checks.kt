package com.roccoapps.bloccoapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Una voce della verifica autorizzazioni. ok = null -> non verificabile in automatico. */
data class Check(val title: String, val why: String, val ok: Boolean?, val fix: (() -> Intent)?)

object Checks {

    private fun appDetails(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    private fun alive(ctx: Context, key: String): Boolean {
        val t = Prefs.diag(ctx, key)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - t < 120_000
    }

    @SuppressLint("BatteryLife")
    fun all(ctx: Context): List<Check> {
        val owner = Policy.isOwner(ctx)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val accOn = BlockAccessibilityService.isEnabled(ctx)
        return buildList {
            add(Check(
                "Accesso ai dati di utilizzo",
                "Grafici, limiti giornalieri e conto alla rovescia.",
                Limits.hasUsageAccess(ctx),
            ) { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
            add(Check(
                "Accessibilità",
                if (owner) "Conto alla rovescia in sovraimpressione." else "Chiude le app bloccate e mostra il conto alla rovescia.",
                accOn,
            ) { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
            if (accOn) add(Check(
                "Servizio accessibilità in funzione",
                "Se è attivato ma non risponde, spegnilo e riaccendilo in Accessibilità.",
                alive(ctx, "acc_loop"),
            ) { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
            add(Check(
                "Notifiche",
                "Servono alla notifica fissa che tiene vivo il servizio dei limiti.",
                NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            ) {
                if (Build.VERSION.SDK_INT >= 26)
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                else appDetails(ctx)
            })
            add(Check(
                "Batteria senza restrizioni",
                "Evita che il telefono chiuda i servizi per risparmiare batteria.",
                pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            ) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            })
            if (Build.VERSION.SDK_INT >= 30) add(Check(
                "\"Sospendi se inutilizzata\" disattivato",
                "Altrimenti Android toglie i permessi all'app dopo un po'.",
                ctx.packageManager.isAutoRevokeWhitelisted,
            ) { Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:${ctx.packageName}")) })
            add(Check(
                "Servizio limiti in funzione",
                "La notifica \"Blocco App attivo\" deve esserci sempre.",
                alive(ctx, "guard_loop"),
            ) { appDetails(ctx) })
            add(Check(
                "Avvio automatico (Xiaomi / Oppo)",
                "Non verificabile dall'app: in Info app attiva \"Avvio automatico\" se c'è.",
                null,
            ) { appDetails(ctx) })
        }
    }

    fun missing(ctx: Context) = all(ctx).count { it.ok == false }
}
