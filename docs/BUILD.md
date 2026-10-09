# Build and install

All commands run in Git Bash on the PC. Gradle always goes through the lock wrapper (at most two builds at once on this machine).

```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
```

Phones: A `192.168.242.89:5555`, B `192.168.242.23:5555`, C `192.168.242.76:5555` (`bash scripts/adb-connect.sh` if a transport drops).

## Build types

| Build type | Gradle task | applicationId | Debuggable | `BuildConfig.DEBUG` | Pairing secret |
|---|---|---|---|---|---|
| debug | `:app:assembleDebug` | `com.swarmos.app` (plus `-PidSuffix=.x` for side-by-side variants) | yes | true | fixed dev secret `swarmos-dev-secret` |
| demo | `:app:assembleDemo` | `com.ustaad.app` | no | false | random, created at first start of each install |

The demo build is what judges see. Because `DEBUG` is false it has none of the debug routes (unsigned pairing payload, `/pdu/debug/*`, gallery, adb-only shortcuts), `run-as` does not work on it, and a request signed with the dev secret is refused with 401. It is signed with the debug key so it installs over adb, minify is off, and the Kotlin namespace stays `com.swarmos.app`.

## Debug build (development)

```bash
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug                      # base id com.swarmos.app
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug -PidSuffix=.shell -PportOffset=60   # side-by-side variant
$ADB -s 192.168.242.76:5555 install -r app/app/build/outputs/apk/debug/app-debug.apk
```

## Demo build (what runs at the finale)

```bash
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDemo
$ADB -s 192.168.242.76:5555 install -r app/app/build/outputs/apk/demo/app-demo.apk     # installs as com.ustaad.app, beside com.swarmos.app
$ADB -s 192.168.242.76:5555 shell am start -n com.ustaad.app/com.swarmos.app.MainActivity
```

Things that differ from the debug app on the same phone:

- Ports. The demo build uses the same ports as the base debug app (8787 and 8788, 8080 and 8081, 50052), so only one of them can run at a time. Stop the debug app (`$ADB shell am force-stop com.swarmos.app`, or "Stop Ustaad" in its settings) before starting the demo build, or build the demo with `-PportOffset=60` for a side-by-side test.
- Models. They live in the app's own external files directory, which is per package. Push them again for the demo package: `$ADB -s <phone> push models/<file>.gguf /sdcard/Android/data/com.ustaad.app/files/models/` (create the directory first with `$ADB shell mkdir -p /sdcard/Android/data/com.ustaad.app/files/models` if the app has not run yet).
- Pairing. Each install makes its own random secret, so the three phones must pair with the QR code (or the `swarmos://pair?d=...` deep link); they do not trust each other automatically as the debug builds do.
- Intents. `--ez setupDone true`, `--es name 'Phone A'` and `--ez autoload false` still work on the demo build; `--ez gallery` and the debug routes do not.

## Checks used on the demo build

```bash
# answers without a signature: GET /v1/hello (200 with the node info)
curl -s http://192.168.242.76:8787/v1/hello
# a request signed with the dev secret is refused (401): the demo secret is random
bash scripts/swarm-call.sh 192.168.242.76:8787 GET /link/peers
# the unsigned pairing payload of the debug build is not served (401 without a signature)
curl -s -o /dev/null -w "%{http_code}\n" http://192.168.242.76:8787/v1/link/pairing-payload
```

Replace the port with `8847` when the demo was built with `-PportOffset=60`.

## Unit tests

```bash
bash scripts/gradle-locked.sh "$PWD/app" :app:testDebugUnitTest
```
