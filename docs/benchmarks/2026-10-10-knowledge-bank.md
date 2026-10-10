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
