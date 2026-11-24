package com.roccoapps.bloccoapp

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni salvate nella memoria "device protected" (leggibile anche prima dello sblocco dopo il riavvio). */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("blocco", Context.MODE_PRIVATE)

    // App che l'utente vuole bloccate.
    fun blocked(ctx: Context): Set<String> = sp(ctx).getStringSet("blocked", emptySet())!!.toSet()
    fun setBlocked(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("blocked", v.toSet()).apply()

    // App effettivamente sospese da noi all'ultimo giro (per sapere cosa riattivare).
    fun applied(ctx: Context): Set<String> = sp(ctx).getStringSet("applied", emptySet())!!.toSet()
    fun setApplied(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("applied", v.toSet()).apply()

    // Protezioni extra (Device Owner).
    fun protection(ctx: Context, p: Protection): Boolean = sp(ctx).getBoolean("prot_" + p.name, p.defaultOn)
    fun setProtection(ctx: Context, p: Protection, on: Boolean) =
        sp(ctx).edit().putBoolean("prot_" + p.name, on).apply()

    // Password (hash PBKDF2 + salt) e blocco dopo tentativi sbagliati.
    fun pwHash(ctx: Context): String? = sp(ctx).getString("pw_hash", null)
    fun pwSalt(ctx: Context): String? = sp(ctx).getString("pw_salt", null)
    fun setPw(ctx: Context, hash: String, salt: String) =
        sp(ctx).edit().putString("pw_hash", hash).putString("pw_salt", salt).apply()

    fun failCount(ctx: Context) = sp(ctx).getInt("fail_count", 0)
    fun lockedUntil(ctx: Context) = sp(ctx).getLong("locked_until", 0L)
    fun setFail(ctx: Context, count: Int, lockedUntil: Long) =
        sp(ctx).edit().putInt("fail_count", count).putLong("locked_until", lockedUntil).apply()

    // "Mai installare": si può SOLO aggiungere (nessuna funzione per togliere).
    fun neverInstall(ctx: Context): Set<String> = sp(ctx).getStringSet("never", emptySet())!!.toSet()
    fun addNeverInstall(ctx: Context, pkg: String) =
        sp(ctx).edit().putStringSet("never", neverInstall(ctx) + pkg).apply()

    // Limiti giornalieri (minuti): si impostano UNA volta, poi non si modificano più.
    fun limits(ctx: Context): Map<String, Int> =
        sp(ctx).getStringSet("limits", emptySet())!!.mapNotNull {
            val i = it.lastIndexOf('='); if (i <= 0) null
            else it.substring(0, i) to (it.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
    /** false se per quell'app un limite esiste già (non si cambia). */
    fun addLimit(ctx: Context, pkg: String, minutes: Int): Boolean {
        val cur = limits(ctx)
        if (pkg in cur) return false
        sp(ctx).edit().putStringSet("limits", (cur + (pkg to minutes)).map { "${it.key}=${it.value}" }.toSet()).apply()
        return true
    }

    // App con limite superato OGGI (giorno = yyyymmdd).
    fun overLimit(ctx: Context, day: Int): Set<String> =
        if (sp(ctx).getInt("over_day", 0) == day) sp(ctx).getStringSet("over", emptySet())!!.toSet() else emptySet()
    fun setOverLimit(ctx: Context, day: Int, v: Set<String>) =
        sp(ctx).edit().putInt("over_day", day).putStringSet("over", v.toSet()).apply()

    // Richiesta di disattivazione totale (attesa 24 h prima di poterla confermare).
    fun releaseRequestedAt(ctx: Context) = sp(ctx).getLong("release_req", 0L)
    fun setReleaseRequestedAt(ctx: Context, t: Long) = sp(ctx).edit().putLong("release_req", t).apply()

    // Modalità base: senza Device Owner, blocco tramite accessibilità.
    fun basicMode(ctx: Context) = sp(ctx).getBoolean("basic", false)
    fun setBasicMode(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("basic", on).apply()

    // Diagnostica: segnali di vita dei servizi e ultimo errore.
    fun diag(ctx: Context, key: String): String? = sp(ctx).getString("diag_" + key, null)
    fun setDiag(ctx: Context, key: String, v: String) = sp(ctx).edit().putString("diag_" + key, v).apply()

    // App con "pausa prima di aprire" (aggiungere libero, togliere con password).
    fun pauseApps(ctx: Context): Set<String> = sp(ctx).getStringSet("pause", emptySet())!!.toSet()
    fun setPauseApps(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("pause", v.toSet()).apply()

    // App libere aggiunte a mano (ytmp3 è riconosciuta in automatico).
    fun freeApps(ctx: Context): Set<String> = sp(ctx).getStringSet("free", emptySet())!!.toSet()
    fun setFreeApps(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("free", v.toSet()).apply()

    // Avviso sessione lunga.
    fun sessionAlert(ctx: Context) = sp(ctx).getBoolean("session_alert", true)
    fun setSessionAlert(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("session_alert", on).apply()
    fun sessionFirstMin(ctx: Context) = sp(ctx).getInt("session_first", 15)
    fun setSessionFirstMin(ctx: Context, m: Int) = sp(ctx).edit().putInt("session_first", m).apply()

    fun lastUpdateCheck(ctx: Context) = sp(ctx).getLong("last_upd", 0L)
    fun setLastUpdateCheck(ctx: Context, t: Long) = sp(ctx).edit().putLong("last_upd", t).apply()
}

/** Protezioni anti-aggiramento attivabili da Device Owner. */
enum class Protection(val title: String, val desc: String, val defaultOn: Boolean) {
    SAFE_BOOT("Blocca modalità provvisoria", "Impedisce di riavviare in modalità provvisoria per togliere il blocco.", true),
    FACTORY_RESET("Blocca ripristino dalle Impostazioni", "Il ripristino di fabbrica dal menu Impostazioni non è disponibile.", true),
    USERS("Blocca nuovi utenti e Ospite", "Impedisce di creare un utente o usare Ospite per installare le app altrove.", true),
    DATE_TIME("Blocca modifica data e ora", "Impedisce di spostare l'orologio per saltare l'attesa dopo password sbagliate.", true),
    DEBUG("Blocca Opzioni sviluppatore / ADB", "Disattiva il debug USB e wireless. Lascialo spento se sviluppi sul telefono.", false),
}
