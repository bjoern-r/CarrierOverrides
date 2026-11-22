package com.example.carrieroverrides

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private lateinit var contentContainer: LinearLayout
    private var selectedSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scrollView.addView(contentContainer)
        setContentView(scrollView)

        ensurePermissionsAndRender()
    }

    private fun ensurePermissionsAndRender() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                REQ_PHONE_STATE
            )
            return
        }
        renderUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE_STATE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            renderUi()
        } else {
            contentContainer.removeAllViews()
            contentContainer.addView(TextView(this).apply {
                text = "READ_PHONE_STATE permission is required to inspect SIM subscriptions."
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderUi() {
        contentContainer.removeAllViews()

        val subManager = getSystemService(SubscriptionManager::class.java)
        val carrierConfigManager = getSystemService(CarrierConfigManager::class.java)
        val subs: List<SubscriptionInfo> = subManager.activeSubscriptionInfoList.orEmpty()

        // Header
        contentContainer.addView(TextView(this).apply {
            text = "Carrier Settings Overrides"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        // Display signing cert SHA-256 (helpful when writing ARA-M rules onto a test UICC)
        contentContainer.addView(TextView(this).apply {
            text = "APK Cert SHA-256:\n${getSigningCertSha256()}"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 32)
        })

        if (subs.isEmpty()) {
            contentContainer.addView(TextView(this).apply {
                text = "No active SIM subscriptions detected."
            })
            return
        }

        if (subs.none { it.subscriptionId == selectedSubId }) {
            selectedSubId = subs.first().subscriptionId
        }

        // SIM Selector
        val subLabels = subs.map {
            "SIM ${it.simSlotIndex + 1}: ${it.displayName} (subId=${it.subscriptionId})"
        }
        val simSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                subLabels
            )
            setSelection(subs.indexOfFirst { it.subscriptionId == selectedSubId }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val chosen = subs[pos].subscriptionId
                    if (chosen != selectedSubId) {
                        selectedSubId = chosen
                        renderUi()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        contentContainer.addView(simSpinner)

        // Carrier Privilege Status
        val tm = getSystemService(TelephonyManager::class.java)
            .createForSubscriptionId(selectedSubId)
        val hasPrivileges = tm.hasCarrierPrivileges()

        contentContainer.addView(TextView(this).apply {
            text = if (hasPrivileges) {
                "Status: UICC Carrier Privileges GRANTED"
            } else {
                "Status: NO Carrier Privileges on this SIM (UICC ARA-M / ARF rule missing)"
            }
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 32)
        })

        val settings = listOf(
            "volte" to "VoLTE / Vo4G",
            "vowifi" to "Wi-Fi Calling (VoWiFi)",
            "vonr" to "VoNR / Vo5G",
            "nr_nsa" to "5G NSA",
            "nr_sa" to "5G SA",
            "cross_sim" to "Backup / Cross-SIM Calling"
        )

        val stateLabels = CarrierConfigStore.TriState.entries.map { it.label }

        for ((key, title) in settings) {
            contentContainer.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setPadding(0, 16, 0, 4)
            })

            val current = CarrierConfigStore.getState(this, selectedSubId, key)
            val spinner = Spinner(this).apply {
                isEnabled = hasPrivileges
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    stateLabels
                )
                setSelection(current.ordinal)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val nextState = CarrierConfigStore.TriState.entries[pos]
                        if (nextState != CarrierConfigStore.getState(this@MainActivity, selectedSubId, key)) {
                            CarrierConfigStore.setState(this@MainActivity, selectedSubId, key, nextState)
                            carrierConfigManager.notifyConfigChangedForSubId(selectedSubId)
                            Toast.makeText(
                                this@MainActivity,
                                "Applied $title -> ${nextState.label}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            contentContainer.addView(spinner)
        }

        // Reset Button
        contentContainer.addView(Button(this).apply {
            text = "Reset All Overrides for Selected SIM"
            isEnabled = hasPrivileges
            setOnClickListener {
                CarrierConfigStore.resetAll(this@MainActivity, selectedSubId)
                carrierConfigManager.notifyConfigChangedForSubId(selectedSubId)
                Toast.makeText(this@MainActivity, "Overrides reset to carrier defaults", Toast.LENGTH_SHORT).show()
                renderUi()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun getSigningCertSha256(): String {
        return try {
            val pkgInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val cert = pkgInfo.signingInfo?.apkContentsSigners?.firstOrNull() ?: return "Unavailable"
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    companion object {
        private const val REQ_PHONE_STATE = 1001
    }
}
