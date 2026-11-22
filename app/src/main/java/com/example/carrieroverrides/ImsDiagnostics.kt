package com.example.carrieroverrides

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Telephony
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.NetworkRegistrationInfo
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager

/**
 * Read-only VoLTE readiness checks. Everything here relies on READ_PHONE_STATE and/or the
 * app's UICC carrier privileges (which substitute for READ_PRECISE_PHONE_STATE and APN access).
 */
object ImsDiagnostics {

    enum class Level(val symbol: String, val color: Int) {
        OK("✔", 0xFF2E7D32.toInt()),
        WARN("!", 0xFFEF6C00.toInt()),
        FAIL("✘", 0xFFC62828.toInt()),
        INFO("•", 0xFF757575.toInt()),
    }

    data class Check(val level: Level, val title: String, val detail: String)

    private fun mmTelManager(context: Context, subId: Int): ImsMmTelManager =
        context.getSystemService(ImsManager::class.java).getImsMmTelManager(subId)

    fun runChecks(context: Context, sub: SubscriptionInfo): List<Check> {
        val subId = sub.subscriptionId
        val ccm = context.getSystemService(CarrierConfigManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
        val config = try {
            ccm.getConfigForSubId(subId)
        } catch (e: SecurityException) {
            null
        }

        val checks = mutableListOf<Check>()
        if (config == null) {
            checks += Check(Level.FAIL, "Carrier config", "Cannot read the effective carrier config.")
        } else {
            checks += volteAvailable(context, subId, config)
            checks += provisioning(config)
            checks += entitlement(config)
            checks += vowifi(config)
        }
        checks += userVolteSwitch(context, subId, config)
        checks += imsApn(context, sub)
        checks += mobileRegistration(tm)
        checks += vops(tm)
        return checks
    }

    /**
     * IMS registration state; [onResult] runs once on the main thread.
     *
     * getRegistrationState() is documented as carrier-privilege accessible, but Android 14
     * enforces READ_PRIVILEGED_PHONE_STATE (system only) for it. registerImsRegistrationCallback()
     * accepts carrier privileges, reports the current state immediately on registration and,
     * when unregistered, also delivers the reason.
     */
    @SuppressLint("MissingPermission") // covered by carrier privileges
    fun checkImsRegistration(context: Context, subId: Int, onResult: (Check) -> Unit) {
        val title = "IMS registration"
        val mmTel = try {
            mmTelManager(context, subId)
        } catch (e: Exception) {
            onResult(Check(Level.WARN, title, "Unavailable: ${e.javaClass.simpleName}"))
            return
        }

        val callback = object : RegistrationManager.RegistrationCallback() {
            private var done = false

            private fun report(check: Check) {
                if (done) return
                done = true
                try {
                    mmTel.unregisterImsRegistrationCallback(this)
                } catch (_: Exception) {
                }
                onResult(check)
            }

            override fun onRegistered(attributes: ImsRegistrationAttributes) {
                val via = when (attributes.transportType) {
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "mobile network (VoLTE/VoNR)"
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "Wi-Fi (VoWiFi)"
                    else -> "unknown transport"
                }
                report(Check(Level.OK, title, "Registered via $via."))
            }

            override fun onRegistering(attributes: ImsRegistrationAttributes) {
                report(Check(Level.WARN, title, "Registering\u2026 refresh in a few seconds."))
            }

            override fun onUnregistered(info: ImsReasonInfo) {
                val reason = buildString {
                    append("code ${info.code}")
                    if (info.extraCode != 0) append(", extra ${info.extraCode}")
                    info.extraMessage?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                }
                report(
                    Check(
                        Level.FAIL, title,
                        "Not registered ($reason). Fix the items marked \u2718/! above; if all " +
                            "pass, the network's IMS core or the modem's IMS profile is the limit."
                    )
                )
            }
        }

        try {
            mmTel.registerImsRegistrationCallback(context.mainExecutor, callback)
        } catch (e: ImsException) {
            // Thrown when the IMS service is not available for this subscription (yet).
            onResult(Check(Level.FAIL, title, "IMS service not available for this SIM (code ${e.code})."))
        } catch (e: Exception) {
            onResult(Check(Level.WARN, title, "Unavailable: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun volteAvailable(context: Context, subId: Int, config: android.os.PersistableBundle): Check {
        val title = "VoLTE allowed by carrier config"
        val effective = config.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
        val override = CarrierConfigStore.getState(context, subId, "volte")
        return when {
            effective && override == CarrierConfigStore.TriState.ENABLED ->
                Check(Level.OK, title, "Yes (forced by this app).")
            effective -> Check(Level.OK, title, "Yes (carrier default).")
            override == CarrierConfigStore.TriState.ENABLED -> Check(
                Level.FAIL, title,
                "Override is set but not applied. Is this app the bound carrier service for this " +
                    "SIM (main user, no other carrier app)? Check 'dumpsys carrier_config'."
            )
            else -> Check(Level.FAIL, title, "No. Set 'VoLTE / Vo4G' to Force Enabled above.")
        }
    }

    private fun provisioning(config: android.os.PersistableBundle): Check {
        val title = "Operator provisioning gate"
        val required = config.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)
        val mmtelBundle = config.getPersistableBundle("ims.mmtel_requires_provisioning_bundle")
        return when {
            required -> Check(
                Level.WARN, title,
                "carrier_volte_provisioning_required_bool = true: VoLTE stays off until the " +
                    "operator provisions it (OMA-DM / TS.43)."
            )
            mmtelBundle != null && !mmtelBundle.isEmpty -> Check(
                Level.WARN, title,
                "ims.mmtel_requires_provisioning_bundle is non-empty: per-RAT provisioning required."
            )
            else -> Check(Level.OK, title, "No provisioning required.")
        }
    }

    private fun entitlement(config: android.os.PersistableBundle): Check {
        val title = "TS.43 entitlement (ImsServiceEntitlement)"
        val url = config.getString("imsserviceentitlement.entitlement_server_url_string").orEmpty()
        val enabled = config.getBoolean("imsserviceentitlement.ims_provisioning_bool")
        return when {
            enabled && url.isBlank() -> Check(
                Level.FAIL, title,
                "Entitlement provisioning enabled but no server URL configured; it can never succeed."
            )
            enabled -> Check(Level.INFO, title, "Active, server: $url")
            url.isNotBlank() -> Check(Level.INFO, title, "Server configured ($url), IMS provisioning off.")
            else -> Check(Level.OK, title, "Inactive; does not block VoLTE.")
        }
    }

    private fun vowifi(config: android.os.PersistableBundle): Check {
        val wfc = config.getBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)
        return Check(Level.INFO, "VoWiFi allowed by carrier config", if (wfc) "Yes" else "No")
    }

    @SuppressLint("MissingPermission") // covered by carrier privileges
    private fun userVolteSwitch(context: Context, subId: Int, config: android.os.PersistableBundle?): Check {
        val title = "User VoLTE switch (4G Calling)"
        return try {
            val on = mmTelManager(context, subId).isAdvancedCallingSettingEnabled
            val hidden = config?.getBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL) == true
            when {
                on -> Check(Level.OK, title, "Enabled.")
                hidden -> Check(Level.FAIL, title, "Off and hidden in Settings (hide_enhanced_4g_lte_bool).")
                else -> Check(Level.WARN, title, "Off. Enable VoLTE / 4G Calling in the SIM settings.")
            }
        } catch (e: Exception) {
            Check(Level.WARN, title, "Unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun imsApn(context: Context, sub: SubscriptionInfo): Check {
        val title = "IMS APN"
        val numeric = "${sub.mccString.orEmpty()}${sub.mncString.orEmpty()}"
        val projection = arrayOf("name", "apn", Telephony.Carriers.TYPE)
        val attempts = listOf(
            Triple(Telephony.Carriers.CONTENT_URI, "${Telephony.Carriers.NUMERIC} = ?", arrayOf(numeric)),
            Triple(Telephony.Carriers.SIM_APN_URI, null, null),
        )
        var lastError: Exception? = null
        for ((uri, selection, args) in attempts) {
            try {
                val apns = mutableListOf<Triple<String, String, String>>()
                context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        apns += Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty())
                    }
                } ?: continue
                val ims = apns.filter { (_, _, type) ->
                    type.split(',').map { it.trim() }.any { it == "ims" || it == "*" }
                }
                return when {
                    ims.isNotEmpty() -> Check(
                        Level.OK, title,
                        ims.joinToString { (name, apn, type) -> "$name ($apn) [$type]" }
                    )
                    apns.isEmpty() -> Check(Level.WARN, title, "No APNs found for $numeric.")
                    else -> Check(
                        Level.FAIL, title,
                        "Missing. No APN of type 'ims' for $numeric; IMS cannot register. Found: " +
                            apns.joinToString { (name, _, type) -> "$name [$type]" } +
                            ". Add one in Settings → Access Point Names (type: ims)."
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Check(Level.WARN, title, "Cannot read APN list: ${lastError?.javaClass?.simpleName ?: "no result"}")
    }

    @SuppressLint("MissingPermission")
    private fun mobileRegistration(tm: TelephonyManager): Check {
        val title = "Mobile data registration"
        return try {
            val ps = tm.serviceState?.networkRegistrationInfoList?.firstOrNull {
                it.domain == NetworkRegistrationInfo.DOMAIN_PS &&
                    it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
            }
            when {
                ps == null -> Check(Level.WARN, title, "No service state available.")
                !ps.isRegistered -> Check(Level.FAIL, title, "Not registered on the mobile network.")
                else -> {
                    val rat = when (ps.accessNetworkTechnology) {
                        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                        TelephonyManager.NETWORK_TYPE_NR -> "NR (5G SA)"
                        else -> "network type ${ps.accessNetworkTechnology}"
                    }
                    val level = if (rat == "LTE" || rat.startsWith("NR")) Level.OK else Level.WARN
                    Check(level, title, "Registered on $rat.")
                }
            }
        } catch (e: Exception) {
            Check(Level.WARN, title, "Unavailable: ${e.javaClass.simpleName}")
        }
    }

    /**
     * VopsSupportInfo is a hidden API; parse it from ServiceState's toString (best effort).
     * AOSP encodings differ per RAT:
     *  LteVopsSupportInfo: 1 = NOT_AVAILABLE, 2 = SUPPORTED, 3 = NOT_SUPPORTED
     *  NrVopsSupportInfo:  0 = NOT_SUPPORTED, 1 = SUPPORTED (3GPP), 2 = SUPPORTED (non-3GPP)
     */
    @SuppressLint("MissingPermission")
    private fun vops(tm: TelephonyManager): Check {
        val title = "Network VoPS (IMS voice over PS)"
        return try {
            val text = tm.serviceState?.toString().orEmpty()
            val lte = Regex("LteVopsSupportInfo\\s*:\\s*mVopsSupport\\s*=\\s*(\\d+)").find(text)
            val nr = Regex("NrVopsSupportInfo\\s*:\\s*mVopsSupport\\s*=\\s*(\\d+)").find(text)
            when {
                nr != null -> when (nr.groupValues[1]) {
                    "1", "2" -> Check(Level.OK, title, "5G network advertises IMS voice support.")
                    else -> Check(Level.FAIL, title, "5G network does NOT advertise IMS voice support.")
                }
                lte != null -> when (lte.groupValues[1]) {
                    "2" -> Check(Level.OK, title, "LTE network advertises IMS voice support.")
                    "3" -> Check(Level.FAIL, title, "LTE network does NOT advertise IMS voice support.")
                    else -> Check(Level.INFO, title, "Not indicated by the LTE network/modem.")
                }
                else -> Check(Level.INFO, title, "Unknown (not reported by this device/network).")
            }
        } catch (e: Exception) {
            Check(Level.INFO, title, "Unavailable: ${e.javaClass.simpleName}")
        }
    }
}
