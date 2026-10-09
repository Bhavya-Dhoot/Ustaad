# SwarmOS build contracts (orchestrator-owned; workers read, never edit)

These contracts let several workers build in parallel without touching the same files. If a contract blocks you, say so in your report; do not change it.

## 1. Decisions that override the PRD (deltas, recorded for gate-1)

1. Inference runtime = llama.cpp's own `llama-server` and `rpc-server` binaries, cross-compiled for arm64-v8a and shipped inside the APK. The app launches them and talks OpenAI-compatible HTTP on 127.0.0.1. No hand-written JNI inference wrapper. Reason: one binary already gives streaming chat, embeddings, JSON-schema constrained output and the RPC layer split.
2. Node-to-node control traffic = HTTP + JSON (Ktor CIO server and client) instead of the custom 4-byte framing in PRD R1.2. Raw TCP is used only for the throughput probe.
3. Request authentication = HMAC-SHA256 with the pairing secret carried in the QR, instead of Ed25519.

## 2. Repository layout

```
app/                       Gradle root (settings.gradle.kts, gradlew)
app/app/                   Android application module :app
app/app/src/main/java/com/swarmos/app/
  MainActivity.kt, SwarmApp.kt, AppGraph.kt      shared, orchestrator-owned after scaffold
  core/      NodeInfo, NodeRegistry, HealthMonitor, AuditLog, SwarmService (foreground)
  link/      SwarmLink: pairing, hotspot, direct, lan, NodeServer, NodeClient, Tput
  llm/       LlamaRuntime (process launcher), ModelStore, LlmClient + LlamaServerClient
  choir/     Agent Choir
  pdu/       Private Data Union
  memcloud/  MemoryCloud + ExpertMesh
  ui/        theme, StatsBar, shared composables
native/                    llama.cpp build script and prebuilt arm64 binaries (native/prebuilt/arm64-v8a/)
third_party/               fetched sources, gitignored
models/                    GGUF files on the PC, gitignored
docs/benchmarks/           measured numbers only
```

Ownership: a worker edits only its own package directory plus files its brief names. Shared files (`MainActivity.kt`, `SwarmApp.kt`, `AppGraph.kt`, `AndroidManifest.xml`, Gradle files) are edited only by the scaffold worker and afterwards by the orchestrator. A module worker that needs a line added to a shared file lists the exact line in its report.

## 3. Module plug-in points (scaffold creates these)

```kotlin
// AppGraph.kt : manual service locator, no DI framework
object AppGraph {
    lateinit var appContext: Context
    val registry: NodeRegistry
    val health: HealthMonitor
    val audit: AuditLog
    var llm: LlmClient            // chat model on this phone
    var embedder: LlmClient       // embedding model on this phone
    val nodeClient: NodeClient    // signed HTTP to peers
    val modules: List<SwarmModule>
}

interface SwarmModule {
    val id: String                // "link", "models", "choir", "pdu", "memcloud"
    val title: String             // tab label
    fun Route.routes()            // Ktor routes mounted under /v1/<id>/ on the node server
    @Composable fun Screen()      // tab content
}
```

Each module package exposes exactly one `object XxxModule : SwarmModule`. `AppGraph.modules` is the only list that names them.

## 4. LLM interface (llm/ package owns the implementation; everyone else codes against it)

```kotlin
data class ChatMessage(val role: String, val content: String)   // "system" | "user" | "assistant"
data class ChatRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 256,
    val temperature: Double = 0.7,
    val jsonSchema: String? = null,      // JSON Schema text; server constrains output to it
    val stop: List<String> = emptyList(),
    val seed: Int? = null,
)
data class ChatChunk(val delta: String, val done: Boolean = false, val stats: ChatStats? = null)
data class ChatStats(val promptTokens: Int, val completionTokens: Int, val promptMs: Double, val genMs: Double) {
    val decodeTokPerSec get() = if (genMs > 0) completionTokens * 1000.0 / genMs else 0.0
}
data class ChatResult(val text: String, val stats: ChatStats)

interface LlmClient {
    val modelName: String
    suspend fun ready(): Boolean
    fun chatStream(req: ChatRequest): Flow<ChatChunk>
    suspend fun chat(req: ChatRequest): ChatResult
    suspend fun embed(texts: List<String>): List<FloatArray>
}
```

The scaffold ships `FakeLlmClient` (deterministic canned output, fake stats flagged `modelName = "FAKE"`) so other modules compile and run before the real runtime lands. Any screen showing output from `FAKE` must label it FAKE.

## 5. Ports

| Port | Bind | Use |
|---|---|---|
| 8787 | all interfaces | Node API (Ktor) |
| 8788 | all interfaces | raw TCP throughput probe |
| 8080 | 127.0.0.1 | llama-server, chat model |
| 8081 | 127.0.0.1 | llama-server, embedding model |
| 50052 | link interface | llama.cpp rpc-server (MemoryCloud only, started on demand) |

## 6. Node API

Base `http://<peer-ip>:8787/v1`. JSON via kotlinx.serialization. Every request carries `X-Swarm-Node` (sender node id), `X-Swarm-Ts` (epoch ms) and `X-Swarm-Sig` = hex HMAC-SHA256(pairingSecret, "$method\n$path\n$ts\n$body"). The server rejects a bad signature or a timestamp more than 30 s off. `GET /v1/hello` is the only unsigned route.

Core routes (link/ owns): `GET /hello` -> NodeInfo, `GET /health` -> NodeHealth, `POST /link/tput` (start probe), `POST /link/bye`.
Module routes live under `/v1/<module-id>/...` and are defined by that module.

```kotlin
@Serializable data class NodeInfo(val nodeId: String, val name: String, val serial: String, val ip: String, val appVersion: String, val chatModel: String?, val embedModel: String?)
@Serializable data class NodeHealth(val nodeId: String, val thermalStatus: Int, val batteryPct: Int, val charging: Boolean, val ramAvailMb: Long, val lastDecodeTokPerSec: Double?, val linkStandard: String?, val linkSpeedMbps: Int?)
```

Node identity: `nodeId` = first 8 hex of SHA-256 of ANDROID_ID; `name` = "Phone A/B/C", user-editable; each node gets a fixed accent colour from its name (A cyan, B amber, C magenta).

## 7. Build and install

- From `H:\UStaaf\app`: `./gradlew :app:assembleDebug`. Optional `-PidSuffix=.x` gives applicationId `com.swarmos.app.x` so two builds install side by side.
- Install: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
- Phones: A = `192.168.242.89:5555`, B = `192.168.242.23:5555`, C = `192.168.242.76:5555` (once authorized). Run `scripts/adb-connect.sh` if a transport drops. In Git Bash set `MSYS_NO_PATHCONV=1` and give `adb shell` commands `</dev/null` inside loops.
- Pre-approved dependencies (scaffold adds them all so module workers do not edit Gradle files): Compose BOM + Material3, navigation-compose, lifecycle-viewmodel-compose, kotlinx-coroutines, kotlinx-serialization-json, Ktor server (cio, content-negotiation, sse) and client (cio, content-negotiation), ZXing core, CameraX + ML Kit barcode-scanning, ML Kit text-recognition, pdfbox-android, androidx.sqlite (bundled driver), datastore-preferences.

## 8. Rules for every worker

- Commit your work on the branch you were given with plain commit messages. Never mention AI, Claude or Anthropic in commits.
- No cloud calls from the app except the model download screen.
- A number shown in the UI or written in docs/benchmarks/ comes from a run on the iQOO phones.
- Phones are shared between workers. Before a timed measurement, check nothing else is hammering the phone (`top -n 1 -m 5`), and say in your report which phone you used and when.
- Do not change phone system settings (developer options, device_config, battery settings) without saying so in the report. Never factory-reset, never uninstall anything that is not `com.swarmos.app*`.

## 9. Addenda (2026-10-10)

1. Variants: `-PidSuffix=.x -PportOffset=N` builds an app that installs beside the base app and listens on 8787+N / 8788+N (constants in `core/Ports.kt`). Worker variants: link `.link` offset 10, choir `.choir` offset 20, pdu `.pdu` offset 30. The llm and memcloud workers use the base id `com.swarmos.app`, offset 0, and are the only ones that install the base id while variants are in flight.
2. Debug builds share the fixed pairing secret `swarmos-dev-secret`, so dev phones trust each other with no QR scan. QR pairing replaces the secret at runtime. Node names are set over adb: `am start -n <pkg>/com.swarmos.app.MainActivity --es name "Phone A"`.
3. SwarmLink primary link = Wi-Fi Direct autonomous group on 5 GHz created by the app (`WifiP2pConfig.Builder().setNetworkName().setPassphrase().setGroupOperatingBand(5 GHz)`), because an app can create and join it without user dialogs and without internet. Same-LAN (any shared Wi-Fi, including a phone hotspot the user turns on) is the zero-setup path and must always work. LocalOnlyHotspot is not used (band not controllable). Pairing payload = JSON {name, nodeId, ips[], port, secret, p2p{ssid, pass}?}; shown as QR and also accepted through the deep link `swarmos://pair?d=<base64url>` so it can be injected over adb.
4. Private Data Union store = plain SQLite via `androidx.sqlite` bundled driver: FTS5 table for BM25 + BLOB column for 256-dim float embeddings, cosine by brute force in Kotlin. sqlite-vec is dropped (no NDK build needed; brute force is milliseconds at demo scale).
5. Models live in the base app's external files dir: `/sdcard/Android/data/com.swarmos.app/files/models/*.gguf` (adb can push there). The PC copy is `H:\UStaaf\models\`.
6. Scoring hooks from the hackathon rules (15 % creative phone use measured from device telemetry): camera is used for QR pairing and for "scan a document into the Data Union"; voice input (Android on-device SpeechRecognizer) on the query boxes is a stretch item.
7. `scripts/swarm-call.sh <ip[:port]> <METHOD> <path> [json-body]` signs and sends a Node API request from the PC with the dev secret (written by the link worker).
8. Design: `docs/design/DESIGN.md` is the binding design system ("Whiteboard"). `ui-kit/` holds the founder's Google Stitch export of all 21 screens (renamed from `ustaad/`); `ui-kit/README.md` maps screens to modules and lists the Stitch inventions that must not be copied. Shared styled composables live in package `com.swarmos.app.ui` (UI-kit worker); feature screens are restyled with them in a later pass.

## 10. Addenda (2026-10-10, founder requirement: throughput, all transports, all phones)

1. SwarmLink is one logical channel per peer over several transports used together: Wi-Fi Direct (bulk and low-latency control), LAN, and Bluetooth (rendezvous without Wi-Fi, credential hand-over, small control messages as fallback). Path choice is by measurement; bulk may be striped over parallel streams and over two Wi-Fi paths when that measurably helps. Every request carries `X-Swarm-Req`, a unique id; servers deduplicate by id so a retry on another path never runs twice. Brief: plans/briefs/link2.md.
2. SwarmOS scheduler (package `com.swarmos.app.sched`): `RemoteLlmClient`, `SwarmLlm` (the whole swarm behind one `LlmClient`, capability- and health-aware, exactly-once), `BatchRunner` (aggregate tokens/s across phones). Variant `.sched`, port offset 50. Brief: plans/briefs/sched.md.
3. Inference uses every resource that measures faster on these phones: CPU build variant chosen by benchmark, parallel server slots when aggregate tokens/s improves, GPU (OpenCL) if it builds and wins. Each choice is recorded with its numbers in docs/benchmarks/.
4. MemoryCloud asks SwarmLink for the peer address on the fastest bulk path (`bestBulkAddress`) for llama.cpp RPC traffic.
5. Naming (founder, 2026-10-10): the product and app are **Ustaad**. User-visible strings (app label, notifications, titles, the pairing screen, exported files) say Ustaad and never SwarmOS. Internal identifiers stay as they are until the orchestrator's final rename step: Kotlin package and namespace `com.swarmos.app`, class names, route paths, the `swarmos://` deep link, `_swarmos._tcp`, script names. The applicationId becomes `com.ustaad.app` in that final step, so adb commands with `com.swarmos.app` remain valid until then.
