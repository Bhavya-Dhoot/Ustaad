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

The demo build is what judges see. Because `DEBUG` is false it has none of the debug routes (unsigned pairing payload, `/pdu/debug/*`, gallery), it ignores every adb intent extra (`name`, `setupDone`, `autoload`, `gpu`, `activity`, `gallery`; only the `swarmos://pair` deep link is read), `run-as` does not work on it, and a request signed with the dev secret is refused with 401. It is signed with the debug key so it installs over adb, minify is off, and the Kotlin namespace stays `com.swarmos.app`. Until the stored secret has loaded, every build holds a random placeholder secret, never the dev secret.

## Debug build (development)

```bash
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug                      # base id com.swarmos.app
bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDebug -PidSuffix=.shell -PportOffset=60   # side-by-side variant
$ADB -s 192.168.242.76:5555 install -r app/app/build/outputs/apk/debug/app-debug.apk
```

## Demo build: install on a phone, models, pairing

Status: the build, the install and the HTTP checks below were run on phone C. Pushing the two models into the demo package and pairing demo-build phones with a QR scan or a pasted link have NOT been verified on a phone yet.

1. Build (from the repository root; use `-PportOffset=60` only for a side-by-side test, see Ports below):

   ```bash
   bash scripts/gradle-locked.sh "$PWD/app" :app:assembleDemo
   ```

2. Install on each phone (`com.ustaad.app` installs beside `com.swarmos.app`; first install and updates use the same command):

   ```bash
   for P in 192.168.242.89:5555 192.168.242.23:5555 192.168.242.76:5555; do
     $ADB -s $P install -r app/app/build/outputs/apk/demo/app-demo.apk </dev/null
   done
   ```

3. Push the two default models. The demo package has its own, empty models folder (`/sdcard/Android/data/com.ustaad.app/files/models/`); the files on the PC are in `H:\UStaaf\models\`. Chat model `Qwen3.5-4B-Q4_K_M.gguf` is 2,740,937,888 bytes, embedding model `embeddinggemma-300M-Q8_0.gguf` is 333,590,944 bytes:

   ```bash
   for P in 192.168.242.89:5555 192.168.242.23:5555 192.168.242.76:5555; do
     $ADB -s $P shell mkdir -p /sdcard/Android/data/com.ustaad.app/files/models </dev/null
     $ADB -s $P push H:/UStaaf/models/Qwen3.5-4B-Q4_K_M.gguf /sdcard/Android/data/com.ustaad.app/files/models/
     $ADB -s $P push H:/UStaaf/models/embeddinggemma-300M-Q8_0.gguf /sdcard/Android/data/com.ustaad.app/files/models/
     $ADB -s $P shell ls -l /sdcard/Android/data/com.ustaad.app/files/models/ </dev/null   # sizes must match the numbers above
   done
   ```

4. Start the app on the phone (tap Ustaad). Setup appears: grant the three items, or press "Continue" after answering each dialog, or "Skip for now". The Models tab shows both files as present; with "Load models at start" on (Settings), they load when the service starts.

5. Pair the phones (the demo build has no adb path and no shared dev secret; each phone made its own random secret):
   - On the phone that hosts: Swarm tab, "Host a swarm", then "Host over Wi-Fi Direct (5 GHz)" or "Use current Wi-Fi instead". A QR code is shown.
   - On each other phone: Swarm tab, "Join", allow the camera, point it at the QR code. The phone adopts the host's secret and joins.
   - Without a camera: "Join" also has a field "Paste swarmos://pair link or code" and "Pair with pasted code". The Host page on master shows only the QR code, not the link text, so a link has to come from somewhere else (for example a decoded screenshot of the QR); an adb deep link works only on debug builds.
   - Not verified on a phone: the camera scan between two demo-build phones, and the pasted link.

Ports. The demo build uses the same ports as the base debug app (8787 and 8788, 8080 and 8081, 50052), so only one of them can run at a time. Stop the debug app (`$ADB shell am force-stop com.swarmos.app`, or "Stop Ustaad" in its settings) before starting the demo build, or build the demo with `-PportOffset=60` for a side-by-side test.

Models and trust. The models routes (`/load`, `/stop`, `/autoload`, `/gpu`, `/selftest`, `/bench`) can be called by any paired phone; that is accepted for the demo because MemoryCloud needs it.

## Checks used on the demo build (phone C, 2026-10-10)

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

Last full run: master b8b4777, 2026-10-10 15:00, 1406 tests, 0 failures, 0 skipped (counted from `app/app/build/test-results/testDebugUnitTest/*.xml`); debug and demo APKs built in the same run and the debug APK installed on phones B and C at 15:01 (Phone A is reserved and stays on c2ca589 from 13:07).
