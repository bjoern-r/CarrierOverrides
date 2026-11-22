package com.example.carrieroverrides

import android.os.PersistableBundle
import android.service.carrier.CarrierIdentifier
import android.service.carrier.CarrierService
import android.util.Log

class CustomCarrierConfigService : CarrierService() {

    override fun onLoadConfig(subscriptionId: Int, id: CarrierIdentifier): PersistableBundle {
        Log.i(TAG, "onLoadConfig(subId=$subscriptionId, mcc=${id.mcc}, mnc=${id.mnc})")
        return CarrierConfigStore.buildConfigBundle(applicationContext, subscriptionId)
    }

    @Deprecated("Deprecated in API 33")
    override fun onLoadConfig(id: CarrierIdentifier): PersistableBundle {
        return PersistableBundle()
    }

    companion object {
        private const val TAG = "CustomCarrierService"
    }
}
