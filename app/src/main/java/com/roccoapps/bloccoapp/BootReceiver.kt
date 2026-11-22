package com.roccoapps.bloccoapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al riavvio riapplica i blocchi (le app sospese restano sospese comunque) e i controlli periodici. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Policy.apply(context)
        GuardService.start(context)
        runCatching { Work.schedule(context) }
    }
}
