# Knowledge bank: what was built, and the two-phone check

**Status: procedure, not yet run.** The code below is covered by JVM unit tests (27 in `app/app/src/test/java/com/swarmos/app/kb/KbTest.kt`) and compiles into the debug APK. It has not been installed on a phone: every phone was queued for other runs when it was written. Nothing in this file is a measurement.

## What it is

A knowledge card is one saved answer: the question, the answer text (at most 600 characters), the sources it cited (phone name, file title, date; never file text), who saved it and when, a tier (PRIVATE or SWARM), a revoked flag with time, the audit hash of the `kb.save` entry on the saving phone, and a MAC.

- **id** = SHA-256 of the canonical content (question, answer, sources, author id and name, created time, every field length-prefixed). **MAC** = HMAC-SHA256 with the pairing secret over the same content plus the audit hash. The audit hash is under the MAC but not in the id, because the audit entry is written after the id exists.
- **What the MAC proves:** the card was made by a phone that holds the pairing secret and was not changed on the way. It does not say which of those phones, so `authorId` and `authorName` are a claim by a swarm member. Revocation has the same limit: it is checked as "the tombstone names the same author as the card", not as a signature of that one phone.
- **Stored** in `kb_cards` and `kb_tombstones` of the Data Union database file. Keyword search is query-word coverage over question and answer in memory (at most 500 cards); there is no FTS table. Each card is also embedded with the same embedder under the same guard as the file index (never the placeholder client; a changed embedder or prefix drops all card vectors first).
- **Replication (SWARM tier only):** right after a shared save, after sharing an existing card and after revoking a card that was shared, the same sync is started towards every online peer (a card or tombstone should be on the peer within a few seconds; a second request for a phone waits for the first, so a save during a sync is picked up by the next one). It also runs when a peer comes online, and every 3 minutes while it stays online, `POST /v1/kb/offer` (ids and revocation times; the reply lists what the receiver lacks and what it holds) then `POST /v1/kb/cards` (push what is missing, ask for what is lacking). Receivers verify id and MAC, refuse oversized cards, refuse a card claiming themselves as author unless they already hold it, and refuse any card with a private source. Limits per sync: 50 cards, 128 KB. Total 500 cards. A phone that received a shared card offers it on to others, so it still spreads when its author is away. "Stop sharing" only stops offering; copies already on other phones stay until the author revokes.
- **Revocation** replicates as a tombstone and wins over the card in either arrival order. A card that was never shared leaves no tombstone.
- **Use:** every Data Union ask first searches the cards. The best card answers at once (no peer queried, no model run, "Ask again" runs the live search) when its relevance clears a stated floor: cosine 0.60 and query-word coverage 0.50, or, with no vectors, coverage 0.80. **These floors are stated, not measured**; they need real saved answers on the phones. Cards below the floor, and all cards on "Ask again", join the prompt as one numbered source among the others, titled "Saved answer: ...", with no wording that makes them more reliable.
- **Save:** under a finished answer that a real chat model wrote and that cites at least one source, "Save to knowledge bank" shows the card, then "Keep on this phone" (default) or "Share with the swarm". A card with a source in a private folder cannot be shared and says why. That is decided on the phone, in `KbService.save` and `setShared`, which the button and the debug route both use: a source counts as private when the caller marks it so, when its title is "(private file on this phone)" (what the ask report shows for a private file of the owner), or when a file with that title on this phone is private (folder not shared, or a Private folder). The caller's `priv` flag can only add privacy, never remove it.
- **Page:** Data tab, "Knowledge bank": cards with author token, question, date, tier and the SAVED (outline) or REVOKED (hatched) stamp; detail with sources and provenance; Share / Stop sharing and Revoke (author only, with a confirm).

## Routes

Peers (always on, signed like every node route): `POST /v1/kb/offer`, `POST /v1/kb/cards`.
Debug builds only: `GET /v1/kb/cards` (cards that are not shared show no text), `POST /v1/kb/debug/save`, `POST /v1/kb/debug/revoke`, `POST /v1/kb/debug/sync`. The Data Union debug `POST /v1/pdu/ask` gained `"useSaved": true` (default) and reports `fromCard` and `cardIds`.

## Procedure: save on A, sync to C, stop the app on A, ask on C

Not yet run. Phones: A = 192.168.242.89 (serial 10BFC41T2N001UZ), C = 192.168.242.76 (serial 10BFAT216Z000XQ); phone B is not used. Build: `:app:assembleDebug -PidSuffix=.pdu -PportOffset=30`, package `com.swarmos.app.pdu`, Node API port 8817 (the base build is 8787, the link variant 8797). Run from the repo root in Git Bash. `scripts/swarm-call.sh` signs every call; it uses `SWARM_SECRET` or the dev secret, so export the secret the phones hold (see `GET /v1/link/pairing-payload` on a phone if it was rotated).

```bash
export MSYS_NO_PATHCONV=1
A=192.168.242.89:8817 ; C=192.168.242.76:8817
ADB="/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe"
call() { bash scripts/swarm-call.sh "$@"; }     # call <host:port> <METHOD> <path> [json]
```

0. **Before anything.** Install the same APK on A and C, start the app on both and keep it in front. Thermal guard as in `scripts/e2e-check.sh`. Expect each side to list the other online:
   `call $A GET /link/peers` and `call $C GET /link/peers` (state ONLINE). The embedder may or may not be loaded; the check works with keywords alone, and the response says which was used. No chat model is needed.

1. **Save on A.** A shared card, a kept card, and a card that must refuse to be shared:
   ```bash
   call $A POST /kb/debug/save '{"question":"How long is the laptop warranty?","answer":"Three years from the purchase date [1].","sources":[{"phone":"Phone A","title":"Laptop_Extended_Warranty_Certificate.pdf","dateMs":1760000000000,"priv":false}],"share":true}'
   call $A POST /kb/debug/save '{"question":"What is my salary?","answer":"Kept on A only [1].","sources":[{"phone":"Phone A","title":"Salary_Slip.pdf","dateMs":1760000000000,"priv":true}],"share":false}'
   call $A POST /kb/debug/save '{"question":"Share the salary?","answer":"No [1].","sources":[{"phone":"Phone A","title":"Salary_Slip.pdf","dateMs":1760000000000,"priv":true}],"share":true}'
   ```
   Expect: 200 with `tier` SWARM and a non-empty `auditHash`; 200 with tier PRIVATE; **409** with "a source is in a private folder". Record the first card's `id`. A fourth save with `"priv":false` but the title `(private file on this phone)` and `"share":true` must also be **409**: the phone decides, not the flag. A save with `"share":true` starts a sync by itself: the card should be on C within a few seconds without step 2 (check `GET /kb/cards` on C after about 5 s).

2. **Sync A to C** (the app also does this by itself within seconds of C coming online; the call makes it explicit):
   `call $A POST /kb/debug/sync '{"peer":"192.168.242.76"}'`
   Expect `sentCards` 1, `gotCards` 0, `rejected` empty, `error` null.

3. **Check C holds it, and only it.** `call $C GET /kb/cards`: one card with the recorded `id`, tier SWARM, `author` "Phone A". The kept card and the refused card must not be there. On A, `GET /kb/cards` shows the kept card with its question and answer replaced by "(card kept on this phone)".

4. **Stop the app on A.** `"$ADB" -s 10BFC41T2N001UZ shell am force-stop com.swarmos.app.pdu`. On C, poll `call $C GET /link/peers` until A is AWAY (a few seconds). Confirm A is unreachable: `SWARM_MAXTIME=8 call $A GET /kb/cards` must fail.

5. **Ask on C.** The card must answer although A is away:
   ```bash
   call $C POST /pdu/ask '{"text":"How long is the laptop warranty?","k":5,"deadlineMs":1500}'
   ```
   Expect `fromCard` equal to the recorded id, `answer` equal to the saved answer, `savedBy` "Phone A" and `savedAtMs` the card's created time, `sources` the card's cited files (phone, title, date; no file text) numbered as the saved text numbers them, `devices` empty (no peer was queried), `timings.totalMs` small, `model` empty. The Data tab shows the same: "Saved answer from Phone A, <date>" under the answer and the cited files as source cards. Cards saved before 2026-10-10 evening keep the old numbering in their text (their markers can point at nothing); new cards are renumbered to match their source list. Then the control with the live search: the same call with `"useSaved":false`. Expect `cardIds` to contain the id, A reported unreachable or offline in `devices`, `incomplete` true, and, if a real chat model is READY on C, an answer that lists the card as one source ("Saved answer: ...") among any file sources; with no chat model the `note` reads "No chat model is loaded on this phone, showing sources only".

6. **Revocation.** Start the app on A again, wait until the peers list it online, then:
   ```bash
   call $A POST /kb/debug/revoke '{"id":"<id>"}'
   call $A POST /kb/debug/sync '{"peer":"192.168.242.76"}'
   call $C GET /kb/cards
   call $C POST /pdu/ask '{"text":"How long is the laptop warranty?","k":5,"deadlineMs":1500}'
   ```
   Expect `sentTombstones` 1; on C the card has `revokedMs` set; the ask has no `fromCard` and `cardIds` does not contain the id.

7. **Tampering.** Build a wire card from C's view of the card with the answer changed and push it to A:
   ```bash
   call $C GET /kb/cards > kb-c.json
   python - <<'PY' > kb-tampered.json
   import json
   c = json.load(open("kb-c.json"))[0]
   w = {k: c[k] for k in ("id","question","answer","sources","authorId","createdMs","auditHash","mac")}
   w["authorName"] = c["author"]; w["answer"] = "Ten years."
   print(json.dumps({"cards": [w]}))
   PY
   call $A POST /kb/cards "$(cat kb-tampered.json)"
   ```
   Expect `accepted` 0 and `rejected` containing "id does not match the content". A request without signature headers (`SWARM_UNSIGNED=1`) must get 401.

8. **Audit.** The audit log of A should hold `kb.save` twice (the refused third save writes none), `kb.revoke`, `kb.sync`; C should hold `kb.sync` (side "received" for what a peer pushed to it, side "started" for a sync it ran itself; counts only, no card text) and `pdu.ask_saved`. Read them from the app's Query audit page or the audit file; the card id in `kb.save` must equal the id in step 1 and the `auditHash` of the card must equal that entry's hash.

Record per step: the raw response, the time, thermal status of both phones. Pass means every "Expect" held; any other outcome is a finding, not a pass.

## Not covered by this procedure

The visual states (empty page, SAVED and REVOKED stamps, the save panel, the saved-answer block) need a screenshot pass on a phone. The relevance floors need a calibration run with real saved answers and the real embedder, like `docs/benchmarks` for the file index. The 3-minute re-sync and the "peer comes online" trigger are only exercised if the run leaves the phones together for a few minutes after step 6.

## Third run: replication without a sync call (2026-10-10)

Run 09:41 to 10:01 IST on the three iQOO phones, base app `com.swarmos.app`, port 8787. Build: `app-debug.apk` built 09:30, one minute after merge `1f514b6` (which carries `924e720`, "push after a shared save or revoke, receiving-side audit, saved-answer provenance, privacy decided on the phone"); `app/` and `scripts/` are identical between `1f514b6` and master `3a55086` (`git diff --stat` empty). Installed with `adb install -r` on A, B and C (no `pm clear`), launched with `am start -n com.swarmos.app/.MainActivity --es name 'Phone X' --ez setupDone true --ez autoload true`. Chat `Qwen3.5-4B-Q4_K_M` READY on GPU, embedder `embeddinggemma-300M-Q8_0` READY on all three at 09:42:50 and again at 10:00. Each phone listed the other two online. Phone B = author, C and A = receivers. **No call to `/v1/kb/debug/sync` was made at any point.**

**Thermal status** (`dumpsys thermalservice`): 0 on A, B and C at every read (eight reads between 09:41 and 10:00, including before each save, before the Choir run and at the end); never above 0.

Method: save or revoke on B through `scripts/swarm-call.sh` (HTTP 200 returned, timestamp T0 taken on the PC right after), then at once two background loops, one on C and one on A, each doing `GET /v1/kb/cards` about every 1.1 s (call 0.3 to 0.5 s plus 0.6 s sleep), stopping at the first poll that lists the card id (or, for revocation, shows `revokedMs` set), at most 60 s. All times below are on the PC clock, so they do not depend on the phone clocks. "Sent / answered" is when that poll request left and when its answer arrived; the card was on the phone by "answered". Every event was seen by the **first** poll, so the true delay is below the "answered" value and cannot be resolved further with this method.

| Event on B (HTTP 200 at T0) | Seen on C (sent / answered, ms after T0) | Seen on A (sent / answered, ms after T0) |
|---|---|---|
| Shared save, card 1 | +387 / +754 | +402 / +754 |
| Revoke, card 1 | +92 / +506 | +103 / +476 |
| Shared save, card 2 (step d) | +166 / +497 | +182 / +536 |
| Revoke, card 2 (step f) | +112 / +420 | +131 / +467 |
| Revoke, card 0 (extra, see below) | +105 / +504 | +117 / +454 |
| Shared save, card 0 | blank: my first poller was started with a shifted argument list (grep -P failed on the card id) and never polled the card; the card had reached C and A anyway (audit rows below) | blank, same reason |

Card 0 was the first save of the run; the poller bug cost its listing time, so I revoked it and repeated save and revoke with cards 1 and 2. All three cards were saved from the same real answer (Phone B asked "Where is the warranty bill for my laptop?" at 09:45:59, GPU, ttft 5242 ms, total 9815 ms; four sources, the fourth being B's private file).

**Phone-side timestamps** (audit logs read with `run-as com.swarmos.app cat files/audit.jsonl`, phone clocks, so tens of ms of skew between phones): B `kb.save` or `kb.revoke` to the `kb.sync` entry with side "received" on C: +97, +56 (revoke 0), +56, +66 (revoke 1), +84 ms (save 0, revoke 0, save 1, revoke 1, save 2); on A: +22, -12, -13, +10, +10 ms. B logged its own two `kb.sync` side "started" entries 66 to 126 ms after each save or revoke.

**Step d: card served while the author is stopped.** Card 2 saved at 09:50:08 and listed on C and A (row above). `am force-stop com.swarmos.app` on B at 09:50:17; ask on C 75 ms later (C still listed B online, so I asked again after C listed B "away" and B's API gave "Connection refused"). Both asks: `fromCard` = card 2 id, `cardIds` = [card 2], `model` empty, `devices` empty (no phone queried), `incomplete` false, `note` null, `savedBy` "Phone B", `savedAtMs` 1791606011007, `sources` = 2 (Phone B IMG_2231.jpg 2025-03-14, Phone A Laptop_Extended_Warranty_Certificate.pdf 2025-03-16, no file text). `timings.totalMs` 40 (first ask) and 47 (second); the whole HTTP call took 398 and 405 ms including signing and curl. B relaunched right after the second ask; C listed it online again after 1954 ms.

**Step e: receiving-side audit.** Yes. C's `audit.jsonl` holds five `kb.sync` entries with `"side":"received"` (payload: peer id, sent 0, got 1, rejected 0, counts only, no card text) and two `pdu.ask_saved` entries (card 2 id). A holds five `received` entries too. There is no HTTP audit route in the app; the file was read through `run-as`, as `scripts/e2e2-check.sh` does.

**Step f.** Cards 0, 1 and 2 were revoked on B; at 10:00 no phone holds an active shared card (SWARM tier with `revokedMs` null: A 0, B 0, C 0). B still holds one old kept (PRIVATE) card from the earlier run, "(card kept on this phone)"; it is not shared and was not made by this run.

**Deviation from the procedure, and a finding that closes an earlier one.** `scripts/e2e/e2e2.py kb-save-body` sends all four sources of the ask; the phone answered **HTTP 409** "A source is in a private folder, so this card cannot be shared" because the fourth source is B's private file. The app's own Save keeps only the cited sources, so I sent the first two (the answer cites only [1] and [2]) and the save was accepted: tier SWARM, audit hash `0c1f2b2d...` (card 1). This is the privacy decision on the phone that the second run (finding 4) said was missing.

**Against the second run** (`2026-10-10-e2e2-knowledge-bank.md`, build `5569e36`): card and tombstone were not on C within 48 s and 60 s without a sync call; here both were on both phones at the first poll, under 0.8 s. Not measured: what happens with a phone that is away when the card is saved, the 3-minute timer, and more than one card in flight.

**Two oddities noticed, not investigated.** (1) First launch quoting error: my `adb shell am start ... --es name "Phone X"` lost its quotes, so the three phones were named "Phone" from 09:42 until I re-sent the intent with `'Phone X'` at about 09:47; a "Phone" row (09:45:01) stays in C's "Asked of this phone" audit list. (2) Live ask time to first word was 5.2 to 5.8 s on this build (B 5242 and 5770 ms via the API, C 5291 ms in the UI, 2998 ms with A away) against 556 ms for the same question in the second run; the cause (cold GPU after the reinstall, the new build, or the deadline wait) was not tested.
