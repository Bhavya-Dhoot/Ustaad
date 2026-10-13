# Closed cloud wire protocol (version 3)

Status: written 2026-10-13 for package P2 of plans/2026-10-13-closed-cloud.md. Code of this stage: `app/app/src/main/java/com/swarmos/app/cloud/` (no Android imports, tests in `app/app/src/test/java/com/swarmos/app/cloud/`). Nothing in link/ or core/ uses it yet. Nothing here has run on a phone; every statement about the Android keystore or Conscrypt is a statement of intent, not of measurement.

Version: the closed cloud is protocol 3. `core/NodeInfo.PROTOCOL_VERSION` (2) is the old shared-secret node protocol and stays until the wiring stage.

Words: a **member** is a key listed in the member list. A **node** is a member of kind `node`. The **admitter** is the node that shows an invite. The **newcomer** is the device that scans it.

## 1. Conventions

### 1.1 Byte encoding (everything that is signed, MACed or hashed)

Big-endian integers: `u8`, `u16`, `u32`, `u64`. `bytesN` is N raw bytes. `lp(x)` is `u32 length || x`. `str` is `lp` of UTF-8 (strictly decoded; no byte below 0x20, no 0x7f). Every structure that is signed, MACed or hashed starts with `lp(label)` (an ASCII label in section 1.3), so no signature, MAC or hash made for one purpose can be accepted for another. A parser rejects a length that runs past the input, a trailing byte, an unknown type or version, and a field outside its limit (all answered `malformed`). Nothing is ever signed as JSON text.

Public key `pub`: 65 bytes `0x04 || X || Y`, a P-256 point, checked to lie on the curve before use (also for ephemeral keys; the point at infinity is not representable). Signature `sig`: 64 bytes `r || s` (the WebCrypto form; the code converts to and from the DER form of `java.security.Signature`). Hash `H` is SHA-256. `fp(pub) = H(lp("ustaad/v1/key") || pub)`, 32 bytes, written in hex (64 characters) in text. The 8-hex node id is unchanged; a node id is always exactly 8 characters of `[0-9a-f]`. Display names are 1 to 64 bytes.

### 1.2 Primitives

- ECDSA P-256 with SHA-256 for identity signatures. ECDH P-256 (ephemeral keys, shared secret = the 32-byte X coordinate). The identity key never does ECDH and the ephemeral key never signs.
- HKDF-SHA256 (RFC 5869), written on `Mac("HmacSHA256")`; an empty salt means 32 zero bytes, as the RFC says. `hkdf(ikm, salt, info, L)`.
- HMAC-SHA256 for request MACs and tags. AES-256-GCM, 128-bit tag, for sealing.
- All secret comparisons use `MessageDigest.isEqual` (constant time). No key, invite code or MAC is ever logged or put in an exception message.

### 1.3 Labels (domain separation)

`ustaad/v1/` followed by: `entry`, `entry/sig`, `cloud`, `key`, `token`, `loghash`, `join/c2a`, `join/a2c`, `join/pop`, `session`, `session/sig/i`, `session/sig/r`, `session/mac`, `session/seal/i2r`, `session/seal/r2i`, `session/id`, `req`, `frame`, `frame/stream`, `tag`, `relay/route`, `relay/open`.

## 2. Identity

Each device makes one EC P-256 key pair on first start. The private key signs and does nothing else; it never leaves the device. The code defines `DeviceIdentity { publicKey: ByteArray (pub); sign(message): ByteArray (sig) }`. `SoftwareIdentity` is the implementation for tests and for a desktop node (it can export and restore its PKCS#8 form; the file is the owner's to protect). The Android implementation (next stage) is a keystore key with purpose SIGN and digest SHA-256; the code never asks it for anything but `publicKey` and `sign`.

A reinstalled app has a new key, so it is a new member; the old key stays removed or stays a member until someone removes it. The node id may repeat across the two keys (section 3.6).

## 3. Member list

### 3.1 Entries

A cloud's list is a set of signed entries. An entry on the wire is `lp(body) || sig`. Its **id** is `H(body)` (32 bytes). The signature covers `lp("ustaad/v1/entry/sig") || body` and is made by the key named `signer` in the body. Two entries with the same body are the same entry, whatever their signatures (ECDSA signatures are malleable; ids and the log hash ignore the signature).

Body, after `lp("ustaad/v1/entry") || u8 type`:

| type | name | fields |
|---|---|---|
| 1 | genesis | `bytes16 seed`, `u64 time`, `str nodeId`, `str name`, `pub` |
| 2 | add | `bytes16 cloudId`, `bytes32 signer`, `u64 time`, `deps`, `str nodeId`, `str name`, `u8 kind`, `u8 keyType`, then for keyType 1 `pub`, for keyType 2 `bytes32 issuer`, `bytes16 keyId` |
| 3 | remove | `bytes16 cloudId`, `bytes32 signer`, `u64 time`, `deps`, `bytes32 target` |

`deps` is `u8 n` then n entry ids in ascending byte order without duplicates, n at most 32. `kind`: 1 node, 2 client, 3 browser. `keyType`: 1 device key, 2 token key (section 8). Allowed: node with device key; client with device key or token key; browser with token key. For a token key `issuer` must equal `signer` (the issuing node signs its own client's entry; the field stays so that the Devices list can show it without a lookup). `time` is the signer's clock in epoch ms; it is shown to people and **never used for validity**.

The genesis entry is the creator adding itself. Its signer is the key inside it. The **cloud id** is `H(lp("ustaad/v1/cloud") || genesis body)` cut to 16 bytes. So the id is random (the seed is 16 random bytes) and nobody can make a second log under the same id without breaking SHA-256: there is exactly one root.

The **member id** of an entry is `fp(pub)` for a device key, and `H(lp("ustaad/v1/token") || issuer || keyId)` for a token key. A remove names a member id (a key), not a node id.

### 3.2 Why entries carry `deps` (a sharpening of the brief)

"The signer must itself have been valid when it signed" cannot be decided from a set of entries if the only ordering is the signer's own `time`: a removed member could write any time it likes. So every entry lists the entries its author had in its log when it signed (the **heads**: entries no other stored entry cites). The graph of these citations is acyclic because an id is a hash of a body that contains the ids it cites. `anc(e)` is the set of entries reachable from e through `deps`. "e was before r" means `e` is in `anc(r)`. Two entries neither of which is in the other's `anc` are **concurrent**. The citation rule is what lets a removal say exactly which of the removed member's entries it covers: the ones it had seen.

### 3.3 Merge (set union)

`merge(incoming)` stores an entry if all of these hold, and drops it otherwise:
1. it parses, and it is not a genesis (a log is created from its genesis; a second genesis cannot carry the same cloud id); `cloudId` equals the log's;
2. every id in `deps` is already stored (an incoming batch is processed in repeated passes, so its order does not matter; an entry whose ancestry is missing is dropped, the sender is expected to send the closure);
3. the key of `signer` is known, because some stored add or genesis carries a device key with that fingerprint, and the signature verifies under that key (this is the check that stops a forged add);
4. the log has fewer than 2048 entries, or the entry is a remove and the log has fewer than 2048 + 64 (the last 64 places are for removes, so a member that fills the log cannot stop its own removal).

Merging is idempotent, commutative and associative on a causally closed set: the stored set after any order of merges is the union of the accepted entries. Nothing in merge looks at validity; an entry made by a member that was invalid is stored, and the fold ignores it. Every stored entry has its whole ancestry stored.

### 3.4 State: the fold

State is a pure function of the stored set. Let `adds` be the genesis and add entries and `removes` the remove entries. For a set `T` of removes (an assumption), define `valid_T(e)` for the entries in an order in which every entry comes after its `deps`. Genesis is valid. An entry `e` with signer `s` is valid under `T` when all hold:

- V1. Some add entry `a` in `anc(e)` has member id `s`, kind node, a device key, and `valid_T(a)`. (The signer was a node when it signed.)
- V2. No remove `r` in `T` with target `s` is in `anc(e)`. (The signer had not been removed in the author's own past.)
- V3. For every remove `r` in `T` with target `s` that is concurrent with `e` (not in `anc(e)`, `e` not in `anc(r)`, and `r` is not `e`): `e` is itself a remove whose target is the signer of `r`. (A removed key's entries count only if the removal had seen them; the one exception is two members removing each other, below.)

`F(T)` is the set of removes valid under `T`. Making `T` bigger can only make entries invalid, so `F` is antitone. The fold computes `T0 = all removes`, `T(k+1) = F(T(k))` until `T(k+1) = T(k)`, or until `T(k+1) = T(k-1)` (the sequence alternates). In the second case the answer is `T(k) union T(k+1)`. Rationale: where the rules cannot decide, a removal takes effect (remove wins). This covers two members that remove each other at the same time, and longer cycles. It terminates in at most 2n+2 steps because `F` on a finite set is antitone. The result `P` is the **effective removals**.

Then `removed = { target(r) : r in P }`. The **members** are the adds `a` with `valid_P(a)` whose member id is not in `removed`, and (for a token key) whose issuer is a member of kind node (so removing the issuer ends the clients it issued). Two adds of the same member id are one member (the one with the smaller entry id shows its name). `removed` is permanent: an add of a removed key is never a member, whoever signs it and whenever it arrives, and a remove that arrives before the add it targets has the same effect as one that arrives after (a remove may name a key it has never seen added).

What this gives, as tests: a member's entries made before its removal survive it (the people it admitted stay); entries it makes after, or concurrently with, its removal do not (a removed member that kept the whole log and signs new entries with old `deps` is concurrent and is ignored); a removed member that signs a removal of everybody else is ignored for the same reason; two members removing each other concurrently are both removed (stated cost, section 15).

### 3.5 Creating entries

Only a current node can create entries. The code refuses to sign if the creator is not a member of kind node in its own state, or if there are more than 32 heads. Admitting a device: `add`, kind node or client, device key. Issuing a token client: section 8. Removing: `remove` with the member id of the target. The creator's own device stores the new entry and the normal sync (3.7) carries it to the others.

### 3.6 Limits and duplicates

At most 2048 entries (plus 64 for removes), body at most 512 bytes. A node id held by two different active member ids is reported in `MemberState.duplicateNodeIds`; both stay members (membership is the pair node id and key; a session is bound to the key). The admitter refuses an invite whose newcomer proposes a node id an active member with another key already holds (`node_id_taken`). Two admitters adding the same node id at the same moment still produce a duplicate (open point 7).

### 3.7 Hash and sync

`logHash = hex( H(lp("ustaad/v1/loghash") || ids in ascending byte order, 32 bytes each) cut to 8 bytes )`, 16 characters. A heartbeat carries it as `"log":"<16 hex>"`. When two members see different hashes, the one that sees it asks:

- `LogPull` request (sealed, inside a session): `u32 n || n * bytes32` (the ids it holds). Answer: the entries whose ids are not in the list, as an **EntryList**: `u32 n || n * (u32 len || entry wire)`, n at most 2048 (+64). The receiver calls merge, then recomputes the hash.

The hash is not a security device; a lying member can say anything inside its session. It exists so that an honest pair converges.

## 4. Join

### 4.1 Hosting

Hosting no longer makes a secret. The admitter shows an **invite**. Text form (QR code or link, ASCII, base64url without padding):

`ustaad://join?v=1&c=<B64 cloudId 16>&n=<nodeId>&f=<B64 fingerprint 32>&a=<addr>[,<addr>...]&p=<port>&i=<B64 code 16>`

`v` protocol version (1 = this document's join), `c` cloud id, `n` admitter's node id, `f` fp of its public key, `a` one to 8 addresses (IPv4 dotted or IPv6 in square brackets; DNS names are not accepted), `p` the port, `i` the one-time code: 128 random bits. The admitter keeps in memory per invite: the code, the kind it will admit (node or client), the issue time, and `usedBy` (empty). An invite is valid for **10 minutes** from issue (`now < issued + 600000`), at most 8 live invites. The code is a secret shared by camera; anyone who sees the QR code holds it (threat table).

### 4.2 Exchange

Both messages are one final sealed frame (section 10) of type JOIN, stream number 0, context = cloud id, so the AES-GCM tag is the MAC under a key derived from the code, in both directions. Using an AEAD instead of a bare MAC (the brief) also keeps the discovery key secret from a sniffer of the join.

1. Newcomer draws `nc` (32 random bytes). `kC2A = hkdf(code, cloudId || nc, lp("ustaad/v1/join/c2a"), 32)`.
2. Request: `u8 1 || nc || frame`. Plaintext: `u8 1 || str nodeId || str name || pub || sig` where `sig` is the newcomer's identity signature over `lp("ustaad/v1/join/pop") || cloudId || nc || str nodeId || str name || pub || str admitterNodeId` (proof that it holds the private key of the key it offers).
3. Admitter: tries each stored code (live, used or expired up to one hour past expiry) as `kC2A`. None opens: refuse (`join_refused`). The one that opens selects the invite. Checks, in order: proof of possession (else refuse); node id and name shape (else refuse); then the invite: `usedBy` equals this key's fp (it goes on whatever the age: a retry after a lost answer), or `usedBy` is empty and the invite is not expired; anything else is refused.
4. State checks, answered sealed (status in the answer): key is in `removed`: status 2. Key is already a member: status 1 (the answer carries the current list; no new entry). Node id held by another active key: status 3. Otherwise the admitter's UI asks the person to confirm (shows name, node id, first 16 hex of fp); declined: status 4 and the code stays usable. Confirmed: the admitter creates the add entry (kind from the invite) in its own list, sets `usedBy`, and answers status 0.
5. Answer: `u8 1 || na || frame`, `na` 32 fresh random bytes, `kA2C = hkdf(code, cloudId || nc || na, lp("ustaad/v1/join/a2c"), 32)`. Plaintext: `u8 status` and, for status 0 or 1, `bytes32 discoveryKey || EntryList` (the whole list including the new add).
6. Newcomer checks: the frame opens; the cloud id of the genesis in the list equals the invite's; the whole list merges (every signature verifies); the invite's node id and fingerprint are a current member of kind node; its own key is a current member. Any failure: it throws the answer away and stores nothing. On success it keeps the list and the discovery key.

The code is 128 bits, so a guess is not feasible and no password-based exchange is needed. Fresh `nc` and `na` give fresh keys per message pair, so no AES-GCM key and nonce pair is used twice even when a retry changes the plaintext.

### 4.3 State machine of an invite (admitter)

`LIVE` (issued, unused, not expired) goes to `USED` (by key K) at step 4, and to `EXPIRED` at issue + 10 minutes. `USED` and `EXPIRED` are deleted one hour after expiry (the hour only lets the UI say "expired" or "already used" instead of "unknown"). In `USED(K)`, a request from key K gets the normal answer again, at any age until the record is deleted (status 1 with the current list): this is how a newcomer recovers from a lost answer. A request from any other key is refused.

### 4.4 Outcomes

| Case | What the admitter does | What the newcomer sees |
|---|---|---|
| Wrong code (opens with no stored code) | nothing stored; counts the attempt; HTTP 403 `join_refused`, empty body | `join_refused` |
| Reused code, other key | same 403 `join_refused`, UI note "invitation already used" | `join_refused` |
| Expired code | same 403 `join_refused`, UI note "invitation expired" | `join_refused` |
| Malformed or bad proof of possession | same 403 `join_refused` | `join_refused` |
| Same key again with a used code | answers again (status 1 or 0) | joined |
| Key already a member | status 1 sealed, list and discovery key, no new entry, code becomes `USED(K)` | joined |
| Key was removed | status 2 sealed; no entry | "this device was removed; this key cannot join again, reinstall to get a new key" |
| Node id taken by another key | status 3 sealed | "node id in use" |
| Person declines | status 4 sealed, code stays live | "declined" |

All unsealed refusals are byte-identical on the wire, so wrong, reused, expired and malformed cannot be told apart by someone who does not know the code. Per-source rate limiting is left to the wiring stage (open point 14).

## 5. Sessions between members

### 5.1 Messages

Initiator I connects to responder R. Three flights, each a body of one HTTP exchange or one relay message. The brief puts one signature from each side over both ephemeral keys and both nonces; the second signer is the only one who has seen both, so the exchange has three flights.

| # | Flight | Fields |
|---|---|---|
| 1 | Init (I to R) | `u8 1 || u8 1 || str nodeI || bytes32 fpI || pub ephI || bytes32 nonceI || bytes8 tag` |
| 2 | Reply (R to I) | `u8 1 || u8 2 || str nodeR || bytes32 fpR || pub ephR || bytes32 nonceR || sig sigR` |
| 3 | Finish (I to R) | `u8 1 || u8 3 || sig sigI` |

`tag` is the discovery tag of I for the current time slot (section 11); R checks it first, so a stranger costs R one HMAC and not an ECDH and a signature. The cloud id never appears on the wire.

Transcript `T = lp("ustaad/v1/session") || u8 1 || cloudId || str nodeI || str nodeR || fpI || fpR || ephI || ephR || nonceI || nonceR`. `sigR` is R's identity signature over `lp("ustaad/v1/session/sig/r") || T`; `sigI` is I's over `lp("ustaad/v1/session/sig/i") || T`. The role label stops a signature of one role being replayed as the other. The fingerprints are in `T` so a signature is bound to keys, not only to names.

### 5.2 Checks

- R, on Init: version and shape; tag valid for `nodeI` in slot -1, 0, +1; `fpI` is a current member whose `nodeId` is `nodeI`, kind node or client, with a device key. Else `not_member` (one answer for all, no oracle). Then it makes `ephR`, `nonceR`, signs, answers, and keeps the pending state for 10 seconds (at most 64 pending; a replayed Init gets a fresh Reply and no more).
- I, on Reply: `fpR` is a current member, its node id is `nodeR` (the node id it dialled), kind node or client, device key; `sigR` verifies under the key from the list (never a key sent in the message). Then it signs and sends Finish and derives keys.
- R, on Finish: `sigI` verifies under I's listed key; then it derives keys.
- A member that is not current for either side is refused. "Current" means present in the state at that moment (so a removal the checker already has refuses the exchange).

### 5.3 Keys

`prk = HKDF-Extract(salt = nonceI || nonceR, ikm = ecdhSecret)`, `th = H(T)`. Each key is `HKDF-Expand(prk, lp(label) || th, L)`:

| Label | L | Use |
|---|---|---|
| `session/mac` | 32 | MAC key of requests, both directions |
| `session/seal/i2r` | 32 | sealing key, I to R |
| `session/seal/r2i` | 32 | sealing key, R to I |
| `session/id` | 8 | session id (16 hex), sent in `X-Swarm-Sid` |

Putting `th` into `info` ties the keys to both identities and the cloud. The ephemeral private keys are dropped after the derivation, so a later theft of an identity key does not open an old session.

### 5.4 Life

A session ends at 24 hours after it was made, when the process restarts (sessions are in memory only), when either member is removed (checked on every lookup against the current state), or at once on the removal of the local device (all sessions dropped). At most 4 sessions per peer; the oldest is dropped. A counter never restarts under a key: keys are new per session (section 10). Re-keying is a new exchange; both sessions work until the old one ends.

## 6. Requests

### 6.1 Headers and MAC

A request carries (the first four names exist today; `X-Swarm-Sig` changes meaning):

`X-Swarm-Node` sender node id; `X-Swarm-Ts` epoch ms; `X-Swarm-Req` request id (1 to 64 characters of `[A-Za-z0-9_-]`); `X-Swarm-Sig` hex of the 32-byte MAC; exactly one of `X-Swarm-Sid` (16 hex, session) or `X-Swarm-Kid` (32 hex, token key id); `X-Swarm-Stream` (decimal stream number, only when the body is sealed); `X-Swarm-Proto: 3`.

`mac = HMAC-SHA256(macKey, lp("ustaad/v1/req") || lp(METHOD) || lp(pathWithQuery) || u64 ts || lp(requestId) || lp(senderNodeId) || lp(body))` with METHOD upper-case, `pathWithQuery` the raw request target (query included, as the old signature already does) and `body` the exact bytes sent on the wire (for a sealed body, the frame; a receiver checks the MAC before it decrypts). `macKey` is the session MAC key, or for a token client its 32-byte key (section 8). The sender node id and the request id are now covered (today they are not).

### 6.2 Verification order

1. Shape of headers; `ts` not older than the receiver's start time (a restart empties the replay memory, so anything signed before the restart is refused: `skew`); `|now - ts| <= 30000` else `skew` (the answer carries server time, as today).
2. Look up the key: session by `Sid` (unknown, expired, or its peer or the local device no longer a member: `unknown_session`; the session's peer node id must equal the header's: `sender_mismatch`), or token by `Kid` (section 8).
3. Constant-time MAC check: `bad_mac`.
4. Replay guard (6.3), only for a request whose MAC was right, so a stranger cannot fill it.
5. The caller then applies the permission table (section 9) to the sender's kind.

### 6.3 Replay and the stored result

An identical copy of a signed request is a replay; a repeat of the same **request id** with a fresh signature is the sender's own retry. Today the retry is how failover works: `NodeClient` re-signs on every attempt, with a new `ts` and so a new MAC, under the same `X-Swarm-Req`, and the server dedup (`link/Dedup.kt`, key `node/reqId`) returns the stored result to the second one. These two facts do not clash if the guard tells them apart by the MAC, which the sender cannot repeat and a copier cannot change:

For key `(sender node id, request id)` the guard keeps `{ fingerprint, set of MACs, last seen }`, where `fingerprint = H(lp(METHOD) || lp(pathWithQuery) || lp(body))`. On a request with a verified MAC:

| Case | Answer |
|---|---|
| no entry | record; run (first use). Capacity: if 50000 live entries exist, answer `busy` (503), never evict a live entry |
| entry has this MAC | `replayed` (409). Not the stored result: a captured request gets nothing back |
| entry has other MACs, same fingerprint, fewer than 8 MACs | add the MAC; hand on to the existing dedup, which returns the stored result or 409 "still running" |
| entry has other MACs, different fingerprint | `request_id_reused` (422): one id never stands for two requests |
| entry has 8 MACs | `too_many_resends` (429) |

An entry lives 5 minutes after its last use (the dedup's own age, so the guard never forgets before the window of 30 seconds plus the largest allowed clock step). Entries are timed on the receiver's monotonic clock. A sender must never sign two requests with the same request id and the same `ts`; on `replayed` it resends with a fresh `ts`. Everything is in memory; the restart rule in 6.2 step 1 covers the gap.

`NodeServer` keys its dedup by the **verified** sender (today `node` comes from an unverified header, so any holder of the secret can poison another node's ids).

## 7. Error answers

HTTP answers with a JSON body `{"error":"<code>"}` (the existing `ErrorBody`). An answer before a session exists cannot be authenticated; the client treats it as information only, except that `skew` carries the server time as today.

| Code | HTTP | Meaning |
|---|---|---|
| `malformed` | 400 | does not parse, wrong version or limit |
| `unknown_session` | 401 | session id unknown, expired, or a member removed: run the exchange again |
| `unknown_key` | 401 | token key id unknown, not in the list, or removed |
| `sender_mismatch` | 401 | header node id is not the one the session or key is bound to |
| `bad_mac` | 401 | MAC wrong |
| `skew` | 401 | outside the 30 s window or older than the receiver's start |
| `not_member` | 403 | exchange: the peer or its tag is not accepted (no more detail) |
| `forbidden` | 403 | the member's kind may not do this action |
| `join_refused` | 403 | any failed join without the right code |
| `replayed` | 409 | this exact signed request was seen |
| `request_id_reused` | 422 | the id was used for a different request |
| `too_many_resends` | 429 | more than 8 signatures for one id |
| `busy` | 503 | replay memory full |
| `bad_frame`, `out_of_order`, `truncated` | local | sealed-frame failures (section 10); the stream is dropped, never partly used |

## 8. Token clients

A client that cannot run the exchange (the PC test scripts, a browser later) holds a 32-byte key issued by one node. The issuing node:
1. draws `key` (32 bytes) and `keyId` (16 bytes) at random, a node id for the client (8 random hex), a name and a kind (client or browser);
2. creates an add entry with keyType 2, `issuer` = its own member id, kind client or browser;
3. hands `key`, `keyId` and the client's node id to the client by a path the owner controls (QR, a copy), and keeps `key` in memory or its protected storage. **Other members never see `key`.**

The client signs requests with the same string as section 6.1, `macKey = key`, `X-Swarm-Kid = hex keyId`, `X-Swarm-Node` = its node id. Only the issuer can verify. It looks the key up, checks that the list has a member with id `H(lp("ustaad/v1/token") || issuerFp || keyId)` of kind client or browser, not removed, whose node id equals the header, and that the issuer (itself) is a current node; then MAC and guard as above. A token client is removed like any member: a `remove` with its member id; the issuer drops the key when it merges the removal. If the issuer is removed the fold drops its clients. The issuer carries out the client's request itself, under its own identity and after the permission table; the other members only ever see the issuer. A token session gives no forward secrecy and, until a token session handshake exists (open point 5), no confidentiality of bodies on the wire between the client and the issuer.

## 9. Permissions

Kind (the requester's) by action. `yes` means allowed; `local` means allowed only when the link is the local network (not the relay). A pure function `Permissions.allowed(kind, action, local)`.

| Action | node | client | browser |
|---|---|---|---|
| `READ_STATUS` (hello, health, caps, member list) | yes | yes | yes |
| `SUBMIT_TASK` (chat, batch, Choir run: the receiver decides where it runs) | yes | yes | yes |
| `ASK_QUESTION` (federated query over files) | yes | yes | yes |
| `USE_FILES` (list, read, write, delete in the cloud store) | yes | yes | yes |
| `WORK_FOR_PEER` (run a unit of work that a peer's scheduler dispatched, report its result) | local | no | no |
| `LEND_MEMORY` (MemoryCloud loan) | local | no | no |
| `STORE_FOR_PEER` (hold chunks for others) | local | no | no |
| `MANAGE_MEMBERS` (sync the list, add, remove, issue token clients) | yes | no | no |

For the three "for a peer" actions **both ends** must be nodes: the sender of work or a loan checks `Permissions.allowed(targetKind, ...)` before it dispatches, so a client is never given work to do. `local` is the property of the link the request arrived on, decided by the transport layer (never by anything in the request). A client or browser may use the cluster; it never computes for it.

## 10. Sealed frames

AES-256-GCM, 128-bit tag. A **stream** is one ordered sequence of frames on one carrier (one HTTP body, one SSE reply, one relay conversation); a one-shot request or response body is a stream of one final frame.

Frame: `u8 type || u8 flags || u64 counter || ciphertext || tag(16)`. Types: 1 request, 2 response, 3 event, 4 join. `flags` bit 0 is `final`; other bits must be 0. Plaintext at most 4 MiB per frame (streams cut at 16 KiB by convention).

**Stream key** (a sharpening of "one counter per direction"). The node server answers many parallel HTTP connections in one session, and frames of different requests would arrive out of order and be refused by a single per-direction counter. So each stream derives its own key from the direction key: `streamKey = HKDF-Expand(directionKey, lp("ustaad/v1/frame/stream") || u64 streamNo || lp(context), 32)`. `directionKey` is `session/seal/i2r` or `r2i`, or a join key. `streamNo` is allocated by the sender from one counter per direction and per session; `context` is the request id (so a response cannot be moved to another request) or the cloud id (join). Nonce is 4 zero bytes then the 64-bit counter, which starts at 0 in every stream: a (key, nonce) pair is used once, because `(directionKey, streamNo)` is unique per session and the session key is new every session. Associated data: `lp("ustaad/v1/frame") || u64 streamNo || u8 type || u8 flags || u64 counter`.

Receiver rules: the counter must be exactly the next one (0, then 1, ...), not merely higher: with "higher than the last", a frame deleted from the middle of a stream would go unnoticed. A frame of a different type than the stream's, any change to the header or the ciphertext, a frame from another stream or context, a wrong direction key: `bad_frame` (tag failure). A counter that is not the next: `out_of_order` (this covers replay and reordering). A frame after the final one: `bad_frame`. A carrier that closes before the final frame: `truncated`; the partial data is discarded, never delivered as a result. The stream number travels in `X-Swarm-Stream` (or the relay conversation); a wrong number fails the tag. Counters at 2^32 end the stream. After a new session the counters start at 0 again under new keys; a frame of the old session does not open under the new one.

## 11. Discovery

The join answer carries a 32-byte `discoveryKey` (made by the creator with the genesis, 32 random bytes, held by all members, never in the log). A device advertises a tag, not a name: `tag = HMAC-SHA256(discoveryKey, lp("ustaad/v1/tag") || str nodeId || u64 slot)` cut to 8 bytes (4 on a Bluetooth advert that has no room), `slot = epochMs / 600000` (10 minutes). Members accept slots -1, 0 and +1 for each member node id they know. The NSD text record is `v=3;t=<tag hex>;p=<port>` with a random instance name (not the device name or node id); the Bluetooth advert carries the tag only. An outsider sees a value that changes every 10 minutes, differs per device, and cannot be tied to a cloud or to another device. The unsigned `GET /v1/hello` answers `{"protocol":3}` and nothing else; name, serial, address and models move behind the signature. A tag in a session Init proves only that the sender holds the discovery key; the exchange proves the rest.

A removed member keeps the key it had (open point 1).

## 12. Relay (specified now, built later)

Both ends dial out to a relay with a WebSocket over TLS on 443. The relay pairs two connections and forwards bytes. It never holds a key that opens a session.

**Route id** (16 bytes, 32 hex): `hkdf(ikm = discoveryKey, salt = empty, info = lp("ustaad/v1/relay/route") || str homeNodeId || homeFp, 16)`. Only a holder of the discovery key can compute it; the relay sees the id and cannot link two ids of one cloud or learn the cloud id. It includes the home node's key, so a reinstalled home node (new key) has a new route.

**Messages** (one WebSocket binary message each, first byte the type; control ping and pong are WebSocket frames):

| Type | Name | Direction | Fields |
|---|---|---|---|
| 1 | HELLO | either to relay | `u8 ver=1 || u8 role (1 home, 2 away) || route(16)` |
| 2 | CHALLENGE | relay to home | `bytes32 nonce` |
| 3 | PROOF | home to relay | `pub || sig` over `lp("ustaad/v1/relay/open") || str relayHost || route || nonce` |
| 4 | READY | relay to home | (empty) the home socket now waits for a partner |
| 5 | PAIRED | relay to both | (empty) |
| 6 | ERR | relay to either | `u8 code` (1 bad hello, 2 bad proof, 3 route owned by another key, 4 no home, 5 rate) |
| 7 | DATA | both ways after PAIRED | opaque bytes, at most 64 KiB |

**Right to use a route.** The relay stores `route -> pub` on the first successful PROOF and requires the same key for 30 days after the last use. Each home connection signs a fresh relay nonce and the relay host name (a different relay cannot replay it). An away connection (role 2) sends only HELLO; it needs to know the route id, and its right to talk to the home node is decided end to end by the session exchange. A stranger who does not know the route id (128 bits from the discovery key) cannot hold the route. A holder of the discovery key who registers a route before the real home node can squat it (open point 1); it still reads nothing.

**Pairing.** The home node keeps one idle connection per route (HELLO, PROOF, READY). An away HELLO is paired with an idle home connection of the same route (PAIRED to both), after which both sockets only carry DATA; the home node opens a fresh idle connection at once. No idle home connection: ERR 4. When one side closes, the relay closes the other.

**What the relay sees.** Both IP addresses, the route id, the role, time, message sizes, and in the clear the flights of the session exchange (node ids, fingerprints, ephemeral keys, nonces, signatures; not the cloud id, not the tag) and the headers if the carrier shows them (open point 4). It sees DATA bodies only as sealed frames. It cannot read, forge or splice a frame (keys come from the end-to-end exchange; a spliced frame fails the stream key and counter rules).

**Keep-alive and reconnect.** WebSocket ping every 25 s from each end; a side that gets no frame for 75 s closes. The relay closes a socket silent for 75 s. A client reconnects with exponential back-off, 1 s doubling to 60 s with random jitter of up to 50 %, and on a network change at once. The session exchange runs as DATA messages, unchanged; the session survives a reconnect because it is in the ends; a stream cut by the reconnect fails as `truncated` and the client asks again with the same job id.

## 13. Removal, merge and state machines in one place

- Member: `ABSENT` to `ACTIVE` (an add valid under the fold) to `REMOVED` (an effective remove names the key; terminal). A key never leaves `REMOVED`. A member whose issuer is removed, or an add voided by the fold, is `ABSENT`.
- Removal reaches a peer by sync (3.7). The removed device is refused at the next exchange (`not_member`); a session already open ends at the next lookup after the peer has the entry. Between removal and the news, a peer that has not heard still serves the removed device (open point 10).
- Session: `NONE` to `PENDING` (Init seen, 10 s) to `ACTIVE` to `ENDED` (24 h, restart, removal of either side).
- Invite: 4.3.

## 14. Threat table

| Attacker | Can | Cannot | Rule that stops it |
|---|---|---|---|
| A device on the same Wi-Fi that is not a member | see IP addresses, packet sizes, timing, node ids and session ids in headers, the paths (open point 4); replay a captured request; try the join with guessed codes; flood | read a body or an answer; make a request the member list accepts; complete a session exchange; recognise a cloud or follow a device for longer than a 10-minute slot; learn the cloud id (never on the wire) | sealed frames (10); session exchange with identity signatures (5); replay guard, new MAC per retry (6.3); invite code 128 bits (4); tag (11) |
| A removed member that kept everything it had (log, discovery key, old session keys, files already read) | still recognise devices by tag and compute route ids (open point 1); read what it already has; sign new entries with old `deps`; try old sessions | start a session (not a member); be accepted for any new entry; re-add itself or a new key under its old key's name (removed for good; a new key needs a person to confirm); remove others by stale entries (concurrent, ignored); read new traffic (old keys end at removal or 24 h) | fold rules V1 to V3 and permanent `removed` (3.4); membership check in the exchange (5.2); session ends on removal (5.4) |
| The relay operator | see IPs, route ids, timing, sizes, the exchange flights; drop, delay, duplicate, reorder messages; refuse service; squat a route | read or alter a body, an answer or a stream; make a frame pass in another stream or direction; learn the cloud id or the discovery key; act as a member | end-to-end sealing keyed by the exchange (5, 10); counters exactly next, `truncated` (10); route id derived from a key it does not hold (12) |
| A member that misbehaves (a valid node that lies or attacks) | add keys, remove other members (every node may, plan open decision), fill the log up to its cap, flood, return wrong results, forward requests of its token clients | sign for another key; open another pair's session; make a client or browser compute for others; remove its own removal; stop a removal by filling the log | signatures per key (3); 64 places kept for removes (3.3); permission table (9); only a human removing the member ends it (open point 3) |
| Someone who steals an unlocked phone | do anything the owner can in the app: read files, ask, admit a device, remove others, issue token clients | take the private key out of a keystore-backed device (next stage) | removal by another member (3.4) and session end (5.4); the key not being exportable. The thief keeps cached data and the discovery key (open points 1, 2) |
| Someone who photographs the QR code | join within 10 minutes, if first | join without a person confirming on the admitter; join after one use or after 10 minutes | invite life and single use (4.3); confirmation step (4.2 step 4) |
| A stranger who knows the relay address | connect, flood | hold a route without the discovery key; read | route id (12); relay rate limit (open point 14) |

## 15. Open points

1. **Discovery key is never rotated.** A removed member keeps it: it can still recognise the cloud's devices by tag and follow them, and it can compute route ids and squat them at the relay (a denial of service, no reading). The key is also what every future member receives. Smallest fix: a `rotate` entry type carrying only a counter and `H(newKey)`; the new key is passed over sessions from member to member. Not built.
2. **Other cloud secrets in later stages** (the storage key of P6) have the same removal problem: whatever a removed member already holds stays valid. Needs a rotation story before P6 stores keys.
3. **Every node can remove every member and add keys.** A stolen or misbehaving node has full power until a person removes it, and a removed member's counter-removal makes both sides lose membership (section 3.4: "remove wins" with mutual exemption). An owner-only removal mode (the plan lists it as an open decision) would remove this: the creator's key could not be removed by anyone else.
4. **Metadata is visible**: node ids, session ids, request ids, paths with queries, sizes and timing, on the LAN and to the relay; only bodies are sealed. Putting method and path inside the sealed body is possible at the cost of routing by header. The flights of the session exchange show node ids and fingerprints (a SIGMA-style encrypted second flight would hide them).
5. **Token clients have no confidentiality or forward secrecy**, and a stolen token key is a full impersonation of that client until removed. A token session (nonces plus HKDF from the token key, same frame code) is easy to add but is not specified here.
6. **The log only grows.** Cap 2048 entries (+64 for removes), no compaction. A household cloud does not reach it; a hostile member can reach it.
7. **Node id duplicates** from two simultaneous admissions are reported, not resolved.
8. **Clocks.** The 30 s window and the 24 h session need clocks that agree roughly; the join and signatures do not depend on `time`. A phone with a wrong clock is refused with `skew` and the server time.
9. **A device removed while off the network** learns of it only when a peer refuses it; its data stays on it (no remote wipe).
10. **Partitions.** Between a removal and the moment a peer merges it, that peer still serves the removed device. The bound is the heartbeat interval plus the sync. No quorum.
11. **No recovery** if every node loses its key and list; the cloud is gone and is made again. The creator is not special after the genesis.
12. **Key compromise impersonation**: a stolen identity key impersonates that member to the others (inherent); a desktop `SoftwareIdentity` key sits in a file.
13. **Join confirmation is a callback** in the core; the person's tap is the wiring stage's job. Without it the first holder of the QR code gets in.
14. **No rate limiting** in the core for wrong codes, Init floods or relay connections; the wiring stage and the relay need one.
15. **Not tested on devices**: Android keystore signatures and P-256 key agreement through Conscrypt, byte order of public key conversion from the keystore's X.509 form, WebSocket on port 443 over mobile carriers (research 07's open questions). The JVM tests check the algorithms against the JDK and RFC 5869 vectors only.
16. **A removed key's entries before its removal stay valid** by design (the people it admitted stay). If the owner wants them out too, they must be removed one by one.

## 16. Where each rule is implemented

All under `app/app/src/main/java/com/swarmos/app/cloud/`: `Codec.kt` (1.1), `Crypto.kt` (1.2), `Identity.kt` (2), `MemberLog.kt` (3), `Join.kt` (4), `Session.kt` (5), `Request.kt` (6, 7, 8), `Permissions.kt` (9), `Frames.kt` (10), `Discovery.kt` (11), `Relay.kt` (12).
