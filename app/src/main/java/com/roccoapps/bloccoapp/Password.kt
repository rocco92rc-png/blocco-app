package com.roccoapps.bloccoapp

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password di sblocco: salvata solo come hash, con attesa crescente dopo 3 errori. */
object Password {
    private const val ITER = 120_000

    fun isSet(ctx: Context) = Prefs.pwHash(ctx) != null

    fun set(ctx: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Prefs.setPw(ctx, b64(hash(pw, salt)), b64(salt))
        Prefs.setFail(ctx, 0, 0L)
    }

    /** Millisecondi di attesa rimasti prima di poter riprovare (0 = si può provare). */
    fun waitMs(ctx: Context): Long = (Prefs.lockedUntil(ctx) - System.currentTimeMillis()).coerceAtLeast(0L)

    sealed class Result {
        object Ok : Result()
        data class Wrong(val attemptsLeft: Int) : Result()
        data class Locked(val waitMs: Long) : Result()
    }

    fun check(ctx: Context, pw: String): Result {
        val wait = waitMs(ctx)
        if (wait > 0) return Result.Locked(wait)
        val h = Prefs.pwHash(ctx) ?: return Result.Ok
        val salt = Base64.decode(Prefs.pwSalt(ctx), Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(pw, salt), Base64.decode(h, Base64.NO_WRAP))) {
            Prefs.setFail(ctx, 0, 0L)
            return Result.Ok
        }
        val fails = Prefs.failCount(ctx) + 1
        return if (fails % 3 == 0) {
            // 3 errori -> 5 min, 6 errori -> 10 min, 9 -> 20 min ... (max 24 h)
            val mins = (5L shl ((fails / 3) - 1).coerceAtMost(8)).coerceAtMost(24 * 60)
            val until = System.currentTimeMillis() + mins * 60_000
            Prefs.setFail(ctx, fails, until)
            Result.Locked(mins * 60_000)
        } else {
            Prefs.setFail(ctx, fails, 0L)
            Result.Wrong(3 - fails % 3)
        }
    }

    private fun hash(pw: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
