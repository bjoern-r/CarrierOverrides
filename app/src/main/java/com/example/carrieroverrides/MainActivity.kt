package com.example.carrieroverrides

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.UserManager
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
        permissions: Array<String>,
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

        // The telephony stack binds CarrierServices only in the system user (user 0). Settings
        // saved from a secondary user / work profile land in that user's storage and are never
        // seen by the service, so block editing there.
        val isSystemUser = getSystemService(UserManager::class.java).isSystemUser
        if (!isSystemUser) {
            contentContainer.addView(TextView(this).apply {
                text = "WARNING: This app is running in a secondary user or work profile. " +
                    "Android only loads carrier config from the main user, so changes made here " +
                    "have no effect. Open the app from the main profile instead."
                textSize = 14f
                setTextColor(Color.RED)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 24, 0, 0)
            })
        }
        val controlsEnabled = hasPrivileges && isSystemUser

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
                isEnabled = controlsEnabled
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
            isEnabled = controlsEnabled
            setOnClickListener {
                CarrierConfigStore.resetAll(this@MainActivity, selectedSubId)
                carrierConfigManager.notifyConfigChangedForSubId(selectedSubId)
                Toast.makeText(this@MainActivity, "Overrides reset to carrier defaults", Toast.LENGTH_SHORT).show()
                renderUi()
            }
        })

        renderDiagnostics(subs.first { it.subscriptionId == selectedSubId })
    }

    private fun renderDiagnostics(sub: SubscriptionInfo) {
        contentContainer.addView(TextView(this).apply {
            text = "VoLTE Diagnostics"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 48, 0, 8)
        })

        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentContainer.addView(section)

        ImsDiagnostics.runChecks(this, sub).forEach { section.addView(checkView(it)) }

        val imsRow = checkView(
            ImsDiagnostics.Check(ImsDiagnostics.Level.INFO, "IMS registration", "Querying…")
        )
        section.addView(imsRow)
        ImsDiagnostics.checkImsRegistration(this, sub.subscriptionId) { result ->
            val index = section.indexOfChild(imsRow)
            if (index >= 0) {
                section.removeViewAt(index)
                section.addView(checkView(result), index)
            }
        }

        contentContainer.addView(Button(this).apply {
            text = "Refresh Diagnostics"
            setOnClickListener { renderUi() }
        })
        contentContainer.addView(TextView(this).apply {
            text = "Config changes reload asynchronously; refresh a few seconds after changing an override."
            textSize = 12f
        })
    }

    private fun checkView(check: ImsDiagnostics.Check) = TextView(this).apply {
        text = "${check.level.symbol}  ${check.title}\n     ${check.detail}"
        textSize = 13f
        setTextColor(check.level.color)
        setPadding(0, 8, 0, 8)
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
