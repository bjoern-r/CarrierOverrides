# AGENTS.md - Coding Agent Guidelines for CarrierOverrides

## 1. Project Overview
`CarrierOverrides` is a minimal, zero-external-dependency Android application (Kotlin, minSdk 33, targetSdk 35) designed to configure telephony carrier overrides (VoLTE, VoWiFi, VoNR, 5G NSA/SA, Cross-SIM/Backup calling) on devices where the app holds **UICC Carrier Privileges**.

Because third-party apps cannot invoke `CarrierConfigManager.overrideConfig()`, this project implements a system-bound `android.service.carrier.CarrierService` (`CustomCarrierConfigService`) that returns a `PersistableBundle` on demand and triggers live reloads via `CarrierConfigManager.notifyConfigChangedForSubId(subId)`.

---

## 2. Repository Structure
```text
CarrierOverrides/
├── AGENTS.md                        # Agent instructions and architectural constraints
├── .github/workflows/build.yml      # GitHub Actions CI workflow (JDK 17 + Gradle 8.9)
├── settings.gradle.kts              # Gradle module setup (:app)
├── build.gradle.kts                 # Root Android Gradle Plugin (8.7.2) & Kotlin (2.0.21)
├── gradle.properties                # JVM & AndroidX flags
└── app/
    ├── build.gradle.kts             # App module configuration (compileSdk 35, minSdk 33, JVM 17)
    ├── proguard-rules.pro           # ProGuard rules
    └── src/main/
        ├── AndroidManifest.xml      # Declares READ_PHONE_STATE & BIND_CARRIER_SERVICES
        ├── res/values/strings.xml   # App name string resource
        └── java/com/example/carrieroverrides/
            ├── CarrierConfigStore.kt          # Direct-boot-safe SharedPreferences & bundle builder
            ├── CustomCarrierConfigService.kt  # Bound CarrierService implementation
            └── MainActivity.kt                # Multi-SIM UI, cert SHA-256 display, live triggers
```

---

## 3. Build, Lint & Verification Commands
* **Build Debug APK:** `./gradlew assembleDebug` (or `gradle assembleDebug` if wrapper JAR is absent)
* **Build Release APK:** `./gradlew assembleRelease`
* **Run Android Lint:** `./gradlew lintDebug`
* **Install via ADB:** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
* **Inspect Active CarrierConfig on Device:** `adb shell dumpsys carrier_config`

---

## 4. Critical Architectural Rules & Constraints

1. **Public SDK String Literals for `@SystemApi` Keys:**
   * This project compiles against the standard public Android SDK (`compileSdk = 35`), **not** hidden/system framework stubs.
   * Several AOSP `CarrierConfigManager` keys are `@SystemApi` or `@hide` (e.g., `"vonr_enabled_bool"`, `"vonr_setting_visibility_bool"`, `"carrier_cross_sim_ims_available_bool"`, `"enable_cross_sim_calling_on_opportunistic_data_bool"`).
   * **Rule:** Always pass these keys as raw string literals to `PersistableBundle.putBoolean(...)`. Never import hidden `CarrierConfigManager.KEY_*` constants that break standard Gradle builds.

2. **Direct Boot Storage Requirement (`directBootAware="true"`):**
   * `CustomCarrierConfigService` runs with `android:directBootAware="true"` so Android's `CarrierConfigLoader` can bind to it on boot before the user unlocks the lockscreen.
   * **Rule:** All persistent preferences in `CarrierConfigStore` MUST use `context.createDeviceProtectedStorageContext()`. Never read credential-encrypted (default) `SharedPreferences` inside `onLoadConfig()`, or boot-time SIM initialization will throw an `IllegalStateException`.

3. **Tri-State Override Semantics (`DEFAULT`, `ENABLED`, `DISABLED`):**
   * Every toggle uses `CarrierConfigStore.TriState`.
   * When a setting is `TriState.DEFAULT`, **omit the key completely** from the returned `PersistableBundle` so Android falls back to the platform's baseline carrier configuration (`CarrierConfig2` / GCS). Only insert keys when explicitly `ENABLED` or `DISABLED`.

4. **Service Security & Binding:**
   * `CustomCarrierConfigService` must remain `android:exported="true"` and guarded strictly by `android:permission="android.permission.BIND_CARRIER_SERVICES"`.

5. **Minimal Footprint:**
   * Keep UI code lightweight (native Android Views in `MainActivity.kt` without Jetpack Compose or heavy Material dependencies) so the APK stays small, fast to build, and easy to audit.
