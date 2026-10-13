# Closed cloud wire protocol (version 3)

Status: written 2026-10-13 for package P2 of plans/2026-10-13-closed-cloud.md; revised the same day (stage 1b) after the Codex read in docs/review/2026-10-13-cloud-protocol-codex-1.txt, which showed that a leaderless member list cannot make removal final. Sections 3 and 4 are new; 5 to 10, 12, 13, 15 and 16 are revised. Code of this stage: `app/app/src/main/java/com/swarmos/app/cloud/` (no Android imports, tests in `app/app/src/test/java/com/swarmos/app/cloud/`). Nothing in link/ or core/ uses it yet. Nothing here has run on a phone; every statement about the Android keystore or Conscrypt is a statement of intent, not of measurement.

Version: the closed cloud is protocol 3. `core/NodeInfo.PROTOCOL_VERSION` (2) is the old shared-secret node protocol and stays until the wiring stage.

Words: a **member** is a key listed in the member list. A **node** is a member of kind `node`. The **owner** is the device whose key writes the list. The **host** is the node that shows an invite. The **newcomer** is the device that scans it.

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

`ustaad/v1/` followed by: `entry`, `entry/sig`, `cloud`, `key`, `token`, `loghash`, `join/c2a`, `join/a2c`, `join/pop`, `session`, `session/sig/i`, `session/sig/r`, `session/mac`, `session/seal/i2r`, `session/seal/r2i`, `session/id`, `req`, `frame`, `frame/stream`, `tag`, `relay/route`, `relay/open`, `chan`, `chain`, `chain/sig`, `epoch`, `join/answer`, `join/sas`, `token/welcome`, `token/mac`, `token/seal/c2s`, `token/seal/s2c`, `token/id`, `token/loopback`.

## 2. Identity

Each device makes one EC P-256 key pair on first start. The private key signs and does nothing else; it never leaves the device. The code defines `DeviceIdentity { publicKey: ByteArray (pub); sign(message): ByteArray (sig) }`. `SoftwareIdentity` is the implementation for tests and for a desktop node (it can export and restore its PKCS#8 form; the file is the owner's to protect). The Android implementation (next stage) is a keystore key with purpose SIGN and digest SHA-256; the code never asks it for anything but `publicKey` and `sign`.

A reinstalled app has a new key, so it is a new member; the old key stays a member until the owner removes it, and the owner admits the new key only under a node id no current member holds (3.3). Two more keys exist per cloud and are not device keys: the **owner key** (the creator's device key, 3.1) and the **recovery key** (made at creation, shown once, kept off every device).

## 3. Member list: one owner, one chain

Stage 1 made the list a set of entries signed by any node and folded into a state. The Codex read (docs/review/2026-10-13-cloud-protocol-codex-1.txt) showed that such a list cannot make a removal final (a removed member can sign a removal of its remover; the caps, the heads limit and the fold's cost were all weapons in the hands of any node). Stage 1b replaces it. This section is the whole of it.

### 3.1 The owner, the recovery key and the chain

A cloud has **one owner key**: the key of the device that created it. It is the only key that writes the member list. The list is a **chain**: a sequence of entries numbered 0, 1, 2, ..., entry n carrying n, the hash of entry n-1 and one operation, signed by the owner key in force at n. A node that is not the owner cannot write at all; it can only carry a request to the owner (4.5).

A **recovery key** is made at creation: a second key pair whose public key is in the genesis. It may sign exactly one kind of entry, a transfer of ownership. The device shows its private half to the owner once (the wiring stage turns it into a code to keep off the phone) and does not keep it. The recovery key is not a member and is on no device.

The cost, stated plainly. **Membership cannot change while the owner's device cannot be reached**: nobody can be admitted or removed, no secrets can be rotated, until the owner is back or the recovery key has moved ownership to another device. Members go on working with the list they hold. Everything else in this document that needs "the owner" inherits this.

### 3.2 Entry format

An entry on the wire is `lp(body) || sig`. Its **id** is `H(body)`; `prev` of entry n+1 is the id of entry n. The signature covers `lp("ustaad/v1/chain/sig") || body`. Two entries with the same body are the same entry whatever their signatures (ECDSA signatures are malleable; ids ignore them).

Body: `lp("ustaad/v1/chain") || u8 op || u8 role || bytes16 cloudId || u64 seq || bytes32 prev || u64 time || payload`. `role` says which key signed: 1 owner, 2 recovery (only a transfer may carry 2). `time` is the owner's clock, shown to people, never used for validity. In the genesis `cloudId` is 16 zero bytes, `seq` 0, `prev` 32 zero bytes.

| op | name | payload |
|---|---|---|
| 1 | genesis | `bytes16 seed || pub owner || pub recovery || str nodeId || str name || bytes32 epoch0Commitment` |
| 2 | add | `str nodeId || str name || u8 kind || u8 keyType ||` key (keyType 1: `pub`; keyType 2: `bytes32 issuer || bytes16 keyId`) |
| 3 | remove | `bytes32 target` (a member id) |
| 4 | rotate | `u32 epoch || bytes32 commitment` |
| 5 | transfer | `pub newOwner || str nodeId || str name || u8 hasRecovery ||` (if 1) `pub newRecovery` |
| 6 | checkpoint | the state, 3.4 |

`kind`: 1 node, 2 client, 3 browser. keyType 1 (device key) for node and client, keyType 2 (token key, section 8) for client and browser. The **cloud id** is `H(lp("ustaad/v1/cloud") || genesis body)` cut to 16 bytes: random (the seed) and tied to one root. The **member id** of a device key is `fp(pub)`; of a token key `H(lp("ustaad/v1/token") || issuer || keyId)`. The owner is a member (kind node) from the genesis. **Body limit: 262144 bytes (256 KiB), enforced by one function when an entry is created and when it is parsed.** A normal entry is under 400 bytes; the limit exists for a checkpoint.

### 3.3 What each operation requires

The chain is valid only if every entry satisfies the rule of its operation against the state after the entry before it. The state holds: owner key, recovery key, epoch and the commitments of the last two epochs, the members, the removed ids, `needsRotation`, the next number and the head id.

- **Every entry**: `seq` is the next number, `prev` is the head id, `cloudId` is the cloud's, the body is within its limit, the signature verifies under the key of its `role` in force (owner for role 1, recovery for role 2).
- **add** (role 1): the node id is held by no current member (the owner enforces what stage 1 could only report); the member id is neither a member nor removed; a device key is a point on the curve and is not the recovery key; a token key's issuer is a current node; at most 512 members. A removed key can never be added again whoever asks: a reinstalled device has a new key.
- **remove** (role 1): the target is a current member and is not the owner key in force (to leave, an owner transfers first). The target and every token client it issued become removed for good (at most 4096 removed ids are kept) and `needsRotation` is set. Removal is final because there is one writer and the chain only grows: nobody but the owner can undo it, and the owner cannot add the key again.
- **rotate** (role 1): `epoch` is the current epoch plus 1; clears `needsRotation`. Section 3.7.
- **transfer** (role 1 or 2): the new owner key is a curve point, is not removed and is not the recovery key. By the owner (role 1): the new key differs from the current owner and `hasRecovery` is 0; the recovery key is unchanged. By the recovery key (role 2): `hasRecovery` is 1 and the new recovery key is a curve point different from the old one and from the new owner (the old recovery key has just been typed into a device, so it is retired); `needsRotation` is set. If the new owner is already a node member it stays one; otherwise it becomes a node member with the given node id and name (unique, as for add). The old owner stays a member until removed.
- **checkpoint** (role 1): the state it carries equals, byte for byte, the state the chain has reached. A checkpoint that lies is rejected. It changes nothing; it exists so that a newcomer or a long-absent member needs only the last checkpoint and what follows.

Why the owner cannot change the recovery key: a thief of the owner phone would otherwise replace the recovery key and lock the real owner out for good. Only the recovery key can set a new one; to get a new code, use the old one once.

### 3.4 Checkpoint state

`pub owner || pub recovery || u32 epoch || u8 n (1 or 2) || n * (u32 epoch || bytes32 commitment) (newest first) || u8 needsRotation || u32 members || members sorted by id || u32 removed || removed ids sorted`; a member is `bytes32 id || str nodeId || str name || u8 kind || u8 keyType || key || u64 addedSeq`. At most 512 members and 4096 removed ids make a checkpoint fit the body limit.

### 3.5 Accepting a chain

A device holds a chain from a **base** (the genesis, or a checkpoint it started from) to its head. `offer(entries)` takes a contiguous run of entries, verifies each against the state it reaches, and decides:

1. **Extension**: the run continues what the device holds (same prefix, then new entries): adopt the new entries. A shorter or equal run changes nothing.
2. **Gap**: the run begins after the device's head: refused, unless it begins with a checkpoint that passes the jump rule below.
3. **Fork**: the run and the held chain differ at number f. If the run's entry f is a **recovery-signed transfer** and the held entry f is not one, the run replaces everything from f on (rollback, flagged and the dropped entries kept for the Devices screen). Any other difference is **equivocation**: the device keeps what it has, refuses the other run and raises a flag the Devices screen can show.
4. An entry that does not satisfy 3.3, or is signed by anyone but the owner key in force (a node, a client, a removed member, the old owner after a transfer), makes the run invalid: nothing of it is taken.

**Why a recovery transfer wins a conflict with an owner entry at the same number.** The owner key is the thing that gets stolen; the recovery key is offline. If the first entry seen won, a thief who is online would win the race against the real owner's recovery. The recovery key is also checked against the key in force *before* the fork, so a thief's owner-signed entries cannot retire it (an owner entry cannot change it anyway). Two different recovery transfers at one number, or an owner entry after a recovery transfer, are equivocation: the first seen stays.

**Cost of that rule.** The holder of the recovery key (including a thief who has stolen the code) can fork from any point where that key was in force, however deep, and thereby undo removals made after that point. Removal is final against everybody except the recovery key. A retired recovery key keeps this power over the history before its retirement; destroy old codes.

**Jump rule** (a long-absent member or a newcomer meets a checkpoint at c beyond its head): accepted only if the checkpoint is signed by the owner key the device holds in force, names that same owner and recovery key, and its removed set contains the device's own. If ownership changed in between, the device needs the entries.

**Compaction.** A device may drop entries before the last checkpoint that it first saw at least 24 hours ago (its own clock). The hold gives the real owner a day to fork before a thief's checkpoint makes the thief's earlier changes permanent; after that, a recovery fork cannot go below the checkpoint.

### 3.6 Heartbeat and sync

`chain = "<head seq>:<8 bytes of head id in hex>"` rides in the heartbeat. A device that sees a higher number asks for the entries after its own head: `ChainPull` `u64 fromSeq || bytes32 prevId` ("I hold up to fromSeq-1, whose id is prevId"). The answer is the entries after it (at most 256, repeated), or, if the id does not match (a fork), the entries from the answerer's base. A device that sees the same number and a different id has found a fork and exchanges runs in both directions; 3.5 decides. Entries are public to members and are not secret.

### 3.7 Epochs and shared secrets

The cloud's shared secrets, the discovery key now and the storage key later, belong to an **epoch**. A secrets set is `epoch (u32) || discoveryKey (32) || storageKey (32)`; its **commitment** is `H(lp("ustaad/v1/epoch") || u32 epoch || discoveryKey || storageKey)`. The genesis names epoch 0 by its commitment. A `rotate` entry names the next epoch and the commitment of its secrets, which the owner makes at random. **The owner makes a rotate on every removal** (the code helper writes both entries; `needsRotation` stays set, and the Devices screen can show it, until a rotate exists) and after a recovery transfer.

Delivery, only over sessions between current members. Message `Secrets`, sealed in the session: `u8 1 || u32 epoch || bytes32 discoveryKey || bytes32 storageKey`. The owner pushes it to each member it reaches (`POST /v1/cloud/secrets`); a member that lacks it pulls it (`GET /v1/cloud/secrets?e=<epoch>`, answered with the same body). A receiver accepts secrets only for an epoch its own chain names (the current or the previous one) and only if their commitment equals the chain's; anything else is dropped. A member serves the secrets of epoch e only if its chain contains the rotate that names e **and** the asker is a current member in that chain. Because the chain is one sequence, a rotate cannot be known without the removal that precedes it: a removed member cannot get new secrets from a peer that is up to date, and a peer that is not up to date has none to give.

Use. Tags (section 12) and relay route ids (section 13) are computed from the current epoch's discovery key. The previous epoch's key is still used to **recognise** tags, and the previous route stays registered, for **24 hours after this device first learnt of the new epoch** (its own clock): members that have not yet received the new secrets can still find it. After that the old key is dropped. A removed member knows the keys of the epochs up to its removal and no later one: it cannot compute a later tag or route, and it cannot register a later route first.

## 4. Join

### 4.1 Hosting and the invite

Any current node may host an invite; hosting makes no secret that outlives it. The **host** shows an invite as a QR code or link (ASCII, base64url without padding):

`ustaad://join?v=1&c=<B64 cloudId 16>&n=<nodeId>&f=<B64 fingerprint 32>&a=<addr>[,<addr>...]&p=<port>&i=<B64 code 16>`

`c` cloud id, `n` the host's node id, `f` `fp` of its public key (this is the key that signs the answer, 4.3), `a` one to 8 addresses (IPv4 dotted or IPv6 in brackets; no names), `p` port, `i` the one-time code: 128 random bits, a secret shared by camera. An invite lives **10 minutes**, at most 8 open per host. The host keeps per invite: the code, the kind to admit (node or client), the issue time and `usedBy`.

### 4.2 Exchange

All sealed parts are frames of section 10 (type JOIN, stream 0, context = cloud id). Let `nc`, `na` be fresh 32-byte nonces of the newcomer and the host, `eN` and `eH` their fresh ephemeral P-256 keys.

1. **Request** (newcomer to host): `u8 1 || nc || frame`, sealed under `kC2A = hkdf(code, cloudId || nc, "join/c2a", 32)`. Plaintext `u8 1 || str nodeId || str name || pub key || pub eN || sig`, where `sig` (the newcomer's identity key) covers `lp("ustaad/v1/join/pop") || cloudId || nc || str nodeId || str name || key || eN || str hostNodeId`: possession of the key, bound to this ephemeral key, this exchange and this host.
2. The host opens it with each stored code; checks the proof; binds the invite to the key (`usedBy`); checks the list: key removed, already a member, node id taken (answers below); otherwise it **passes the request to the owner** (4.5) and answers `PENDING`.
3. **Answer** (host to newcomer): `u8 1 || na || pub eH || sig || frame`. `sig` is the host's identity signature (the key pinned in the QR code) over `lp("ustaad/v1/join/answer") || cloudId || nc || na || eN || eH || u8 status || H(secrets) || H(chain)`, where `secrets` and `chain` are the plaintext fields below (empty when absent). The frame is sealed under `kA2C = hkdf(ecdh(eH, eN) || code, cloudId || nc || na, "join/a2c", 32)`. Plaintext: `u8 status`, and for status 0 or 1: `u32 epoch || bytes32 discoveryKey || bytes32 storageKey || EntryList` (the chain from its base, including the newcomer's add entry).
4. The newcomer checks, before it keeps anything: `sig` verifies under the pinned key; the frame opens (so the host's `eH` and the ECDH are right); the cloud id is the invite's; the chain verifies from its base (3.3) and the host is a current node in it with the pinned fingerprint and node id; its own key is a current member; the secrets match the commitment for their epoch in the chain (3.7). Any failure: throw it away, store nothing.

Statuses: 0 OK, 1 ALREADY_MEMBER (answered from the list, no owner needed), 2 REMOVED, 3 NODE_ID_TAKEN, 4 DECLINED (the owner said no), 5 PENDING.

Why the key exchange (Codex Join.kt:166, :230): in stage 1 the answer key came from the code and public nonces, so whoever learnt a used code later could decrypt a recorded answer, and a holder of the code on the path could reseal it with a substituted discovery key. Now the answer key needs the ephemeral private keys, which are discarded when the exchange ends, and the host's signature covers the nonces, both ephemeral keys, the status and digests of everything it carries. A holder of the code alone can read the request (it is sealed under the code) and drop messages; it can neither read an answer nor forge one. A late replay of the recorded request gets an answer sealed to `eN`, which nobody can open.

### 4.3 The invite as a state machine (host)

`LIVE` goes to `BOUND(K)` when the first valid request from key K arrives, and to `EXPIRED` after 10 minutes. A different key presenting a bound code is refused. K asking again gets the current answer (this is how it polls while PENDING and how it recovers a lost answer). An owner's refusal returns the invite to `LIVE`. Records are kept one hour after expiry only so that the screen can say why. Every refusal without a sealed answer is the same `join_refused`.

### 4.4 Admission by the owner

The newcomer is not a member until the owner has signed its add entry, and the owner signs only after a person confirms **on the owner's device**. The confirmation is not a callback that returns true. The owner's core makes an `AdmitRequest` holding the exact newcomer key, node id, name, kind and host, and a six-digit confirmation code `d = H(lp("ustaad/v1/join/sas") || code || key || str nodeId || str name)` mod 10^6 (the newcomer's screen shows the same digits, so a person can see that the request they are asked about is the request on the phone in their hand). The only way to a granting `Approval` is `AdmitRequest.approve()`, called by the screen on the tap; the approval belongs to that request alone, is used once and is good for 120 seconds. A request with no approval, with another request's approval, with an old or reused one, or that sees the 120 seconds pass, is refused. Cancel is refusal.

### 4.5 Admit-through (host is not the owner)

```
newcomer N      host H (any node)            owner O
   |--- request -->|                            |
   |<-- PENDING ---|--- AdmitForward ---------->|  (sealed in the H-O session)
   |   (polls)     |                            |-- shows the confirmation, waits for the tap
   |               |<-- AdmitDecision ----------|  Granted(chain entries after H's head) or Denied
   |<-- OK + chain + secrets (signed by H) -----|
```

`AdmitForward` (H to O, sealed in their session, so O knows the sender): `u8 1 || u8 keyType || str nodeId || str name || u8 kind || key (pub, or bytes16 keyId for a token client) || u64 hostHeadSeq || bytes32 hostHeadId`. O accepts it only from a current node over a session and shows, as the requester, the *session's* peer, not a name in the message. `AdmitDecision` (O to H): `u8 status (0 granted, 1 denied)` and, if granted, an EntryList of the chain after `hostHead` (or from O's base if the heads differ). H merges it (3.5), after which the newcomer's next poll gets OK and the secrets H holds for the current epoch. A member that wants to leave sends O the same kind of message asking for its own removal.

**While the owner is not reachable.** H keeps the request `PENDING` until the invite's ten minutes and five more have passed, and the newcomer polls (the same request bytes every two seconds). H's screen says it is waiting for the owner's device and the newcomer's says the same; **nothing is granted**, and no one else can grant: not H, not a majority. If the time runs out, the newcomer gets the generic refusal and the person scans again later. H must hold the current epoch's secrets to finish an admission; a host that lacks them says so and does not forward.

A token client (section 8) is recorded the same way: its issuing node sends `AdmitForward` with keyType 2; the owner confirms; the add entry names the issuer.

### 4.6 Outcomes

| Case | Host does | Newcomer sees |
|---|---|---|
| Wrong code, malformed, bad proof, other key on a bound code, expired | nothing stored; 403 `join_refused`, identical bytes | `join_refused` |
| Key already a member | status 1 sealed, chain and secrets | joined |
| Key was removed | status 2 sealed | "this device was removed; reinstall for a new key" |
| Node id held by another member | status 3 sealed | "node id in use" (the owner removes the old entry or the device is reinstalled with a new id) |
| Owner not reached yet | status 5 sealed, record kept | "waiting for the owner's device" |
| Owner declines, or no tap in 120 s | status 4 sealed; invite back to LIVE | "declined" |
| Owner approves | status 0 sealed | joined |

## 5. Sessions between members

### 5.1 Messages

Initiator I connects to responder R. Three flights, each a body of one HTTP exchange or one relay message. The brief puts one signature from each side over both ephemeral keys and both nonces; the second signer is the only one who has seen both, so the exchange has three flights.

| # | Flight | Fields |
|---|---|---|
| 1 | Init (I to R) | `u8 1 || u8 1 || str nodeI || bytes32 fpI || pub ephI || bytes32 nonceI || bytes8 tag || lp(extI)` |
| 2 | Reply (R to I) | `u8 1 || u8 2 || str nodeR || bytes32 fpR || pub ephR || bytes32 nonceR || lp(extR) || sig sigR` |
| 3 | Finish (I to R) | `u8 1 || u8 3 || sig sigI` |

`tag` is the discovery tag of I for the current time slot (section 12); R checks it first, so a stranger costs R one HMAC and not an ECDH and a signature. The cloud id never appears on the wire. `ext` (at most 256 bytes, possibly empty) is a list of items `u8 id || lp(value)` in ascending id order; a reader skips ids it does not know. Item 1 is the capability offer of section 11.1. The field exists in the first version of the message so that nothing in it changes when the local channel is added.

Transcript `T = lp("ustaad/v1/session") || u8 1 || cloudId || str nodeI || str nodeR || fpI || fpR || ephI || ephR || nonceI || nonceR || lp(extI) || lp(extR)`. Both offers are signed, so a device on the path that strips an offer (to push the pair back to plain HTTP, or to a slower cipher) makes the signature fail. `sigR` is R's identity signature over `lp("ustaad/v1/session/sig/r") || T`; `sigI` is I's over `lp("ustaad/v1/session/sig/i") || T`. The role label stops a signature of one role being replayed as the other. The fingerprints are in `T` so a signature is bound to keys, not only to names.

### 5.2 Checks

- **R, on Init**, in this order. (a) Version and shape. (b) Cheap budgets: at most 30 Inits a minute per source address, and an Init whose nonce was seen in the last 10 minutes is refused (a replayed Init). (c) The discovery tag for `nodeI` in slot -1, 0, +1, against the current and the previous epoch's key (3.7). (d) **Reserve a pending slot** (64 in all, 4 per source address); none free: `busy`. (e) Only now: `fpI` is a current member whose `nodeId` is `nodeI`, kind node or client, with a device key (else `not_member`, one answer for all), then key generation and the signature. Everything before (e) costs a lookup or an HMAC. Reason (Codex Session.kt:170): the tag is observable and replayable, so a stranger replaying a captured Init used to cost the responder a key generation and a signature per copy while the capacity check came after.
- **I, on Reply.** `initiate` takes the expected member id (the key fingerprint), not only the node id: the Reply's `fpR` must equal it, its node id must be the one dialled, and `fpR` must be a current member of kind node or client with a device key. Reason (Codex Session.kt:110): names are labels and a malicious member could otherwise answer as the dialled name. `sigR` verifies under the key from the list (never a key sent in the message). Then I signs, sends Finish and derives keys.
- **R, on Finish.** `sigI` verifies under I's listed key (looked up again: I may have been removed meanwhile); then it derives keys. A Finish works once; the pending entry is released at once.
- **Single use (Codex Session.kt:132).** `complete` yields one session. A second call with the same Reply returns the same session object; any other Reply fails. It never builds a second session with the same keys and a stream counter starting again at zero (that would repeat an AES-GCM key and nonce).
- **Ephemeral private keys** are dropped when the exchange completes and when it fails, on both sides (Codex Session.kt:190); a failed exchange is dead and I starts again.
- A member that is not current for either side is refused. "Current" means present in the state at that moment.

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

**What the first flight shows.** Init carries the node id, the key fingerprint and the tag in the clear. A passive observer of session exchanges can therefore link a device across ten-minute slots; so can a relay. Hiding them (a SIGMA-style encrypted second flight) is the larger change; this document takes the smaller one and narrows its claims: sections 12, 13 and 15 promise unlinkability only against someone who watches discovery adverts (NSD, Bluetooth), not against someone who watches the exchange (open point 4).

## 6. Requests

### 6.1 Headers and MAC

A request carries (the first four names exist today; `X-Swarm-Sig` changes meaning):

`X-Swarm-Node` sender node id; `X-Swarm-Ts` epoch ms; `X-Swarm-Req` request id (1 to 64 characters of `[A-Za-z0-9_-]`); `X-Swarm-Sig` hex of the 32-byte MAC; exactly one of `X-Swarm-Sid` (16 hex: a session, or a token session, section 8) or `X-Swarm-Kid` (32 hex: a token key id, unsealed loopback mode only, 8.3); `X-Swarm-Proto: 3`. A sealed body is one final frame whose header carries its own stream number (section 10), so no extra header is needed.

`mac = HMAC-SHA256(macKey, lp("ustaad/v1/req") || lp(METHOD) || lp(pathWithQuery) || u64 ts || lp(requestId) || lp(senderNodeId) || lp(body))` with METHOD upper-case, `pathWithQuery` the raw request target (query included) and `body` the exact bytes sent on the wire (for a sealed body, the frame; the MAC is checked before the frame is opened). `macKey` is the session MAC key, the token session MAC key, or the loopback key (8.3). The sender node id and the request id are covered (the old signature covered neither).

### 6.2 Verification order

1. Shape of headers; `ts` not older than the receiver's start time (a restart empties the replay memory, so anything signed before the restart is refused: `skew`); `|now - ts| <= 30000` else `skew` (the answer carries server time).
2. Look up the key: the session by `Sid` (unknown, expired, or its peer or the local device no longer a member: `unknown_session`; the session's peer node id must equal the header's: `sender_mismatch`), or, for `Kid`, the loopback key, and only if the request came from the loopback address (`forbidden` otherwise).
3. Constant-time MAC check: `bad_mac`.
4. Open the sealed body (the frame's stream number, context = the request id): `bad_frame`. Unsealed bodies exist only in loopback mode.
5. Replay guard (6.3), keyed by the **verified member id** and the logical request, only for a request whose MAC was right and whose body opened, so a stranger cannot fill it.
6. The caller applies the permission table (section 9) to the sender's kind and to how the request arrived.

### 6.3 Replay and the stored result

An identical copy of a signed request is a replay; a repeat of the same **request id** with a fresh signature is the sender's own retry (`NodeClient` re-signs on every attempt, with a new `ts` and so a new MAC, under the same `X-Swarm-Req`, and the server dedup returns the stored result to the second one). The guard tells them apart by the MAC, which the sender cannot repeat and a copier cannot change.

The key is `(verified member id, request id)`, not the node id in a header (Codex Request.kt:161: keyed by node id, one member could poison another's ids; the owner now also keeps node ids unique, but the member id is what was verified). The stored results of the dedup are keyed the same way. The guard keeps `{ fingerprint, set of MACs, last seen }` with `fingerprint = H(lp(METHOD) || lp(pathWithQuery) || H(opened body))`: the **logical** request, so that a retry after a rekey or on another carrier, whose sealed bytes are different, is still a retry (Codex Request.kt:161, medium). On a request with a verified MAC:

| Case | Answer |
|---|---|
| no entry | record; run (first use). If 50000 live entries exist: `busy` (503), never evict a live entry |
| entry has this MAC | `replayed` (409). Not the stored result: a captured request gets nothing back |
| entry has other MACs, same fingerprint, fewer than 8 MACs | add the MAC; hand on to the dedup, which returns the stored result or 409 "still running" |
| entry has other MACs, different fingerprint | `request_id_reused` (422) |
| entry has 8 MACs | `too_many_resends` (429) |

An entry lives 5 minutes after its last use, on the receiver's monotonic clock. A sender never signs two requests with the same request id and `ts`; on `replayed` it resends with a fresh `ts`. Everything is in memory; step 1 of 6.2 covers the gap after a restart.

### 6.4 Answers

An answer to an authenticated request is sealed: stream chosen by the responder, context = the request id, the first frame of type RESPONSE holding the **envelope** `u16 status || str contentType || body bytes`, further frames of type EVENT (section 10). The status line and Content-Type of the carrier are not trusted and are not used (Codex sections 6, 7, 10): an on-path attacker could otherwise keep a valid sealed body and change an error into a success. The channel (11.2) already works this way. Errors before a session exists cannot be authenticated; the client treats them as information only.

## 7. Error answers

Before a session exists an error is an unauthenticated JSON body `{"error":"<code>"}` with the HTTP status of the table; after, the status is inside the sealed envelope (6.4). `skew` carries the server time.

| Code | HTTP | Meaning |
|---|---|---|
| `malformed` | 400 | does not parse, wrong version or limit |
| `unknown_session` | 401 | session id unknown, expired, or a member removed: run the exchange again |
| `unknown_key` | 401 | token key id unknown, not in the list, or removed |
| `sender_mismatch` | 401 | header node id is not the one the session or key is bound to |
| `bad_mac` | 401 | MAC wrong |
| `skew` | 401 | outside the 30 s window or older than the receiver's start |
| `not_member` | 403 | exchange: the peer or its tag is not accepted (no more detail) |
| `forbidden` | 403 | the member's kind may not do this action; or an unsealed request that did not come from loopback |
| `join_refused` | 403 | any failed join without the right code |
| `replayed` | 409 | this exact signed request was seen |
| `request_id_reused` | 422 | the id was used for a different request |
| `too_many_resends` | 429 | more than 8 signatures for one id |
| `busy` | 503 | replay memory or handshake budget full |
| `bad_frame`, `out_of_order`, `truncated` | local | sealed-frame failures (section 10); the stream is dead, never partly used |

## 8. Token clients

A client that cannot run the session exchange (the PC test scripts, a browser) holds a 32-byte key `K` issued by one node, its **issuer**. Both modes below are implementable in plain JavaScript with three primitives only: HMAC-SHA256, HKDF-SHA256 and AES-256-GCM (a browser on a plain http page has no `crypto.subtle`; `crypto.getRandomValues` is there; the header and nonce layouts of section 10 are byte operations).

### 8.1 Issue

The issuing node draws `K`, a `keyId` (16 bytes) and a node id for the client, and asks the owner to record it (4.5, keyType 2). The owner confirms on its device and signs an add entry (kind client or browser, issuer = the node's member id). The node keeps `K`; it hands `K`, `keyId` and the node id to the client by a path the person controls (QR, a copy). Other members never see `K`.

### 8.2 Token session (sealed)

1. `TokenHello` (`POST /v1/cloud/token/hello`): `u8 1 || bytes16 keyId || bytes32 cn` (client nonce).
2. `TokenWelcome`: `u8 1 || bytes32 sn || bytes16 boot || bytes32 mac`. `sn` is the issuer's nonce, `boot` the issuer's **boot nonce** (16 random bytes made when the process starts), `mac = HMAC-SHA256(K, lp("ustaad/v1/token/welcome") || keyId || cn || sn || boot)`; the client checks it (the issuer holds `K`).
3. Keys: `prk = HKDF-Extract(salt = cn || sn || boot, ikm = K)`; `HKDF-Expand(prk, lp(label), L)` for `token/mac` (32), `token/seal/c2s` (32), `token/seal/s2c` (32), `token/id` (8, the session id).
4. From here requests and answers are those of sections 6 and 10: `X-Swarm-Sid` = the token session id, sender = the client's node id, MAC under the token MAC key, bodies sealed under the c2s key, answers under the s2c key (AES-256-GCM only).

The boot nonce is in every key: after the issuer restarts, a captured request has no session to belong to and the same hello yields different keys, so nothing recorded before the restart can be replayed (Codex Request.kt:130, where a persistent key and an emptied replay memory allowed it). At most 4 token sessions per client and 256 in all; a Welcome not used within 60 seconds is dropped; the budgets of 5.2 apply per source address.

### 8.3 Unsealed loopback mode

The PC test scripts reach a phone through `adb forward`, so their requests come from 127.0.0.1. For them only: `GET /v1/cloud/token/boot` (loopback only) returns the boot nonce; `macKey = HKDF(K, salt = boot, "token/loopback", 32)`; the request carries `X-Swarm-Kid` and no `Sid`; the body and the answer are plain; the MAC string is that of 6.1. A request in this mode from any other address is `forbidden`, and `Permissions.allowed` refuses it too (section 9). Everything else a token client does goes through a token session.

### 8.4 Verification and removal

Only the issuer can verify (`K` is not shared). The client is a member in the chain; removing it (or its issuer, which removes every client it issued, 3.3) ends its sessions at the next lookup. The issuer carries out the client's request itself, under its own identity and after the permission table; the other members only ever see the issuer.

## 9. Permissions

Kind (the requester's) by action. `yes` means allowed; `local` means allowed only when the link is the local network (not the relay). A pure function `Permissions.allowed(kind, action, local, sealed, loopback)`.

| Action | node | client | browser |
|---|---|---|---|
| `READ_STATUS` (hello, health, caps, member list) | yes | yes | yes |
| `SUBMIT_TASK` (chat, batch, Choir run: the receiver decides where it runs) | yes | yes | yes |
| `ASK_QUESTION` (federated query over files) | yes | yes | yes |
| `USE_FILES` (list, read, write, delete in the cloud store) | yes | yes | yes |
| `WORK_FOR_PEER` (run a unit of work that a peer's scheduler dispatched, report its result) | local | no | no |
| `LEND_MEMORY` (MemoryCloud loan) | local | no | no |
| `STORE_FOR_PEER` (hold chunks for others) | local | no | no |
| `MANAGE_MEMBERS` (carry a join or removal request to the owner, pass the chain on) | yes | no | no |

Before the table: **a request that is not sealed is refused, whatever its kind, unless it came from the loopback address and its sender is a client or browser (a token client in loopback mode).** Writing the list is not an action of this table: only the owner key writes (section 3). For the three "for a peer" actions **both ends** must be nodes: the sender of work or a loan checks `Permissions.allowed(targetKind, ...)` before it dispatches. `local`, `sealed` and `loopback` are properties of the link and session the request arrived on, decided by the transport (never by anything in the request). A client or browser may use the cluster; it never computes for it.

## 10. Sealed frames

One frame format serves the HTTP bodies of sections 4 to 6, the local channel and the bulk path (section 11) and the relay (section 13). Ciphers: AES-256-GCM (always available) and ChaCha20-Poly1305 (negotiated, 11.1); both use a 12-byte nonce and a 16-byte tag. A **stream** is one numbered sequence of frames; a one-shot request or response body is a stream of one final frame.

**Frame** (14-byte header, then the sealed payload):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `type`: 1 request, 2 response, 3 event, 4 join, 5 control, 6 bulk |
| 1 | 1 | `flags`: bit 0 `final`; bits 1 to 7 must be 0 |
| 2 | 4 | `stream` (u32, top bit 0): which stream of the carrier this frame belongs to |
| 6 | 8 | `position` (u64): for types 1 to 5 the frame counter of the stream (0, 1, 2, ...); for type 6 the byte offset of the chunk in the payload |
| 14 | n | ciphertext (same length as the plaintext, at most 4 MiB; at most 1 MiB for bulk) |
| 14+n | 16 | tag |

Associated data: `lp("ustaad/v1/frame") || the 14 header bytes`, so type, flags, stream and position are all authenticated. Nonce: 4 zero bytes then `position`. A frame travels as one HTTP body, one WebSocket binary message, one relay DATA payload, or, on a raw connection, after a `u32` length (the length is carrier framing, not part of the frame).

**Stream key** (a sharpening of "one counter per direction"). The node server answers many parallel connections in one session, so frames of different requests arrive out of order and a single per-direction counter would refuse them. Each stream therefore has its own key: `streamKey = HKDF-Expand(directionKey, lp("ustaad/v1/frame/stream") || u8 cipher || u32 stream || lp(context), 32)`. `directionKey` is `session/seal/i2r` or `r2i`, or a join key. `stream` is allocated by the sender (one counter per direction and session on HTTP; the rule of 11.2 on a channel). `context` is what the stream is bound to: the request id for an HTTP request and its answer (a response cannot be moved to another request); the cloud id for a join; `lp("channel") || u32 c` for streams of a channel; `lp("bulk") || transferId` for a bulk transfer. A (key, nonce) pair is used once because `(directionKey, cipher, stream, context)` is unique per session and `position` is unique within a stream, and the session key is new every session.

**Ordered streams** (types 1 to 5). The receiver accepts exactly the next counter (0, then 1, ...), not merely a higher one: with "higher than the last", a frame deleted from the middle of a stream would go unnoticed. A frame of another type than the stream's, any change to the header or the ciphertext, a frame from another stream, context or direction: `bad_frame` (tag failure). A counter that is not the next: `out_of_order` (replay and reordering). A frame after the final one: `bad_frame`. A carrier that closes before the final frame: `truncated`; the partial data is discarded, never delivered as a result. **After any failure, `truncated` included, the stream is dead**: a missing final frame that turns up later is not accepted (Codex Frames.kt:130). Counters at 2^32 end the stream. **A stream may change type once**: an answer stream starts with type 2 (response) at counter 0 and continues with type 3 (event) at every later counter, under one counter and one key (Codex Frames.kt:117); the opener is told both types and refuses any other order. **The negotiated largest frame is enforced** by the sealer and the opener of every channel and bulk stream (a peer that agreed to 16 KiB cannot send 1 MiB; Codex Session.kt:36); one-shot HTTP bodies keep the 4 MiB limit. After a new session the counters start at 0 under new keys; a frame of the old session does not open under the new one.

**Positional streams** (type 6, bulk). Chunks may arrive in any order. Each chunk is bound to its stream and offset by the associated data and the nonce, so it cannot be moved to another offset, stream or transfer. A sender seals each offset of a stream once (a resume uses a new transfer id and so new keys); the receiver ignores a chunk it already holds, refuses one that overlaps a held chunk differently, and refuses a chunk past the end that a `final` chunk fixed. The `final` flag marks the chunk that ends the payload. Every error kills the stream and nothing more is accepted from it.

## 11. Local channel and negotiation

Why: gate 1 measured (gates/gate-1-swarmlink.md, 100 MiB, quiet phones) 305 to 503 Mbit/s over Wi-Fi Direct and 96 to 104 Mbit/s through the access point, and request round trips of 12 to 26 ms on average against a minimum of 2.7 ms, put down there to Wi-Fi power save and a connection set up per request. This section changes how traffic between members on the local network is carried, not what it means. Everything in it is an optional layer on top of sections 4 to 10.

### 11.1 Negotiation inside the session exchange

Each side puts a **capability offer** (ext item 1) in its Init or Reply:

| Field | Size | Meaning |
|---|---|---|
| `minProfile`, `maxProfile` | u8, u8 | session profiles it speaks. Profile 1 is the baseline of sections 4 to 10; profile 2 adds the channel, the bulk path and the ciphers below |
| `ciphers` | u8 | bit 0 AES-256-GCM (required in every offer), bit 1 ChaCha20-Poly1305, bit 2 "prefers ChaCha20-Poly1305" (the chip has no AES instructions) |
| `links` | u8 | the links this device has to the peer: bit 0 access point, bit 1 Wi-Fi Direct, bit 2 Bluetooth, bit 3 relay |
| `features` | u8 | bit 0 can hold a persistent channel, bit 1 can run the bulk path |
| `bulkStreams` | u8 | most parallel bulk streams it accepts (0 = none) |
| `maxFrame` | u32 | largest plaintext per frame it accepts on a channel or bulk stream, 16384 to 1048576 |

Both sides compute the same result from the two offers (a symmetric function, so nothing more is sent): profile = the highest in both ranges; cipher = ChaCha20-Poly1305 when both offer it and at least one prefers it, else AES-256-GCM; channel = profile 2 and both have feature bit 0; bulk streams = the smaller `bulkStreams` when profile 2 and both have bit 1, else 0; `maxFrame` = the smaller; links = the intersection. The cipher id is part of every stream key (section 10) and both offers are in the signed transcript (5.1), so the choice cannot be forced down by someone on the path.

**Fallback, exactly.** A device whose Init or Reply carries no ext, no capability item, or only profile 1, is a baseline device. With it, the pair uses: AES-256-GCM; no channel; no bulk path; every request is a signed request with a sealed one-frame body over HTTP, every streamed answer a sequence of sealed frames in an HTTP response (sections 6 and 10); bodies over the 1 MiB cap are refused with 413 as today. Nothing in this section is required of the baseline side and nothing in it is visible to it: a baseline device never sees a channel upgrade or a bulk open. A device that offers more never refuses a baseline peer, and a device that loses its channel or bulk path in the middle (a refused upgrade, a dropped connection, a policy) goes back to HTTP for the same request ids at once. Devices of the old shared-secret protocol 2 are not members and are outside this document; the plan keeps the old signing in debug builds only until the scripts have moved.

### 11.2 The persistent channel

One channel per pair of members at a time, instead of a connection per request. It carries requests, answers, streamed tokens, the heartbeat and control messages as frames of section 10.

- **Open.** Either side opens it, over a link in the intersection (Wi-Fi Direct before the access point; never Bluetooth or the relay for the HTTP carrier). The opener sends an ordinary signed request (section 6, `X-Swarm-Sid`) `GET /v1/cloud/channel?c=<n>` with a WebSocket upgrade; the MAC covers the query. `n` is the channel number: it must be greater than every channel number already used in this session, else `replayed`; the receiver remembers the highest. All streams of the channel use `context = lp("channel") || u32 n`, so a recording of an earlier channel cannot be played into a new one. If both sides open at once the channel opened by the side with the smaller node id is kept and the other closed. Over the relay the carrier already is one pipe: after the session exchange the opener sends a DATA message of kind 2 (section 13) with `n` and a MAC under the session MAC key.
- **Carrier.** A WebSocket where each binary message is exactly one frame; a raw upgraded connection may use `u32 length || frame` instead. The frames are identical. (Ktor server WebSockets on the CIO engine are not checked on a device.)
- **Streams.** A stream carries one request and its answer, in both directions, under the key of each direction. Ids of streams opened by the side that opened the channel are odd, by the other side even, so the two sides never open the same id; ids increase and are never reused in a channel. Stream 0 is the control stream (type 5), one per direction.
  - Request: the first frame (type 1, stream s) holds `str method || str pathWithQuery || str requestId || lp body`; the session already authenticates every frame, so there is no per-request MAC and no time field. Further body frames of type 1 follow, the last `final`.
  - Answer: the same envelope as an HTTP answer (6.4): first frame type 2, `u16 status || str contentType || body bytes`; further body or token frames type 3 in the same stream; the last `final`.
  - The handler and the permission check are those of the HTTP route of the same path, with the session's peer as the sender. Request ids and the stored-result rule of 6.3 apply unchanged: the dedup is keyed by the session peer's verified member id and the request id on both carriers.
  - The heartbeat is an ordinary small request on a stream.
- **Control frames** (type 5, stream 0, plaintext `u8 kind || ...`): `1 WINDOW(u32 stream, u32 credit)`; `2 RESET(u32 stream, u8 code)` aborts one stream (cancellation, error, over budget); `3 PING(u64)` and `4 PONG(u64)`; `5 GOAWAY(u32 lastStream, u8 code)` ends the channel: no new streams, existing ones finish.
- **Flow control per stream.** Each receiving side gives each open stream an initial credit of 256 KiB of plaintext (no WINDOW frame needed for it). A sender with no credit stops sending that stream; the receiver returns credit with WINDOW when the application has consumed at least half of it. Control frames and the heartbeat are exempt, and the sender interleaves its streams frame by frame (round robin, control first) so one large answer does not hold up a small request. A receiver buffers at most 4 MiB unread over all streams of a channel and answers a stream that would exceed it with RESET. Frames on a channel are at most the negotiated `maxFrame`, and at most 64 KiB unless both sides chose more.
- **Keep-alive.** A side that has sent nothing for 15 s sends PING; the peer answers PONG; a side that has received nothing for 45 s closes the channel (three intervals). The same applies to a relay pipe with the relay's own ping (section 13).
- **A drop in the middle.** All streams of a dropped channel are dead; partial data is discarded (`truncated`). The sender of each unfinished request opens a new channel with a larger `n` (retry after 0.5 s, doubling to 10 s) or at once uses HTTP, and sends the request again with the same request id. That is safe: the receiver answers from the stored result if the first run finished, or with "still running" if it has not, exactly as for an HTTP retry (6.3). A stream that already delivered text is not retried (the existing rule), and the consumer is told.
- **Change-over.** The HTTP routes are never removed: they remain the interface of scripts, token clients, browsers, baseline devices and the fallback. Step 1 is sections 4 to 10 only. Step 2 builds the channel behind a setting, used for the heartbeat and small requests of negotiated pairs. Step 3 makes it the default for negotiated pairs. The sender treats the channel as one more path of a request, next to each HTTP path, under one request id; a request that began on the channel and is repeated over HTTP gets the stored result. There is no ordering between the two carriers. Token clients and browsers have no session and never use the channel.

### 11.3 The bulk path

For files of the shared store and for a model file copied between devices; never for the JSON API. Local links only (the relay and Bluetooth are not used).

- **Setup.** A request on the normal path, `POST /v1/cloud/bulk/open`, permitted as `USE_FILES` for files or `STORE_FOR_PEER` for chunks held for another member. It names the object and size, the direction, `resumeFrom` (an offset) and the offered stream count and frame size. The answer gives a `transferId` (16 random bytes), the agreed stream count `n`, the frame size, the link and address to use, and the start offset. The link is the best in the intersection of the two offers: Wi-Fi Direct before the access point. The Direct group and the access point share one radio and one channel on these phones (gate 1), so using both at once is not assumed to add up (open point 18).
- **Streams.** Stream 0 of the transfer is its control stream; streams 1 to n are data streams, each its own connection to the chosen address, opened with a signed request `GET /v1/cloud/bulk/stream?t=<transferId>&s=<k>` and a WebSocket upgrade, then bulk frames (type 6). `context = lp("bulk") || transferId`, `stream = k`, `position` = the byte offset of the chunk.
- **Order.** The sender cuts the payload into chunks of the frame size from the start offset; each data stream takes the next unsent chunk when its connection can take it, so a faster stream carries more. Chunks arrive in any order. The receiver writes each chunk at its offset in a partial file and records the held ranges; memory is bounded by `n` chunks.
- **Hash.** When the sender has sent every chunk it sends one control frame on stream 0, `DONE(u64 size || bytes32 sha256 of the whole payload)`. The receiver, once the held ranges cover `[0, size)`, computes SHA-256 over the file and compares in constant time. A mismatch deletes the partial file and fails the transfer (`bad_hash`); the next attempt starts at 0. Each chunk is also authenticated by its tag, so a hash mismatch means a sender or disk fault, not tampering.
- **Resume.** After a drop the receiver reopens with `resumeFrom` = the first missing offset and the sender sends from there under a new transfer id and so new keys. Chunks the receiver holds beyond that offset are accepted again as duplicates and ignored. The held ranges are kept on disk with the partial file.
- **Number of streams.** The result of 11.1 is a ceiling. Each device offers `bulkStreams` = 1 when it is hot (thermal status at least moderate), in battery saver, or has under 1.5 GB free; otherwise `clamp(cores / 2, 1, 4)`. The sender starts with 2 streams (1 if the ceiling is 1) and adds one stream at a time every 2 s while the aggregate rate of the last 2 s grew by at least 10 % with the last addition, up to the ceiling; it removes a stream if the rate fell, or if the sealing or opening core is saturated (above 90 %) while the link is not. A slow device (no AES instructions and the ChaCha20 cipher, or low memory) also offers a smaller `maxFrame` (256 KiB instead of 1 MiB) so that `n * frame` stays small. Memory in flight is at most `2 * n * frame` per direction. These numbers are starting points; the values to use are decided by measurement (open points 17 to 19).

### 11.4 What is shared

One implementation of section 10 serves the HTTP bodies, the channel, the bulk path and the relay: the header above, the stream key, the two receiver kinds. A carrier only decides where frames go and which `context` it uses: HTTP request id, `channel n`, `bulk transferId`, the cloud id (join). The next stage adds carriers and the control messages, not fields.

## 12. Discovery

A device advertises a tag, not a name: `tag = HMAC-SHA256(discoveryKey, lp("ustaad/v1/tag") || str nodeId || u64 slot)` cut to 8 bytes (4 on a Bluetooth advert that has no room), `slot = epochMs / 600000` (10 minutes). The discovery key is that of the **current epoch** (3.7). Members accept slots -1, 0 and +1 for each member node id they know, with the current key and, for 24 hours after they first learnt of the new epoch, the previous one. The NSD text record is `v=3;t=<tag hex>;p=<port>` with a random instance name (not the device name or node id); the Bluetooth advert carries the tag only. Someone who only watches these adverts sees a value that changes every 10 minutes, differs per device, and cannot be tied to a cloud or to another device. **That is the whole claim.** It does not hold against someone who watches the session exchange: Init carries the node id, the key fingerprint and the tag in the clear (5.4, open point 4). The unsigned `GET /v1/hello` answers `{"protocol":3}` and nothing else. A tag in Init proves only that the sender holds a discovery key of a recent epoch; the exchange proves the rest.

A removed member holds the keys of the epochs up to its removal. Because the owner rotates on every removal, it cannot compute any later tag; it can still follow devices that advertise an old epoch during the 24 hours of grace.

## 13. Relay (specified now, built later)

Both ends dial out to a relay with a WebSocket over TLS on 443. The relay pairs two connections and forwards bytes. It never holds a key that opens a session.

**Route id** (16 bytes, 32 hex): `hkdf(ikm = discoveryKey, salt = empty, info = lp("ustaad/v1/relay/route") || str homeNodeId || homeFp, 16)`, with the discovery key of the **current epoch**. Only a holder of that key can compute it; the relay sees the id and cannot link two ids of one cloud or learn the cloud id. It includes the home node's key, so a reinstalled home node has a new route, and a new epoch gives a new route: after a removal the removed member cannot compute, or pre-register, any later route. The home node keeps the previous epoch's route registered for 24 hours after it learnt of the new epoch (3.7), then drops it.

**Messages** (one WebSocket binary message each, first byte the type; control ping and pong are WebSocket frames):

| Type | Name | Direction | Fields |
|---|---|---|---|
| 1 | HELLO | either to relay | `u8 ver=1 || u8 role (1 home, 2 away) || route(16)` |
| 2 | CHALLENGE | relay to home | `bytes32 nonce` |
| 3 | PROOF | home to relay | `pub || sig` over `lp("ustaad/v1/relay/open") || str relayHost || route || nonce` |
| 4 | READY | relay to home | (empty) the home socket now waits for a partner |
| 5 | PAIRED | relay to both | (empty) |
| 6 | ERR | relay to either | `u8 code` (1 bad hello, 2 bad proof, 3 route owned by another key, 4 no home, 5 rate) |
| 7 | DATA | both ways after PAIRED | at most 64 KiB, opaque to the relay: `u8 kind || bytes`. Kind 0: a session flight (section 5.1); kind 1: one sealed frame (section 10); kind 2: channel open, `u32 n || bytes32 mac` with `mac = HMAC-SHA256(session MAC key, lp("ustaad/v1/chan") || u8 role || u32 n)`, role 1 for the session initiator and 2 for the responder, `n` greater than any used in the session. The first byte is needed because a frame and a flight could otherwise begin with the same two bytes. |

**Right to use a route.** The relay stores `route -> pub` on the first successful PROOF and requires the same key for 30 days after the last use. Each home connection signs a fresh relay nonce and the relay host name (a different relay cannot replay it). An away connection (role 2) sends only HELLO; it needs to know the route id, and its right to talk to the home node is decided end to end by the session exchange. A stranger who does not know the route id (128 bits from the discovery key) cannot hold the route. A removed member can still compute the routes of the epochs it knew and can squat those (a denial of service on routes nobody uses once the grace is over, no reading); it cannot compute a later one.

**Pairing.** The home node keeps one idle connection per route (HELLO, PROOF, READY). An away HELLO is paired with an idle home connection of the same route (PAIRED to both), after which both sockets only carry DATA; the home node opens a fresh idle connection at once. No idle home connection: ERR 4. When one side closes, the relay closes the other.

**What the relay sees.** Both IP addresses, the route id, the role, time, message sizes, and in the clear the flights of the session exchange: node ids, key fingerprints, **the discovery tag**, ephemeral keys, nonces, signatures (not the cloud id), and the headers if the carrier shows them (open point 4). It sees DATA bodies only as sealed frames. It cannot read, forge or splice a frame (keys come from the end-to-end exchange; a spliced frame fails the stream key and counter rules).

**Keep-alive and reconnect.** WebSocket ping every 25 s from each end; a side that gets no frame for 75 s closes. The relay closes a socket silent for 75 s. A client reconnects with exponential back-off, 1 s doubling to 60 s with random jitter of up to 50 %, and on a network change at once. The session exchange runs as DATA messages, unchanged; the session survives a reconnect because it is in the ends; a stream cut by the reconnect fails as `truncated` and the client asks again with the same job id.

## 14. State machines in one place

- **Chain**: `held (base..head)`; an offer is an extension (adopt), a fork with a recovery-signed transfer at the first difference (rollback), or any other difference (equivocation: keep, flag). Never a merge (3.5).
- **Member**: `ABSENT` to `ACTIVE` (an add in the chain) to `REMOVED` (a remove names the key; terminal against everyone but the recovery key, 3.5). A token client is removed with its issuer.
- **Removal** reaches a peer by the heartbeat and `ChainPull` (3.6). The removed device is refused at the next exchange (`not_member`); a session already open ends at the next lookup after the peer has the entry. Until then a peer that has not heard still serves it (open point 8). The owner's `rotate` follows the removal at once, and the secrets of the new epoch reach only current members (3.7).
- **Session**: `NONE` to `PENDING` (Init accepted, a slot reserved, 10 s) to `ACTIVE` to `ENDED` (24 h, restart, removal of either side). A failed exchange is dead.
- **Invite**: 4.3. **Admission**: `PENDING` at the host until the owner's decision or the end of the window (4.5).

## 15. Threat table

| Attacker | Can | Cannot | Rule that stops it |
|---|---|---|---|
| A device on the same Wi-Fi that is not a member | see addresses, sizes, timing, and in the first flight a node id, a key fingerprint and a tag; link a device across slots by that; replay captured requests or Inits; try the join with guessed codes; flood | read a body or an answer; make a request that is accepted; complete a session exchange; make the responder generate keys or sign (budgets and the replay table come first); learn the cloud id; follow a device through discovery adverts beyond a slot | sealed frames (10); exchange with identity signatures (5); pending budgets and replayed-Init table (5.2); replay guard (6.3); invite code 128 bits (4) |
| A removed member that kept everything (list, old keys, files read) | recognise and follow devices that still advertise an old epoch for 24 hours; read what it already has; use secrets of epochs up to its removal | start a session; write anything (not the owner); sign a removal of its remover (only the owner writes); learn the secrets of a later epoch; compute a later tag or route; be added again | owner-only chain (3.1, 3.3); `rotate` on every removal and delivery to current members only (3.7); membership check in the exchange (5.2) |
| The relay operator | see IPs, route ids, timing, sizes and the first flight (node ids, fingerprints, tags); drop, delay, duplicate, reorder; refuse service; squat an old route | read or alter a body, an answer or a stream; make a frame pass in another stream or direction; learn the cloud id or the secrets; act as a member | end-to-end sealing (5, 10); counters exactly next, `truncated` (10); route from a key it does not hold (13) |
| A node that misbehaves | flood, return wrong results, forward a fake `AdmitForward` (the owner shows the session's peer and the exact key, name and code), serve a stale chain or secrets to a newcomer | write the list; admit or remove anyone; fill or block the list (there is no shared log to fill); sign for another key; make a client or browser compute; get secrets of an epoch it was removed before | one writer (3); owner confirmation (4.4); permission table (9); secrets checked against the chain's commitment (3.7) |
| Someone who steals an unlocked non-owner phone | act as that member (read files, ask) until the owner removes it | write the list; take the key out of a keystore-backed device | removal and `rotate` by the owner; session end (5.4) |
| Someone who steals the unlocked **owner** phone | do anything the owner can: admit its own devices, remove members, transfer ownership to itself, rotate to secrets it knows, write checkpoints | lock the real owner out (it cannot change the recovery key); undo what the recovery key forks away | recovery key forks at or before its first entry (3.5); a checkpoint compacts only after 24 hours (3.5) |
| Someone who holds the recovery code | fork from any point where that key was in force, undo removals after it, seize ownership | anything without the code | the code is shown once and kept off the phone; a used code is retired (3.3); open point 2 |
| Someone who photographs the QR code | bind the invite first and cause a confirmation prompt on the owner's device | be admitted without the owner's tap on the exact key, name and code; read or forge an answer | invite life and binding (4.3); owner confirmation (4.4); signed, ECDH-sealed answer (4.2) |
| Someone who learns a used invite code later | decrypt a recorded request (it is sealed under the code) | decrypt a recorded answer or replay the request for a usable answer; forge an answer | ephemeral ECDH and the host's signature (4.2) |
| A stranger who knows the relay address | connect, flood | hold a route without the discovery key; read | route id (13); rate limit at the relay (open point 13) |
| A LAN observer of a token client | see addresses, sizes, timing, ids | read a token client's request or answer; replay one after the issuer restarts | token session, boot nonce (8.2); loopback-only unsealed mode (8.3) |

## 16. Open points

Stage 1 listed 20. The owner chain closes the old 1, 3, 5, 6, 7 (they are gone or moved below); the rest stay or change as written.

1. **The owner is a single point.** While the owner's device is unreachable nobody can be admitted or removed and no secrets can rotate (3.1). A thief of the unlocked owner phone has the owner's full power until the real owner uses the recovery key.
2. **The recovery key is the root.** Whoever holds the code, or a retired code for the history before its retirement, can fork from any point where that key was in force and thereby undo removals; removal is final against everybody else. Two holders of the same key can equivocate and the first seen stays. If the owner key and the recovery code are both lost the cloud is lost and is made again. The code is shown once; keeping it is the person's job.
3. **Equivocation by the owner** (a cloned or buggy owner) splits members that saw different entries; they only raise a flag. A recovery transfer resolves it.
4. **Metadata is visible**: node ids, session ids, request ids, paths with queries, sizes and timing, on the LAN and to the relay. The first flight shows the node id, key fingerprint and tag in the clear, so an observer of exchanges can link a device across tag slots; only adverts are unlinkable (5.4). A SIGMA-style encrypted second flight would hide them.
5. **Secrets reach a member only from an up-to-date member** (3.7). If the owner and every member that holds the new epoch are away, a member without it cannot use the new tags and routes until one returns.
6. **A removed member keeps what it already has**: the secrets of epochs up to its removal, so data encrypted with an old storage key stays readable by it. P6 must re-encrypt on rotate or accept this.
7. **A newcomer or a long-absent member that starts from a checkpoint** trusts its host (and the owner's signature on the checkpoint) for freshness until it syncs with another member; a stale checkpoint would hide later removals. After compaction (24 hours of hold) a recovery fork cannot go below the checkpoint.
8. **Partitions**: between a removal and the moment a peer hears of it, that peer still serves the removed device. The bound is the heartbeat interval plus the sync. No quorum.
9. **Clocks.** The 30 s window and the 24 h session need clocks that agree roughly; chain validity does not use `time`. A phone with a wrong clock is refused with `skew` and the server time.
10. **A device removed while off the network** learns of it only when a peer refuses it; its data stays on it (no remote wipe).
11. **Key compromise impersonation**: a stolen identity key impersonates that member to the others (inherent); a desktop `SoftwareIdentity` key sits in a file.
12. **The confirmation is an API shape, not a proof of a person.** The core demands an `Approval` that only `AdmitRequest.approve()` makes, bound to the exact request, single-use and 120 s old at most; a wiring stage that calls `approve()` without a tap defeats it. The six digits help only if a person compares them.
13. **Rate limiting** beyond the handshake budgets (join attempts, relay connections, token hellos per address) is left to the wiring stage and the relay.
14. **Not tested on devices**: Android keystore signatures (DER to 64 bytes, X.509 to the 65-byte point), P-256 agreement through Conscrypt, WebSocket on port 443 over mobile carriers (research 07). The lowest supported Android is 8.0 (API 26): ChaCha20-Poly1305 may be missing below API 28 and negotiation then simply picks AES-256-GCM; `java.util.Base64` is there from API 26. The JVM tests check the algorithms against the JDK and RFC 5869 only.
15. **The browser token page is served over plain http.** Anyone on the path between the browser and the phone can alter the page's JavaScript and read the token key from it; the token session protects against a passive observer and against replay, not against an active attacker on that network. A page over TLS would need a certificate the browser trusts.
16. **Only a measurement on the phones can set the bulk numbers** (11.3): streams (start 2, growing to `clamp(cores / 2, 1, 4)`), frame size (1 MiB or 256 KiB; 30 bytes of header and tag per frame), the channel's 256 KiB window per stream and 4 MiB total, and whether handing chunks out stream by stream keeps the slower stream busy. Gate 1 gives only the link rates (305 to 503 and 96 to 104 Mbit/s for 100 MiB).
17. **Wi-Fi Direct and the access point together.** Gate 1 records one shared radio and channel. Whether spreading streams over both adds anything is untested; the specification uses one link per transfer.
18. **Whether the channel fixes the round trip.** 12 to 26 ms averages against a 2.7 ms minimum were put down to Wi-Fi power save and a connection per request (gate 1). Whether a persistent channel moves the average towards the minimum, and whether a PING every 15 s keeps the radio out of power save or only costs battery, is not known.
19. **Ciphers on the chips.** Which target chips lack AES instructions, and so whether ChaCha20-Poly1305 is ever chosen, is not measured; the JDK name is tested on the JVM only. Ktor server WebSockets on the CIO engine are unchecked on a device.

## 17. Where each rule is implemented

All under `app/app/src/main/java/com/swarmos/app/cloud/`: `Codec.kt` (1.1), `Crypto.kt` (1.2), `Identity.kt` (2), `Chain.kt` (3.1 to 3.6), `Epoch.kt` (3.7), `Join.kt` (4), `Session.kt` (5), `Negotiation.kt` (11.1), `Request.kt` (6, 7), `TokenSession.kt` (8), `Permissions.kt` (9), `Frames.kt` (10), `Discovery.kt` (12), `Relay.kt` (13).
