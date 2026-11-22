# CarrierOverrides

## Building with Docker

No local JDK, Gradle or Android SDK required — only Docker:

```sh
./docker-build.sh                  # assembleDebug (default)
./docker-build.sh lintDebug        # any Gradle task(s) can be passed
./docker-build.sh assembleRelease
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The build runs as your host user; the Gradle cache lives in `.docker-cache/` (gitignored).

Clean up everything:

```sh
rm -rf .docker-cache .gradle build app/build
docker rmi carrieroverrides-builder
```

## Carrier Privileges setup

Android grants UICC carrier privileges only to an app whose **signing certificate hash**
(SHA-1, 20 bytes, or SHA-256, 32 bytes) is stored in an access rule in the ARA-M applet
on the SIM card.

### 1. Sign the APK with your ARA-M key

Builds from `./docker-build.sh` are signed with an auto-generated debug key
(`.docker-cache/home/.android/debug.keystore`), which is **not** in the SIM's rules.
Re-sign every build with your own key before installing:

```sh
apksigner sign --ks /path/to/keystore.jks app-debug.apk
apksigner verify --print-certs app-debug.apk   # prints SHA-256 and SHA-1 digests
```

Notes:
- `apksigner` is part of the Android build-tools. It is also available in the Docker build
  image: `/opt/android-sdk/build-tools/34.0.0/apksigner` (use `docker run -it` for the password prompt).
- Android refuses to update an installed app that was signed with a different key
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Always re-sign before `adb install -r`, or
  uninstall the old app first.
- Deleting `.docker-cache/` creates a new debug key, so re-signing is always required.
- The app's main screen shows the SHA-256 of the certificate it is signed with, which you
  can use to check the result.

### 2. Store the access rule on the SIM (pySim)

1. Start pySim-shell:
   `./venv3/bin/python3 ./pySim-shell.py --pcsc-shared --pcsc-regex '.*Reader.*'`
2. Depending on the card, writing to ARA-M may require the ADM key: `verify_adm <ADM-PIN>`.
3. Store the rule. Replace `--device-app-id` with your certificate hash (SHA-1 or SHA-256),
   as plain hex without colons:
   ```text
   select ADF.ARA-M
   aram_store_ref_ar_do --aid FFFFFFFFFFFF --device-app-id E849B63A7B96E571F788FC5845C4AA3C520D18E8 --android-permissions 0000000000000001 --apdu-always
   ```
   - `--aid FFFFFFFFFFFF`: the AID Android uses for carrier-privilege rules
   - `--android-permissions 0000000000000001`: the carrier-privilege bit
4. Check the result: `aram_get_all`

### 3. Make the phone pick it up

- Android reads the rules only when the SIM is loaded: re-insert the SIM, reboot, or
  toggle airplane mode.
- Only **one** app can be the carrier app for a SIM. Uninstall or disable other apps with
  carrier privileges for this SIM.
- Install the app only in the **main user**, not in a work profile or Island
  (`adb install --user 0 …`). Android binds carrier services only in the main user;
  settings made in a profile copy have no effect.
- The app's status line should now show "UICC Carrier Privileges GRANTED", and
  `adb shell dumpsys carrier_config` should show
  `Binding to com.example.carrieroverrides`.
