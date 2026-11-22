package com.roccoapps.bloccoapp

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Policy.apply(context)
        Work.schedule(context)
        GuardService.start(context)
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    }
}
