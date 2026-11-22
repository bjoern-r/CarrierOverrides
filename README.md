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
