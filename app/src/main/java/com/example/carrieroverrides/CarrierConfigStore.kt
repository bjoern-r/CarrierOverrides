package com.example.carrieroverrides

import android.content.Context
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager

object CarrierConfigStore {
    private const val PREFS_NAME = "carrier_overrides_prefs"

    enum class TriState(val code: Int, val label: String) {
        DEFAULT(0, "Default (Carrier)"),
        ENABLED(1, "Force Enabled"),
        DISABLED(2, "Force Disabled");

        companion object {
            fun fromCode(code: Int): TriState = entries.firstOrNull { it.code == code } ?: DEFAULT
        }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setState(context: Context, subId: Int, key: String, state: TriState) {
        val editor = prefs(context).edit()
        val prefKey = "${subId}_$key"
        if (state == TriState.DEFAULT) {
            editor.remove(prefKey)
        } else {
            editor.putInt(prefKey, state.code)
        }
        editor.apply()
    }

    fun getState(context: Context, subId: Int, key: String): TriState {
        val code = prefs(context).getInt("${subId}_$key", TriState.DEFAULT.code)
        return TriState.fromCode(code)
    }

    fun resetAll(context: Context, subId: Int) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith("${subId}_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun buildConfigBundle(context: Context, subId: Int): PersistableBundle {
        val bundle = PersistableBundle()

        // 1. VoLTE / Vo4G
        when (getState(context, subId, "volte")) {
            TriState.ENABLED -> {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
            }
            TriState.DISABLED -> {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false)
            }
            TriState.DEFAULT -> Unit
        }

        // 2. VoWiFi / Wi-Fi Calling
        when (getState(context, subId, "vowifi")) {
            TriState.ENABLED -> {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
            }
            TriState.DISABLED -> {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false)
            }
            TriState.DEFAULT -> Unit
        }

        // 3. VoNR / Vo5G (String literals used to avoid @SystemApi compile issues)
        when (getState(context, subId, "vonr")) {
            TriState.ENABLED -> {
                bundle.putBoolean("vonr_enabled_bool", true)
                bundle.putBoolean("vonr_setting_visibility_bool", true)
            }
            TriState.DISABLED -> {
                bundle.putBoolean("vonr_enabled_bool", false)
                bundle.putBoolean("vonr_setting_visibility_bool", false)
            }
            TriState.DEFAULT -> Unit
        }

        // 4. 5G NR Capabilities (NSA / SA)
        val nsa = getState(context, subId, "nr_nsa")
        val sa = getState(context, subId, "nr_sa")
        if (nsa != TriState.DEFAULT || sa != TriState.DEFAULT) {
            val modes = mutableListOf<Int>()
            if (nsa == TriState.ENABLED) modes.add(CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA)
            if (sa == TriState.ENABLED) modes.add(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)
            bundle.putIntArray(
                CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                modes.toIntArray()
            )
        }

        // 5. Cross-SIM / Backup Calling
        when (getState(context, subId, "cross_sim")) {
            TriState.ENABLED -> {
                bundle.putBoolean("carrier_cross_sim_ims_available_bool", true)
                bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", true)
            }
            TriState.DISABLED -> {
                bundle.putBoolean("carrier_cross_sim_ims_available_bool", false)
                bundle.putBoolean("enable_cross_sim_calling_on_opportunistic_data_bool", false)
            }
            TriState.DEFAULT -> Unit
        }

        return bundle
    }
}
