package com.roccoapps.bloccoapp

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Applica i blocchi tramite Device Owner:
 *  - le app scelte vengono SOSPESE (icona grigia, non si aprono, niente notifiche);
 *  - non si possono disinstallare (così non si può reinstallarle "pulite");
 *  - Blocco App stessa non si può disinstallare né disattivare dalle Impostazioni;
 *  - protezioni extra (modalità provvisoria, utenti, data/ora...).
 */
object Policy {
    private const val TAG = "BloccoPolicy"

    fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)!!
    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** App da tenere chiuse adesso (usato dalla modalità base). */
    fun targets(ctx: Context): Set<String> =
        Prefs.blocked(ctx) + Limits.suspendedToday(ctx) + Prefs.neverInstall(ctx) - ctx.packageName

    /** Riallinea il telefono a quanto salvato. Ritorna le app che Android non ha permesso di bloccare. */
    @Synchronized
    fun apply(ctx: Context): List<String> {
        if (!isOwner(ctx)) return emptyList()
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        val hard = Prefs.blocked(ctx)
        // Sospese = bloccate sempre + quelle che oggi hanno finito il tempo.
        val blocked = (hard + Limits.suspendedToday(ctx)) - ctx.packageName
        val previous = Prefs.applied(ctx)

        // 1) Riattiva quelle tolte dalla lista.
        val release = (previous - blocked).toTypedArray()
        if (release.isNotEmpty()) {
            runCatching { dpm.setPackagesSuspended(admin, release, false) }
            release.forEach { p -> runCatching { dpm.setUninstallBlocked(admin, p, false) } }
        }

        // 2) Sospendi e blocca la disinstallazione delle app in lista.
        var failed = emptyList<String>()
        if (blocked.isNotEmpty()) {
            failed = runCatching { dpm.setPackagesSuspended(admin, blocked.toTypedArray(), true).toList() }
                .onFailure { Log.w(TAG, "suspend", it) }.getOrDefault(blocked.toList())
        }
        // Le app bloccate o con limite non si disinstallano (niente reinstallazione "pulita").
        (blocked + Prefs.limits(ctx).keys).forEach { p ->
            runCatching { dpm.setUninstallBlocked(admin, p, true) }
        }
        Prefs.setApplied(ctx, blocked)

        // 2b) App della lista "Mai installare" presenti sul telefono: via subito.
        NeverInstall.enforce(ctx)

        // 3) Blocco App non si disinstalla.
        runCatching { dpm.setUninstallBlocked(admin, ctx.packageName, true) }

        // 4) Protezioni extra.
        Protection.values().forEach { p ->
            val on = Prefs.protection(ctx, p)
            restrictionsOf(p).forEach { r ->
                runCatching {
                    if (on) dpm.addUserRestriction(admin, r) else dpm.clearUserRestriction(admin, r)
                }.onFailure { Log.w(TAG, "restriction $r", it) }
            }
        }
        return failed
    }

    private fun restrictionsOf(p: Protection): List<String> = when (p) {
        Protection.SAFE_BOOT -> listOf(UserManager.DISALLOW_SAFE_BOOT)
        Protection.FACTORY_RESET -> listOf(UserManager.DISALLOW_FACTORY_RESET)
        Protection.USERS -> buildList {
            add(UserManager.DISALLOW_ADD_USER)
            if (Build.VERSION.SDK_INT >= 28) add(UserManager.DISALLOW_USER_SWITCH)
        }
        Protection.DATE_TIME -> listOf(UserManager.DISALLOW_CONFIG_DATE_TIME)
        Protection.DEBUG -> listOf(UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    /**
     * Toglie TUTTO: riattiva le app, rimuove le protezioni e smette di essere Device Owner.
     * Dopo si può disinstallare Blocco App normalmente.
     */
    fun releaseAll(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)
        val all = (Prefs.applied(ctx) + Prefs.blocked(ctx) + Prefs.limits(ctx).keys).toTypedArray()
        if (all.isNotEmpty()) {
            runCatching { dpm.setPackagesSuspended(admin, all, false) }
            all.forEach { p -> runCatching { dpm.setUninstallBlocked(admin, p, false) } }
        }
        runCatching { dpm.setUninstallBlocked(admin, ctx.packageName, false) }
        Protection.values().forEach { p ->
            restrictionsOf(p).forEach { r -> runCatching { dpm.clearUserRestriction(admin, r) } }
        }
        Prefs.setApplied(ctx, emptySet())
        Prefs.setBlocked(ctx, emptySet())
        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(ctx.packageName) }
    }
}
