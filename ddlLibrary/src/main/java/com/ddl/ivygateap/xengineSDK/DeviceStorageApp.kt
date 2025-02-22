package com.ddl.ivygateap.xengineSDK

import android.app.Application
import android.content.Context

class DeviceStorageApp(context: Context) : Application() {
    init {
        attachBaseContext(context.createDeviceProtectedStorageContext())
    }

    /**
     * Thou shalt not get the REAL underlying application context which would no longer be operating under device
     * protected storage.
     */
    override fun getApplicationContext() = this
}
