# Remote access to a home cluster (research, 2026-10-13)

Question: how should the owner reach their own Ustaad cluster from outside the home, with no hosted model, no open port at home, and as few steps as possible?
All web pages below were read on 2026-10-13 unless a line says "search summary only" (then I saw a snippet, not the page). (unverified) marks claims I could not tie to a primary source. I did not read the app code; anything about "the node server" comes from the brief.

## Short answer

Build a small relay that only forwards ciphertext (option 3), and seal every request with a key derived from the pairing secret. Keep Tailscale (option 1) as the documented power-user path. Embedded overlays, WebRTC and Tor each add a large dependency or a second network without removing the need for a server or a relay.

## Comparison

Effort cells are my estimate, not measured.

| Option | Steps for the owner | New infrastructure and cost per month | Behind CGNAT | Anything publicly reachable | Streams (SSE, minutes) | Android pitfalls | Effort in the Ktor app |
|---|---|---|---|---|---|---|---|
| 1. Overlay VPN app (Tailscale; NetBird, ZeroTier, WireGuard) | Install app and sign in on every phone, same account | None for Tailscale (free: 6 users, unlimited user devices). Plain WireGuard needs a public VPS hub | Yes, through DERP relay when direct fails; relay can be slow | Nothing at home. Vendor servers see who talks to whom, not content | Yes, ordinary TCP inside the tunnel | One VPN slot per phone; battery; OriginOS may kill it (unverified) | Near zero, if the node server listens on all interfaces (unverified) |
| 2. Overlay embedded in the app (tsnet, NetBird embed, Headscale) | Scan pairing code, if the project issues join keys; else sign in to a tailnet | Coordination server: Tailscale SaaS, or Headscale on a VPS (about $2 to $6) | Same as 1 | Nothing at home; coordination server is public | Yes | No VPN permission needed in userspace mode; Go runtime inside the app; no official Android embedding docs | High: Go toolchain, JNI bridge, loopback forward for Ktor client |
| 3. Own relay + end-to-end sealing | None beyond pairing; relay address ships in the app | One small server: VPS or Fly about $2 to $6; Cloudflare Durable Objects free tier possible | Yes, both sides only dial out on port 443 | The relay only. Sees IPs, timing, sizes, ciphertext | Yes, as WebSocket frames; heartbeat needed | No VPN slot used; keep the socket alive in the foreground service | Medium: WebSocket client, framing, sealing, reconnect, about 200-line relay |
| 4. WebRTC data channel (STUN + TURN) | None beyond pairing | Signalling server (still a server) plus TURN: Cloudflare TURN 1000 GB free, then $0.05/GB | Often, but a share of sessions needs TURN | Signalling and TURN servers | Yes, with own framing over SCTP messages | Large native library; ICE restarts on network change | High |
| 5. Tor onion service on the home phone | Scan onion address (and a client key); away phone needs a Tor client | None (Tor network is free) | Yes, outbound only | No IP; the onion address is the secret to protect | Yes, but slow first connect (unverified) | Tor on two phones; Arti onion hosting is young; ISP blocking reports | High: Rust or C Tor binary, JNI |
| 6. iroh (extra, not asked) | None beyond pairing | Public relays free but "not for production"; own relay on a VPS | Yes, hole punching then relay | Relay only | Yes, QUIC streams | Rust dependency; Kotlin bindings, Android not confirmed | Medium to high |

## 1. Overlay VPN the user installs

- Tailscale Personal plan: up to 6 users, unlimited user devices, 50 tagged resources. https://tailscale.com/pricing
- Android setup per device: install from Play, "Get Started", accept the VPN prompt, "Sign in with Google" or another SSO; Android 8.0 or later. https://tailscale.com/docs/install/android
- NAT: every connection starts on DERP and tries to upgrade to direct UDP. Two hard NATs cannot go direct. https://tailscale.com/kb/1257/connection-types
- Cellular: Tailscale's own 2026 post says two phones on different cellular networks "will often need DERP", and direct success is "well north of 90% in typical conditions". https://tailscale.com/blog/nat-traversal-improvements-pt-1
- India: Tailscale says Indian ISPs use CGNAT heavily and names Jio, Airtel, BSNL and ACT. Their iperf3 run over DERP, Delhi to Minnesota, gave 2.20 Mbit/s; a peer relay gave 27.5 Mbit/s. That is a fixed line to the US, not a phone. A DERP region in Bengaluru exists. https://tailscale.com/blog/peer-relays-international-networks , https://tailscale.com/kb/1232/derp-servers
- 2.2 Mbit/s is plenty for streamed tokens and too little for bulk file sync.
- IPv6: Tailscale prefers native IPv6 when both ends have it (same nat-traversal post). I found no Tailscale statement on IPv6-only mobile networks or NAT64 (unverified). Android runs 464XLAT itself, so apps normally see IPv4 (search summary only).
- One VPN at a time: "iOS and Android enforce a limit of running only one VPN at a time." https://tailscale.com/docs/reference/faq/other-vpns . Android: starting a new VPN service stops the old one. https://developer.android.com/develop/connectivity/vpn
- What that breaks: any other VPN or VpnService-based app (work VPN, privacy VPN, some ad blockers) on the away phone, and on every home phone that runs Tailscale.
- Battery: Tailscale calls mobile battery drain "a known issue", mostly from exit-node use (validated 2026-03-20). https://tailscale.com/docs/reference/troubleshooting/mobile/battery-drains . Forum reports range from none to 30 to 40% overnight; anecdotal. https://forum.tailscale.com/t/battery-drain-on-android/2233
- Vendor kill: dontkillmyapp.com rates vivo 3 of 5 and lists "Autostart" and "High background power consumption" as the switches. https://dontkillmyapp.com/vivo . I found nothing on Tailscale under OriginOS (unverified).
- NetBird Free: up to 5 users, 100 machines; Android app also asks for the VPN permission; relay moved to WebSocket from v0.29.0 (helps where UDP is blocked). https://netbird.io/pricing , https://docs.netbird.io/get-started/install/android , https://docs.netbird.io/about-netbird/how-netbird-works
- ZeroTier Free: 10 devices, 1 network (https://www.zerotier.com/pricing/). Its relay behaviour I did not check (unverified).
- Plain WireGuard: "All packets are sent over UDP" and "explicitly does not support tunneling over TCP". A home phone behind CGNAT has no public endpoint, so a public VPS hub would be needed anyway. https://www.wireguard.com/protocol/ , https://www.wireguard.com/known-limitations/
- Attacker who finds an address: the 100.x address is not routable from outside the tailnet. They would need an account on the tailnet. Port 8787 would still demand the HMAC.
- Browser client later: the browser's device would need Tailscale installed. That kills the browser use case.

## 2. The same overlay inside the app

- tsnet is a Go library with its own userspace TCP/IP stack; auth by URL or auth key. https://tailscale.com/kb/1244/tsnet . The page does not mention Android or licence.
- libtailscale (C library) is BSD-3-Clause; its README does not mention Android. https://github.com/tailscale/libtailscale . The official Android app is BSD-3-Clause and built with gomobile. https://github.com/tailscale/tailscale-android
- Possible without the VPN permission: TailSocks runs "in userspace-networking mode via tsnet" without VpnService (community project, licence not stated). https://tailscale.com/community/community-projects/tailsocks . The Dart package `tailscale` 0.9.0 (MIT) embeds upstream tsnet on Android, needs Go 1.26+ and the NDK, and needs Android 12 / API 31+ for persistent nodes. https://pub.dev/packages/tailscale
- A mihomo issue reports tsnet failing on Android with "netlinkrib: permission denied" in TUN mode (search summary only). https://github.com/MetaCubeX/mihomo/issues/2788
- Dependency size: I found no figure (unverified). Measure a minimal tsnet .so for arm64.
- Who runs coordination: Tailscale's SaaS (each owner needs an account, so the step count of option 1 returns), or Headscale.
- Headscale: BSD-3-Clause, "a single tailnet, suitable for personal use". Needs a public IP, HTTPS on 443, udp/3478 for its DERP; latest release v0.29.4 is still 0.x, and v0.29.0 had breaking changes (hostnames, ACL wildcard); it needs Tailscale clients 1.80.0 or newer. https://github.com/juanfont/headscale , https://headscale.net/stable/setup/requirements/ , https://github.com/juanfont/headscale/releases
- Headscale hardware needs: no figure in its docs (unverified). Cost is one small VPS: Fly shared-cpu-1x 256 MB $2.19 per month, no free tier for new accounts (https://docs.fly.io/about/pricing); Hetzner CX23 about EUR 5.49 excluding VAT (search summary only, unverified).
- A project-run Headscale for all owners puts every owner's network map on one server the project must keep up, and Headscale is not built for many tenants (inference).
- NetBird embed: package `embed` runs the client inside a Go program, userspace by default, `SetupKey` auth, `Dial`/`ListenTCP`/`NewHTTPClient`. https://pkg.go.dev/github.com/netbirdio/netbird/client/embed . Client BSD-3-Clause; management, signal and relay are AGPLv3; self-host needs Linux, 1 CPU, 2 GB, ports 80/443/udp 3478 and a domain. https://github.com/netbirdio/netbird . No Android note for `embed` (unverified).
- Userspace WireGuard on Android: the `com.wireguard.android:tunnel` library exists; whether it has a mode without VpnService I did not verify. https://www.wireguard.com/embedding/
- Play policy: VpnService is allowed only where VPN is core or in a listed category, with a declaration and videos. Using it for a private-cluster app risks review. Userspace tsnet avoids this. https://support.google.com/googleplay/android-developer/answer/12564964
- Attacker: nothing listens at home. The coordination server is public; without a key they cannot join.

## 3. A small relay with end-to-end sealing

Design. Home node keeps one WebSocket to the relay on 443. Away phone opens another. The route id is HKDF(secret, "route") so the relay can pair them without knowing the secret. Requests and streamed answers are sealed (section "Application-layer sealing"). The relay forwards bytes.

- What the relay sees: both IPs, the route id, timing and sizes. Not paths, prompts or tokens. Sizes leak token counts; batch frames to blur this (inference).
- Attacker who finds the relay address: can open connections and flood. Cannot read or forge a message without the secret. Cannot claim a route without knowing the 128-bit route id.
- Ktor client WebSockets on the CIO engine: not checked (unverified). Check before committing.
- Cloudflare Workers + Durable Objects, free plan: SQLite-backed objects only; 100,000 requests per day; 13,000 GB-s per day; incoming WebSocket messages count 1/20; outgoing are free; idle hibernating objects are not billed. https://developers.cloudflare.com/durable-objects/platform/pricing/ . An always-awake object uses 128 MB x 86,400 s = 10,800 GB-s per day, under the free 13,000 (my arithmetic).
- Limits: WebSocket received message 32 MiB; CPU 30 s default per request or message; soft 1,000 requests/s per object. https://developers.cloudflare.com/durable-objects/platform/limits/ . Workers: no hard wall-clock limit while the client stays connected; existing connections get up to 20 minutes during runtime updates, so clients must reconnect. https://developers.cloudflare.com/workers/platform/limits/ . Hibernation keeps clients connected while the object is evicted; ping gets an automatic pong. https://developers.cloudflare.com/durable-objects/best-practices/websockets/ . Paid plan: $5 per month minimum. https://developers.cloudflare.com/workers/platform/pricing/
- Terms risk: Cloudflare's self-serve terms (updated 2025-09-12) forbid using the Services "to provide a virtual private network or other similar proxy services" (2.2.1 (j)). Whether that covers a relay on Workers I could not settle; service-specific terms not read (unverified). https://www.cloudflare.com/terms/
- Indian rule: CERT-In's 2022 directions cover VPN providers, data centres and VPS/cloud providers, with log keeping (secondary sources, search summary only). Whether an end-to-end relay counts as a "VPN service" is a legal question for a lawyer (unverified).
- VPS prices: Fly $2.19 (above); Hetzner about EUR 5.49 (unverified); Oracle Always Free Arm VM with 4 OCPU and 24 GB per Oracle docs, but capacity and idle reclaim are reported problems (blogs, unverified). https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm
- Carrier NAT idle timers are often near 4 to 6 minutes; practitioners keep alive at 270 to 290 s (search summary only, secondary). Use an application ping every 25 to 30 s and measure the battery cost on the phones.

Off-the-shelf tunnels. None is end-to-end by default. Most are native binaries (Go/Rust), awkward on Android.

| Tool | Free limits and terms | Can it read traffic | Note |
|---|---|---|---|
| Cloudflare Tunnel | Free; named tunnel needs a domain (unverified) | HTTPS is decrypted at Cloudflare's edge (secondary sources; Tunnel docs do not say, unverified) | Quick Tunnels: no SSE, 200 in-flight requests. Proxy read timeout 125 s unless Enterprise (error 524), so a slow first token breaks the request. https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/ , https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/ |
| ngrok | Free: 1 GB transfer, 20k HTTP requests per month, 3 endpoints, interstitial page; TCP needs a card. https://ngrok.com/pricing | HTTPS endpoints: always terminated at ngrok. TLS/TCP endpoints can pass through. https://ngrok.com/docs/universal-gateway/tls-termination/ | 1 GB per month is thin |
| bore | MIT; public bore.pub | Not encrypted by default; relays plain TCP. https://github.com/ekzhang/bore | Secret only for the handshake |
| frp | Apache-2.0; TLS on by default frpc to frps; STCP preshared key; XTCP hole punching needs frps | Server sees plain traffic unless STCP/own sealing (docs do not say, unverified). https://github.com/fatedier/frp | v2 in progress |
| rathole | Apache-2.0; optional Noise_NK, or TLS | Not by default; Noise or TLS must be chosen. https://github.com/rathole-org/rathole | HTTP API still WIP |
| sish | MIT; ssh -R tunnels | HTTP tunnels: unclear; SNI routing without termination exists. https://github.com/antoniomika/sish | |

Verdict: build a thin relay of our own. The tools above would still need our own sealing, and a native tunnel client on every phone.

## 4. WebRTC data channels

- Signalling is still needed to swap SDP and ICE. That is a server, so it does not remove infrastructure. Authenticate the DTLS fingerprint with the pairing secret or signalling becomes a man-in-the-middle point.
- STUN: stun.cloudflare.com, free. TURN: $0.05/GB after 1,000 GB free; no TCP relaying (no RFC 6062); relay addresses are IPv4 only; credentials last up to 48 hours. https://developers.cloudflare.com/realtime/turn/faq/
- Success rate: OpenVidu (2026-06-09) cites "up to 17.7%" of sessions needing TURN from 2017 data and argues it is higher now because of CGNAT; it advises TURN on 443 with TLS for locked-down networks. https://openvidu.io/blog/2026/06/09/turn-key-considerations/ . I found no measured share for cellular or Indian carriers (unverified). Vendor figures range 10 to 25% (search summary only).
- Libraries: GetStream `stream-webrtc-android` 1.3.9, Apache-2.0, Maven Central (data channel support not stated on its page). https://github.com/GetStream/webrtc-android . Size: no figure found (unverified); a LiveKit-based SDK doc claims about +70 MB universal debug APK, 30 to 40 MB less with ABI splits (vendor, unverified). https://docs.revrag.ai/embed/integration/android-app-size-optimization
- Does it remove the relay? No. TURN is a relay. Fewer sessions use it, but we would run signalling, TURN, and a heavy library to avoid running one WebSocket relay.
- Streams: yes, but SSE needs our own framing over messages.
- Attacker: can reach signalling and TURN; with no TURN credential they cannot relay. They cannot join a session without the fingerprint proof.
- Benefit: browsers speak WebRTC natively; a direct path is faster for bulk data.

## 5. Tor onion service on the home phone

- Arti: the onion-service feature was marked non-experimental in 1.2.0 (2024), yet the Tor Project then said it did not recommend Arti onion services for production (search summary only; Tor Project pages were unreachable on the day). Arti 2.0.0 is dated 2026-02-02 and its headline is relay, directory authority and RPC work (blog listing, search summary only). `tor-hsservice` is at 0.47.0 and "may not be suitable for typical users". https://docs.rs/tor-hsservice/latest/tor_hsservice/
- Android: a Tor Project VPN beta on Arti exists, and a GitLab issue plans Arti in tor-android (search summary only). https://gitlab.torproject.org/tpo/core/onionmasq/-/issues/11 . Community libraries (Tor Onion Proxy Library, a Flutter plugin on Guardian Project binaries) are not Tor Project products.
- Latency: OnionPerf onion circuit latency medians were 185 to 760 ms in September 2026. https://metrics.torproject.org/onionperf-latencies.csv?start=2026-09-01&end=2026-10-12&server=onion . Time to first byte for a new rendezvous: not read (unverified).
- CGNAT and IPv6: Tor clients and services only dial out, so CGNAT does not matter (inference). IPv6 handling on mobile (unverified).
- India (all search summary only): Siti Networks blocked Tor directory authorities in 2018 (https://blog.torproject.org/comment/275848); a 2020 report found SNI blocking on Airtel and Jio for websites in general (https://archive.torproject.org/websites/lists.torproject.org/pipermail/tor-talk/2020-July/045608.html); a forum claims Jio blocks Tor (unverified). Bridges would add owner steps.
- Law and stores: I found no Tor ban in India and no Orbot removal from Play (search summary only, unverified). The CERT-In 2022 rules target VPN providers. Ask a lawyer before shipping.
- Battery: no figure (unverified).
- Attacker who finds the .onion address: can connect to the node and send junk, and can try to load its introduction points; client authorisation would limit this but I did not confirm Arti supports it (unverified).
- Streams: Tor carries long TCP streams (inference); circuits can be rebuilt mid-stream.
- Verdict: privacy is the best of the six; the dependency, the two Tor stacks and the ISP blocking make it the worst fit for "few steps".

## 6. iroh (not asked, but it fits the problem)

- Dial by public key, QUIC with TLS 1.3, hole punching inside QUIC, relay fallback, end-to-end encrypted. Licence Apache-2.0 or MIT. Bindings listed for C, C++, Swift, Python, JavaScript and Kotlin. https://docs.iroh.computer/what-is-iroh , https://github.com/n0-computer/iroh-ffi (Kotlin/JVM on Maven Central; Android not mentioned)
- Public relays are free, rate-limited and for "development and hobby use"; shared relays $19 per month for four (beta); dedicated relays; self-hosting needs a public IP, DNS name and built-in ACME (search summary only). https://docs.iroh.computer/relays/rate-limiting
- It is option 3 plus hole punching, bought as a Rust dependency. 1.0 status not stated (unverified).

## Application-layer sealing (any transport)

Plan, using only `javax.crypto`:
1. Keys. Master K = the 32-byte pairing secret. Write HKDF-SHA256 (RFC 5869) on `Mac("HmacSHA256")`, about 15 lines. Never use K directly. Derive separate keys with distinct `info` strings: "ustaad/v1/route", "ustaad/v1/relay-auth", and per session "ustaad/v1/c2s" and "ustaad/v1/s2c". RFC 5869 uses `info` to stop one key serving two contexts (search summary only).
2. Session. Client sends 32 random bytes `cn`; node replies with 32 random bytes `sn`. Session keys = HKDF(ikm = K, salt = cn || sn, info = label). Fresh keys per session make counter restarts safe, and a recorded session cannot be replayed because `sn` is new.
3. Cipher. AES-256-GCM. Nonce = 4 zero bytes || 64-bit counter, one counter per direction and per key. Do not use random nonces on a long-lived key: NIST SP 800-38D caps random-IV use at 2^32 messages per key (search summary only). AES-GCM and HKDF are both in the browser's WebCrypto list; ChaCha20-Poly1305 is not listed, so AES-GCM keeps the browser client open. https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/deriveKey . ChaCha20-Poly1305 exists on Android from API 28 (search summary only).
4. Streams. Seal each SSE chunk as its own frame (up to 16 KiB). Put frame type, counter and a "final" flag in the AAD. The last frame carries final = true. A close without a final frame is an error; this blocks truncation. The ready-made form is libsodium `crypto_secretstream_xchacha20poly1305` (tags, `TAG_FINAL`, rekey). https://doc.libsodium.org/secret-key_cryptography/secretstream . Tink's Streaming AEAD (AES-GCM-HKDF) binds segments to position. https://developers.google.com/tink/streaming-aead
5. Replay. Receiver accepts only a counter higher than the last one in that session and drops the session on any failure. Expire idle sessions (for example 10 minutes). For one-shot requests with no handshake, keep the current timestamp + request-id cache.
6. Keep the existing HMAC key and the new AEAD keys separate.

Pitfalls.
- Nonce reuse under one key is "catastrophic" (Noise spec section 14, https://noiseprotocol.org/noise.html ; RFC 8439 gives the same warning, search summary only). Typical causes: counter reset after restart with the same key; both directions sharing one key; two code paths each starting at 0. Fix: per-session keys, per-direction keys.
- Create a fresh `Cipher` and IV per frame. The Java docs say to re-initialise GCM with a different IV after each encryption (search summary only).
- Forward secrecy is absent with a static key. The relay records ciphertext; stealing K from one phone later decrypts old sessions. Fix: add an ephemeral ECDH secret into the HKDF input. P-256 ECDH works from API 11; X25519 ("XDH") only from API 33 (search summary of the Android KeyAgreement reference). The iQOO phones run Android 16 but minSdk is 31, so use P-256 or raise the floor.
- Ephemeral ECDH + PSK in HKDF is the NNpsk0 idea (`-> psk, e` then `<- e, ee`). NN alone has no authentication; the PSK supplies it. IKpsk2 is WireGuard's pattern (https://www.wireguard.com/protocol/ , Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s) and needs a static key per device, which our pairing does not have.
- Is a Noise dependency worth it? Not for NNpsk0 alone: the hand-written form is about 40 lines but it is a hand-written handshake, so review it. Take noise-java only if you want audited state machines.

Maintained libraries and licences.
- noise-java (Rhys Weatherley): MIT; its README does not say which patterns or psk modifiers it supports (unverified). https://github.com/rweather/noise-java . Signal fork `org.signal.forks:noise-java` 0.1.1, MIT, listed as "2 years ago" on Maven Central. https://central.sonatype.com/artifact/org.signal.forks/noise-java
- libsodium: ISC, supports Android. https://github.com/jedisct1/libsodium . Binding lazysodium-android 5.2.0 bundles libsodium 1.0.20, MPL-2.0, min SDK 24. https://github.com/terl/lazysodium-android
- Tink Java: Apache-2.0. https://github.com/tink-crypto/tink-java
- Not found: a maintained Kotlin-native Noise library (unverified).

## Recommendation

First choice: option 3. Build `Ustaad Relay`, a small Ktor WebSocket server, plus a relay client in the node and the away client, sealed as above. Host it on a $2 to $6 VPS or Fly machine. The same jar can be self-hosted by an owner who wants it. A Cloudflare Durable Object version is a free-tier alternative once the Workers terms are checked.
Why: (1) the owner does nothing beyond pairing, and both ends only dial out on 443, so CGNAT and IPv6-only carriers do not matter; (2) the relay sees ciphertext, nothing listens at home, and the browser and desktop clients reuse the same WebSocket plus WebCrypto path; (3) it fits the Kotlin stack with no Go, Rust or WebRTC dependency and no VpnService, so no Play VPN declaration and no second VPN slot.
Fallback: option 1 with Tailscale, documented for power users. It needs no code if the node server listens on all interfaces (unverified). It is also the quickest demo path.
Runner-up lost because: per-phone installs and accounts, the one-VPN slot, DERP speed in India, no browser client, and a vendor control plane we cannot fix.
What would change the choice:
- Bulk transfers (file sync) over the away link become a goal: use iroh or Tailscale for the direct path.
- Hosting a relay is judged a legal or terms risk (CERT-In, Cloudflare 2.2.1 (j)): let owners self-host, or lean on Tailscale.
- Phone tests show WebSocket on 443 is throttled by Jio or Airtel: try HTTP/2 streaming or long polling, then reconsider.
- Browser clients need direct paths: add WebRTC later with the relay as signalling.

## Open questions that only a test on the phones can answer

1. Does the home node's WebSocket survive 8 hours of screen-off on the vivo phones inside the foreground service? What ping interval (25 s, 120 s, 270 s) keeps it alive for the least battery?
2. Does a 5-minute SSE answer with a long first-token gap survive through the relay on Jio and Airtel cellular, including a Wi-Fi to cellular switch mid-stream? What does the client do on reconnect?
3. Does each carrier pass WebSocket on 443 to the relay over IPv4 and over IPv6, and does an IPv6-only path with no relay AAAA record work via NAT64?
4. Tailscale on the iQOO phones: does it stay up under OriginOS, is Wi-Fi to Jio cellular direct or DERP (`tailscale ping`), what Mbit/s over DERP from Bengaluru, and what battery cost per hour?
5. Relay round-trip from Bengaluru to the chosen region (median and p95), and CPU cost of sealing 16 KiB frames on the phones.
6. Does an embedded tsnet start next to the llama.cpp child processes, and how many MB does it add to the APK (arm64)?
7. After Wi-Fi to cellular handover, how long until the away client reaches the node again, and does the home node notice a dead relay socket quickly?
