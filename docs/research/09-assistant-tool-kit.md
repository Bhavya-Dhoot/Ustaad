# Can the phone's own assistant call Ustaad's tools? (research, 2026-10-13)

Method: pages and AOSP/androidx source files fetched on 2026-10-13. This worker had no shell, so nothing was built or run on a phone or emulator; every "works" below comes from a page or a source file and says which. "Not confirmed" means no page I could read says it. Builds on 08 A1/A2 without repeating them; corrections to 08 are in section 8.

Short answer. Yes in principle: the kit is Android App Functions (`androidx.appfunctions` 1.0.0-alpha13, Android 16+ only). No in practice today: Google runs the Gemini side as a private preview for trusted testers, no source documents a sideloaded-app path or any iQOO/vivo build where an assistant holds the caller permission, and Android 15 cannot host it. This week we can build the functions and prove the whole tool layer through adb, without any assistant.

## 1. Which kits exist (October 2026)

- App Functions. Platform `android.app.appfunctions` (API 36) plus Jetpack `androidx.appfunctions` (alpha). Apps declare self-describing functions that agents "discover and execute"; Google says they let apps "behave like on device MCP servers". Experimental; Gemini integration is a private preview with trusted testers, plus an Early Access form. Confirmed: https://developer.android.com/ai/appfunctions and https://developer.android.com/blog/posts/android-17-is-here (Android 17, released 2026-06-16, "expands the capabilities", the library is "currently in alpha").
- App Actions (shortcuts.xml capabilities, built-in intents). Overview page updated 2026-07-16, no deprecation banner, but registration is "when you upload your app using the Google Play console". Confirmed: https://developer.android.com/develop/devices/assistant/overview . The classic Assistant is being switched off on phones: news reports say from 2026-09-04, with no way back (secondary, Google's own page not read): https://www.businesstoday.in/amp/technology/news/story/google-assistant-to-be-replaced-by-gemini-starting-september-on-android-and-wearos-547570-2026-08-06 . Gemini honouring capabilities: not confirmed; the only evidence is a developer forum thread saying it does not (https://discuss.ai.google.dev/t/how-to-invoke-custom-dynamic-shortcuts-and-app-functions-with-gemini/103215 , no Google reply).
- Computer Control (the "UI automation framework", also called Gemini screen automation). Preview; only an OEM-preloaded assistant holding the privileged `ACCESS_COMPUTER_CONTROL` permission can use it; target apps change nothing. Confirmed, page updated 2026-05-13: https://developer.android.com/ai/computer-control . Not a kit we can build against.
- "Gemini Intelligence" (announced 2026-05-12) is the consumer name for the above, rolling out "starting with the latest Samsung Galaxy and Google Pixel phones". It is not a separate developer kit. "Android MCP" is Google's Android 17 wording for AppFunctions ("the on-device equivalent of the Model Context Protocol"). Remote MCP server is the server-side route and needs a backend. ADK for Android, AG-UI and A2UI are for agents inside our own app. Confirmed: https://android-developers.googleblog.com/2026/05/the-android-show-developers-cut-2026.html , https://developer.android.com/ai/intelligence-system , https://android-developers.googleblog.com/2026/05/17-things-android-developers-google-io.html . No "Android agent SDK" that lets Gemini call our app exists on these pages.
- Which one Google says to use: no page says "use X instead of Y". The Android Show post tells developers to "Choose an integration path: a no-code app automation change, or the AppFunctions API for more control"; the intelligence-system page lists AppFunctions as the on-device tool route and Computer Control as "upcoming". For "the assistant calls our tool", AppFunctions is the only kit in active development.

## 2. App Functions: build facts for 1.0.0-alpha13 (2026-10-07)

Artifacts on Google Maven (https://dl.google.com/android/maven2/androidx/appfunctions/group-index.xml): `appfunctions`, `appfunctions-compiler`, `appfunctions-testing` have alpha01 to alpha13. `appfunctions-service` stops at alpha09 and is gone since alpha10 (https://raw.githubusercontent.com/android/skills/main/device-ai/appfunctions/references/migrate-to-service-entry-point.md). No beta or stable. Release notes confirm the rename: alpha13 "Renamed `AppFunction` annotation to `AppFunctionDeclaration`", added `@AppFunctionAccessLevel`, renamed `ObserveAppFunctionsEvent` to `AppFunctionsChangeEvent` (https://developer.android.com/jetpack/androidx/releases/appfunctions). Google's guide and skill pages still show `@AppFunction` (alpha10 text), so they lag the library. The source API file shows `AppFunctionDeclaration(boolean isDescribedByKDoc, optional boolean isEnabled)`, all in package `androidx.appfunctions` (androidx-main, may be newer than the tag): https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/appfunctions/appfunctions/api/current.txt

SDK levels. The library is built with compileSdk 37 (androidx-main build.gradle, `compileSdk { version = release(37) }`). Google's skill says targetSdk 36+ and compileSdk 37+ (https://raw.githubusercontent.com/android/skills/main/device-ai/appfunctions/SKILL.md); Google's own sample uses compileSdk 37, minSdk 24, targetSdk 37 (https://raw.githubusercontent.com/android/appfunctions/main/ChatApp/app/build.gradle.kts); the older guide page says compileSdk 36+. Treat 37 as required for alpha13; the AAR metadata itself was not readable, `./gradlew :app:checkDebugAarMetadata` settles it. No minSdk is stated for the library (our 26 is above the sample's 24). `AppFunctionManager.getInstance` carries no `@RequiresApi` and returns null when unsupported; `AppFunctionService` is `@RequiresApi(BAKLAVA)` = 36. Android 17 setup uses `compileSdk = 37` (https://developer.android.com/about/versions/17/setup-sdk); the sdkmanager package id for platform 37 is not confirmed (`sdkmanager --list`); AGP 9.4.1 accepting compileSdk 37 without a warning is not confirmed.

KSP. Latest KSP is 2.3.12 (2026-09-09, minimum AGP 8.12.0); 2.3.10 is the release that "Fix R-class resolution in KSP when AGP 9 built-in Kotlin is enabled" and fixed Kotlin 2.4.0 module names (https://github.com/google/ksp/releases). Kotlin's quickstart pairs Kotlin 2.4.21 with KSP 2.3.10 (https://kotlinlang.org/docs/ksp-quickstart.html); Google's sample runs AGP 9.3.1 + Kotlin 2.4.10 + KSP 2.3.10 with appfunctions alpha10 (https://raw.githubusercontent.com/android/appfunctions/main/ChatApp/gradle/libs.versions.toml). Use 2.3.10 first. Our app already uses AGP 9 built-in Kotlin (no kotlin-android plugin), so do not add one. The compiler POM depends on `symbol-processing-api:2.0.10-1.0.24` (https://dl.google.com/android/maven2/androidx/appfunctions/appfunctions-compiler/1.0.0-alpha13/appfunctions-compiler-1.0.0-alpha13.pom); it works under KSP 2.3.10 with alpha10 in Google's sample; alpha13 under 2.3.10: not run.

```toml
# app/gradle/libs.versions.toml
[versions]
ksp = "2.3.10"
appfunctions = "1.0.0-alpha13"
[libraries]
androidx-appfunctions = { module = "androidx.appfunctions:appfunctions", version.ref = "appfunctions" }
androidx-appfunctions-compiler = { module = "androidx.appfunctions:appfunctions-compiler", version.ref = "appfunctions" }
androidx-appfunctions-testing = { module = "androidx.appfunctions:appfunctions-testing", version.ref = "appfunctions" }
[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
```kotlin
// app/build.gradle.kts (root build.gradle.kts also needs: alias(libs.plugins.ksp) apply false)
plugins { alias(libs.plugins.ksp) }
android { compileSdk = 37 }          // was 36; minSdk 26 and targetSdk 36 stay
ksp { arg("appfunctions:aggregateAppFunctions", "true") }   // required per the skill's implementation reference
dependencies {
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    testImplementation(libs.androidx.appfunctions.testing)
}
```

Manifest (confirmed from Google's sample manifest, https://raw.githubusercontent.com/android/appfunctions/main/ChatApp/app/src/main/AndroidManifest.xml, and the guide https://developer.android.com/ai/appfunctions/add-appfunctions ). The `android:name` is the class KSP generates; `v2` value is `<appFunctionXmlFileName>.xml` (the annotation value must not contain ".xml").
```xml
<service android:name="com.swarmos.app.appfunctions.UstaadAppFunctionService"
    android:permission="android.permission.BIND_APP_FUNCTION_SERVICE" android:exported="true" tools:targetApi="36">
    <property android:name="android.app.appfunctions.schema" android:value="app_functions_schema.xsd" />
    <property android:name="android.app.appfunctions.v2" android:value="ustaad_app_function_service.xml" />
    <intent-filter><action android:name="android.app.appfunctions.AppFunctionService" /></intent-filter>
</service>
<!-- inside <application>, not inside <service> -->
<property android:name="android.app.appfunctions.app_metadata" android:resource="@xml/app_metadata" />
```
```xml
<!-- res/xml/app_metadata.xml (root element and namespace from Google's sample) -->
<AppFunctionAppMetadata xmlns:appfn="http://schemas.android.com/apk/androidx.appfunctions"
    appfn:description="Runs tasks on the user's own phones: start, ask an expert, search their data, status, stop."
    appfn:displayDescription="@string/app_function_app_display_description"/>
```

Minimal function (alpha13 names; written from the alpha10 example plus the rename; not compiled). Function IDs are `<enclosing class>#<method>`.
```kotlin
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "UstaadAppFunctionService", appFunctionXmlFileName = "ustaad_app_function_service")
abstract class BaseUstaadAppFunctionService : AppFunctionService() {
    /**
     * Starts a Ustaad task from a plain-language request and returns its task id.
     *
     * @param task What the user wants done, in their own words.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)   // add isEnabled = false to ship it off by default
    suspend fun startTask(task: String): String = withContext(Dispatchers.IO) {
        if (task.isBlank()) throw AppFunctionInvalidArgumentException("task is empty")
        HandsTools.start(applicationContext, task)       // ours: the existing registry call, returns the task id
    }
}
```
The generated XML is written to `assets/<appFunctionXmlFileName>.xml` (and an `app_functions_v2.xml` is reported in the APK, https://dev.to/tkuenneth/agentic-interaction-using-appfunctions-m8k ); no Google page shows its contents: not confirmed. Without Hilt, reach singletons through `applicationContext` (skill migration page).

Enable/disable. Declared default via `isEnabled`; at runtime `AppFunctionManager.getInstance(ctx)?.setAppFunctionEnabled(BaseUstaadAppFunctionServiceIds.START_TASK_ID, AppFunctionManager.APP_FUNCTION_STATE_ENABLED | _DISABLED | _DEFAULT)`; it only acts on the caller's own functions and can throw until indexing finishes, so wrap in try/catch (guide above; platform source: no permission annotation on it). Shell: `set-enabled ... --state enable|disable|default`.

Types (skill implementation reference): Int, Long, Float, Double, Boolean, their arrays, String, PendingIntent, Uri, LocalTime, LocalDate, LocalDateTime, Instant (alpha13 also ZoneId in `AppFunctionData`), classes marked `@AppFunctionSerializable`, and List of non-primitive supported types. Documentation for a serializable class must be inline KDoc on each property, not class-level `@param`. Whether a bare `Unit` return is allowed: not confirmed; return a String.
Errors: throw a subclass of `AppFunctionException`: `AppFunctionInvalidArgumentException`, `ElementNotFound`, `ElementAlreadyExists`, `FunctionNotFound`, `Disabled`, `Denied`, `LimitExceeded`, `Cancelled`, `NotSupported`, `PermissionRequired`, `AppUnknown`, `SystemUnknown` (api/current.txt above). Only the first two appear in Google's guide.
Process and time. The OS binds the app's service with `BIND_AUTO_CREATE` (plus `BIND_FOREGROUND_SERVICE` when the caller holds the permission), so the process is started on demand (android17-release `AppFunctionManagerServiceImpl.java`, https://android.googlesource.com/platform/frameworks/base/+/android17-release/services/appfunctions/java/com/android/server/appfunctions/AppFunctionManagerServiceImpl.java ). A function "runs on the Android UI thread" by default, so make it `suspend` and switch dispatcher (guide). Time: the platform sets no execution deadline I could find; the only platform constant is a 5000 ms grace after cancellation (`ServiceConfigImpl`, key `execute_app_function_cancellation_timeout_millis`), the shell waits 30 s by default (`--timeout-duration`). Callers set their own limits: Gemini's: not confirmed. So `startTask` must return a task id at once and let `taskStatus` poll.

## 3. Who can call, and is a sideloaded app visible

Rule on the page: callers need `EXECUTE_APP_FUNCTIONS` or `EXECUTE_APP_FUNCTIONS_TRUSTED` for other apps' functions; an app always runs its own (search snippet of https://developer.android.com/reference/android/app/appfunctions/AppFunctionManager , page too large to read whole). Protection levels, read from AOSP manifests (mirror of frameworks/base, `core/res/AndroidManifest.xml`):
- android16-release: `EXECUTE_APP_FUNCTIONS` = `internal|privileged`; no `_TRUSTED`.
- android16-qpr1-release: two declarations chosen by flag `android.permission.flags.app_function_access_api_enabled`. Off: `internal|privileged`. On: `normal`, with the comment "the application must be on a device allowlist, and its ability to execute functions is subject to user approval or disapproval"; plus `MANAGE_APP_FUNCTION_ACCESS` (`signature|installer`).
- mirror main (undated, newer): both `EXECUTE_APP_FUNCTIONS` and `EXECUTE_APP_FUNCTIONS_TRUSTED` are `internal|role`; the first "is currently only granted to preinstalled / system apps having the android.app.role.ASSISTANT role", the second "only granted to system packages".
- Android 17 (android17-release): the manifest page was too large to read. The caller check reads `EXECUTE_APP_FUNCTIONS_SYSTEM` (skips the allowlist) or `EXECUTE_APP_FUNCTIONS` plus a remote allowlist keyed on agent package + SHA-256 of its signing certificate, valued by target package names or `*`, targets matched by package name only (`CallerValidatorImpl.java`, `SystemAppFunctionAllowlistReader.java` under the same android17-release path). Whether Google's list holds `*` for Gemini: not confirmed.
All raw URLs: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/res/AndroidManifest.xml (also `android16-qpr1-release`, `main`).

Who calls today. Gemini on the Galaxy S26 series, "expand[ing] to Samsung devices running OneUI 8.5 and higher", with Samsung Gallery as the example (https://android-developers.googleblog.com/2026/02/the-intelligent-os-making-ai-agents.html); "private preview with apps like KakaoTalk" and "local execution of 25 apps' use cases across device manufacturers" (Android Show post, section 1). Pixel for AppFunctions (not the screen-automation feature): not confirmed. Third-party Gemini access is gated: "only a limited number of apps and system agents can access the entire pipeline" (https://developer.android.com/ai/appfunctions ); the EAP form is https://forms.gle/GN5ybjQFhzHRCguM7 and "registering ... does NOT automatically obtain access". A real third-party attempt: Galaxy A34, Android 16, six functions indexed, Gemini logged "Unable to resolve AppFunctionMetadata", issue closed "not planned" (https://github.com/google-gemini/cookbook/issues/1151). Play or enrolment requirement: not stated on any Google page.

Sideloaded and iQOO, plainly. Nothing I could read says the OS or Gemini refuses a sideloaded app; the Android 17 allowlist matches targets by package name, not installer. But the Gemini end is closed to us, and I found no evidence of any iQOO/vivo build where Gemini or a vivo agent holds the permission. So: not possible today to have Gemini on an iQOO phone call Ustaad; not confirmed that it is blocked in principle. Android 17 Beta 3 exists for iQOO 15 (secondary: https://walastech.com/news/vivo-x300-pro-iqoo-15-android-17-beta/ ); Gemini behaviour there: not confirmed.

## 4. Testing without Gemini

Confirmed text (https://raw.githubusercontent.com/android/skills/main/device-ai/appfunctions/references/adb-interaction-testing.md ; guide page):
```
adb shell cmd app_function help
adb shell cmd app_function list-app-functions            # JSON; android17 source adds --package P and --user N
adb shell "cmd app_function execute-app-function --package P --function 'P.Class#fn' --parameters '{\"task\":\"hello\"}'"
adb shell cmd app_function set-enabled --package P --function 'P.Class#fn' --state enable|disable|default
```
Parameters are keyed by Kotlin parameter name; a serializable argument is a nested object (guide: `{"createTaskParams": {"title": "Buy milk"}}`). Add `--brief-yaml` for short output. "`cmd: Can't find service: app_function`: the device doesn't support this feature." Android 17 source also has `is-enabled`, `--timeout-duration`, and, only behind flags, `grant-app-function-access`, `list-valid-agents`, `set-additional-allowlisted-agents` (`AppFunctionManagerServiceShellCommand.java`, android17-release).

Shell as caller. The shell command accepts only shell or root ("Only allow shell or root") and has no userdebug check in the android17 source; the Shell package requests `EXECUTE_APP_FUNCTIONS` in android16-release, android16-qpr1-release and main (https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/packages/Shell/AndroidManifest.xml , comment "required for CTS test"). So by source the shell can run another app's function on a user build too; not run. Whether the Android 17 allowlist exempts shell: not confirmed.

Emulator. Our images: `system-images/android-36/google_apis` and `google_apis_playstore`, x86_64, revision 7, build `BE2A.250530.026.F3` userdebug (read from build.prop). The `cmd app_function` command class is absent from the android16-release/mirror listings and present in android16-qpr2-release (https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/appfunctions/java/com/android/server/appfunctions/ ). One author saw "No shell command implementation" on some API 36 emulator images and fixed it by moving the AVD to API 36.1 (dev.to link above). Inference, not run: our image will answer "No shell command implementation". We have platform 36.1 but no 36.1 system image; install one (or an Android 17 image) with `sdkmanager --list`. The service itself is published when `AppFunctionManagerConfiguration.isSupported` (turned off on Wear, omnirom commit "Disable app_function service on Wear"); Google's sample README says "a device or emulator running Android 16 (API 36) or higher".

Smallest agent. FilipFan/AppFunctionsPilot (Apache-2.0): an Agent app and a Tool app. Agent install `adb install agent-debug.apk`, then `./start_agent.sh`, which runs `adb shell am instrument -w <pkg>/.ShellIdentityInstrumentation`; that class calls `uiAutomation.adoptShellPermissionIdentity("android.permission.EXECUTE_APP_FUNCTIONS", ...)` and starts MainActivity. No root; opening from the launcher icon gets no permission. Needs flag `enable_app_functions_schema_parser` (`adb shell aflags list | grep enable_app_functions_schema_parser`). Root alternative: push to `/system/priv-app` with `ro.control_privapp_permissions` set to `log`. On Android 17 it adds `adb shell cmd allowlist add-package-multimap 2 <pkg>:<cert sha256> '*'`. Source: https://github.com/FilipFan/AppFunctionsPilot . Google's own tester is built from source (`agent/run_privileged.sh`, same shell-identity idea, "retail" flavour needs a Gemini API key; the prebuilt release is marked deprecated): https://github.com/android/appfunctions . For a demo we can write our own ~60-line agent activity (`AppFunctionManager.getInstance`, `searchAppFunctions`, `executeAppFunction`) and launch it with the same instrumentation; not written.

## 5. Android 15 and older

No backport of the platform API: "AppFunctions is available on devices running Android 16 or higher". There is a sidecar: `com.android.extensions.appfunctions`, a `system_ext` shared library declared by `appfunctions.extension.xml` (`file="/system_ext/framework/com.android.extensions.appfunctions.jar"`) in AOSP `libs/appfunctions` on android16-release; the same directory is 404 on android15-release. androidx wraps it as `ExtensionsAppFunctionService`, "for UPSIDE_DOWN_CAKE through BAKLAVA (inclusive), if the AppFunctions extensions library is available on the device", and `AppFunctionManager.getInstance` returns null below 34 or when `Class.forName("com.android.extensions.appfunctions.AppFunctionManager")` fails (sources: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/appfunctions/appfunctions/src/main/java/androidx/appfunctions/ExtensionsAppFunctionService.kt and `AppFunctionManager.kt` beside it). Before API 37 only one service per `AppFunctionService` action may be declared, so a split build must enable one by SDK level. The library is OEM-shipped; the Samsung A34 issue above reports it present on Android 16. The Redmi (AOSP-based Android 15 ROM): almost certainly absent; check `adb shell pm list libraries | grep appfunctions`. Even present, it does not give Gemini a caller.
Best path on the Redmi: no Google kit lets Gemini or Assistant call a sideloaded app's tool on Android 15. Options in order: Ustaad as the device assistant (08 section B, the only route that already works for a sideloaded app); App Actions through a Play Console draft (section 6) for en-US only; in-app voice entry.

## 6. App Actions for "Hey Google, ... [text]"

- Intent choice. `actions.intent.GET_THING` takes free text in `thing.name` ("Find waterfall hikes on ExampleApp"); user invocation locales: en-US, pt-BR only (https://developer.android.com/reference/app-actions/built-in-intents/common/get-thing , updated 2024-09-04). `OPEN_APP_FEATURE` needs inline inventory, so it is not free text, but covers en-IN (https://developer.android.com/reference/app-actions/built-in-intents/common/open-app-feature). Custom intents are still documented (page updated 2026-02-26, no deprecation): name prefix `custom.actions.intent`, up to 100 query patterns with `$text1`, at most two text parameters, en-US only and the device and Assistant languages must match (https://developer.android.com/develop/devices/assistant/custom-intents). An iQOO phone set to English (India) will not trigger GET_THING; Assistant would have to be en-US.
- Exact shortcuts.xml (pattern from the page's deep-link sample `myexerciseapp://start{?exercise_name}`, https://developer.android.com/develop/devices/assistant/get-started ; adapted, not run). Put `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts"/>` on the launcher activity (https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts); the target activity needs `android:exported="true"` and a VIEW/BROWSABLE/DEFAULT filter for `ustaad://do`; `androidx.core:core` 1.6.0+ is required.
```xml
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
  <capability android:name="actions.intent.GET_THING">
    <intent android:action="android.intent.action.VIEW">
      <url-template android:value="ustaad://do{?q}" />
      <parameter android:name="thing.name" android:key="q" android:mimeType="text/*" />
    </intent>
  </capability>
</shortcuts>
```
  Check the deep link first: `adb shell am start -a android.intent.action.VIEW -d "ustaad://do?q=hello"` (page quotes this form).
- Play Console. The page lists as requirements a Google account with Play Console access and an app "published to the Play Store"; steps: accept the App Actions terms (Advanced settings > App Actions), upload a bundle containing shortcuts.xml, then Google reviews. For testing, the test-tool page says "You can use a draft version of the app for testing" and wants Play Console access to the uploaded package plus the same Google account in Studio, in the Google app on the phone and in Play Console; the get-started page says an internal or closed test release plus the "App Actions Development Program" Google group (up to three hours) lets testers see unapproved actions. So a draft is enough for the preview; real users need review. Package id must match the upload, so com.swarmos.app and com.ustaad.app need separate drafts.
- Test tool 2026. Install "Google Assistant plugin" (JetBrains plugin 16739) in Android Studio, Tools > Google Assistant > App Actions test tool > Create Preview; use a build installed from Android Studio; en-US only; "Use voice triggering only for demonstration" (https://developer.android.com/develop/devices/assistant/test-tool , updated 2026-02-26). Compatibility of the plugin with the current Studio: not confirmed. Emulator use: the get-started page says "physical or virtual device".
- Gemini as the phone's assistant: not confirmed to honour capabilities; the retirement of classic Assistant on phones (section 1) makes an App Actions-only plan a plan on a feature being removed.

## 7. Recommendation

1. Build one `@RequiresApi(36)` entry-point service with the five Hands tools (startTask, askExpert, searchMyData, taskStatus, stopTask), String in and out, each a thin call into the existing registry; startTask and stopTask must keep Ustaad's own confirmation rules (Google: destructive actions need the app's own confirmation).
2. Bump compileSdk to 37, add KSP 2.3.10 and alpha13 as in section 2; keep minSdk 26; keep the service class out of any code path below API 36.
3. Verify this week: build and `checkDebugAarMetadata`; install a 36.1 or Android 17 emulator image; `list-app-functions` and `execute-app-function` against our package; then the Pilot instrumentation agent; then the same probes on an iQOO phone (below) when it is connected.
4. Probe each phone: `adb shell cmd app_function help`; `adb shell pm list permissions -f | grep -A 5 EXECUTE_APP_FUNCTIONS`; `adb shell aflags list | grep -i app_function`; `adb shell pm list libraries | grep -i appfunctions`; `adb shell getprop ro.build.version.sdk_full`.
5. Do not build App Actions unless we decide to enrol in Play; keep the assistant-role route (08 B) as the working voice path on the Redmi and the iQOO phones.
6. Depends on Google: Gemini EAP admission and the Gemini-side allowlist for our package. Depends on vivo: whether OriginOS 6 ships the service, the shell command and the flags, and whether any vivo agent holds the permission (not confirmed).

## 8. Corrections to 08

- 08 A2 "caller permission is privileged on most Android 16 builds": true for 16.0, but QPR1 can make it `normal` behind an allowlist and a user-approval flag, and newer source makes it `internal|role` (assistant role, preinstalled or system app). Section 3.
- 08 A2 compileSdk 37: confirmed by the library build, Google's sample and skill; the guide page still says 36. `appfunctions-service` is dead since alpha10. Section 2.
- 08 A2 "shell test command": it exists only from android16-qpr2 (AOSP listing) and Android 17; our local emulator image predates it. The shell holds the permission by manifest. Section 4.
- New since 08: Computer Control page, the sidecar `com.android.extensions.appfunctions`, classic Assistant retirement reports, Android 17 agent allowlist.
- Not confirmed anywhere above: Gemini honouring App Actions, Gemini calling any sideloaded app, any iQOO/vivo agent path, AppFunctions on Pixel, the generated XML's contents, the Android 17 manifest protection levels.
