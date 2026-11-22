package com.roccoapps.bloccoapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roccoapps.bloccoapp.update.Updater
import java.util.concurrent.TimeUnit

/** Ogni ~6 ore: riapplica i blocchi e, se c'è una versione nuova, la installa da sola. */
class PeriodicWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Policy.apply(applicationContext)
        GuardService.start(applicationContext)
        runCatching { Updater.autoUpdate(applicationContext) }
        return Result.success()
    }
}

object Work {
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PeriodicWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("blocco-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
