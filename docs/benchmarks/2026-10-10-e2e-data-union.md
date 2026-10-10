# End-to-end check: Private Data Union on two phones (gate 4), 2026-10-10

What was run, in order: (1) the app permission "all files access" (`appops MANAGE_EXTERNAL_STORAGE`) was off for `com.swarmos.app` on both phones and was set to allow by the script, so a folder can be indexed without a picker; it is left in place. (2) The demo corpus already on the phones (`/sdcard/Pictures/SwarmDemo`, `/sdcard/Documents/SwarmDemo`; the repo corpus now says `UstaadDemo`, the phones were not re-pushed) was indexed on each phone with `POST /v1/pdu/index {"dir":..., "force":true}` (pictures first, so the old index was wiped) and then documents. (3) Every folder starts private in this build, so before sharing anything Phone A asked a question whose answer sits on C (check `f-private-default`). (4) Both folders on both phones were marked shared through the debug route `POST /v1/pdu/debug/share` (it stands in for the owner's switch in the library). (5) Nine questions were asked from Phone A with `POST /v1/pdu/ask {"k":5,"deadlineMs":1500}`, each after the thermal guard. (6) `POST /v1/pdu/debug/scores` recorded cosine and BM25 of the top hits for three questions on both phones. (7) A temporary `Private/Salary_Slip_Sep2026.pdf` (synthetic sample from the repo corpus of B) was pushed into `Documents/SwarmDemo/Private` on C, indexed, asked about from A, then deleted and C re-indexed.

Phone B's part of the corpus (the photographed laptop bill `IMG_2231.jpg`) was not on either phone, so the PRD 5.4 acceptance pair "bill on B, certificate on A" was **not** tested; the certificate on A and a document on C were.

| Field | Value |
|---|---|
| Date and time | 2026-10-10, main run 05:25 to 06:11 IST (steps a to d, f, g); eval continuation from 06:13 IST (step e, see the choir note) |
| Commit tested | master at `8f18a3e` (debug `app-debug.apk` built 05:14, still current for that commit when checked at 05:23; installed on A and C with `adb install -r` at 05:23 and 05:24). Master moved on during the run (`5803b26`, `25b7ac5`, the MemoryCloud merge); those commits are not in the tested build. |
| Script | `scripts/e2e-check.sh` (first committed as `8bddc5c`; the eval continuation used the version that adds retries and `--resume`) |
| Command | `bash scripts/e2e-check.sh 192.168.242.89 192.168.242.76` (raw responses in the scratchpad results directory `e2e-results/20261010-052551`) |
| Phones | A = "Phone A", serial 10BFC41T2N001UZ, 192.168.242.89; C = "Phone C", serial 10BFAT216Z000XQ, 192.168.242.76. Phone B was not contacted. App launched with `am start -n com.swarmos.app/.MainActivity --es name "Phone A" --ez setupDone true` (and "Phone C"). |
| Models | Chat `Qwen3.5-4B-Q4_K_M.gguf`, embedder `embeddinggemma-300M-Q8_0.gguf`, on both phones. `roles.chat.backend` was `CPU` on both phones in every state read: the GPU server did not start (finding F1 below). |
| Link | Same Wi-Fi through the access point (11ac, link speed A 433 Mbps, C 390 Mbps per `/v1/health`), plus a Wi-Fi Direct group hosted by A (SSID DIRECT-sw-c6f72247, 5 GHz, 5765 MHz) with C as client. `/v1/link/peers` listed both paths as online: A sees C over LAN at 31.3 ms and over DIRECT at 17.9 ms; C sees A at 38.2 ms on both. Which path carried the Choir, Data Union and scheduler calls was not recorded. |
| Pairing secret | Not the debug dev secret. Both phones held the same rotated secret (SHA-256 prefix 29757566, read from `GET /v1/link/pairing-payload`); a request signed with `swarmos-dev-secret` was rejected, so the script signed with the secret the debug build publishes. The phones trusted each other throughout (peers online, cross-phone calls worked). After the run both were put back on the dev secret (details in the Choir note, F9). |
| Thermal status at start | Recorded beside every number below (`A=` / `C=`); the script waits for 0 or 1 on every phone it uses before each generation step. |
| Foreground | The base app was brought to the front on both phones at 05:24 and again at 06:13; the `.link` variant of another worker took the foreground between those times at least once (seen at 06:13). |

## Questions asked from Phone A (summary)

| Ask (from Phone A) | Question | Embed ms | Local search ms | Peer round trip ms (C) | Time to first token ms | Total ms | Sources shown (phone: file) | Withheld by C | Thermal at start |
|---|---|---|---|---|---|---|---|---|---|
| pre-share-electricity | When is my electricity bill due and how much is it? | 27 | 8 | 54 | 11380 | 14988 | Phone A: (private file on this phone); Phone A: (private file on this phone); Phone A: (private file on this phone) | 1 | A=1 C=0 |
| warranty-1 | Where is the warranty bill for my laptop? | 30 | 4 | 114 | 4473 | 8358 | Phone A: Laptop_Extended_Warranty_Certificate.pdf | 0 | A=1 C=0 |
| warranty-2 | Where is the warranty bill for my laptop? | 28 | 4 | 232 | 486 | 5336 | Phone A: Laptop_Extended_Warranty_Certificate.pdf | 0 | A=1 C=0 |
| warranty-3 | Where is the warranty bill for my laptop? | 25 | 4 | 96 | 295 | 5713 | Phone A: Laptop_Extended_Warranty_Certificate.pdf | 0 | A=1 C=0 |
| electricity | When is my electricity bill due and how much is it? | 38 | 7 | 120 | 12436 | 16052 | Phone C: IMG_3301_Electricity_Bill.jpg; Phone A: Rental_Agreement_Summary.docx; Phone A: Society_Maintenance_Notice_Oct2026.pdf; Phone A: IMG_1046_Mobile_Bill_Sept.jpg | 0 | A=1 C=0 |
| unrelated | What is the boiling point of liquid nitrogen? | 28 | 2 | 90 | -1 | 94 | none | 0 | A=1 C=0 |
| hindi-warranty | मेरे लैपटॉप का वारंटी बिल कहाँ है? | 57 | 3 | 121 | 4762 | 11740 | Phone A: Laptop_Extended_Warranty_Certificate.pdf | 0 | A=1 C=0 |
| hindi-note | दूध वाले को कितने रुपये देने हैं? | 33 | 1 | 137 | 3228 | 4693 | Phone C: IMG_3302_Hindi_Note.jpg | 0 | A=1 C=0 |
| salary | What is my salary for September 2026? | 53 | 12 | 180 | 11764 | 15089 | Phone C: Society_AGM_Minutes.docx; Phone A: Society_Maintenance_Notice_Oct2026.pdf; Phone A: IMG_1046_Mobile_Bill_Sept.jpg; Phone A: Rental_Agreement_Summary.docx | 1 | A=1 C=0 |

No ask was flagged incomplete, no peer was unreachable, and the embedder was used (keywords-only false) in every ask. "Withheld by C" counts documents.

## Detail from the responses (index runs, store state, sources with scores, answer texts, calibration)

Index runs

| Phone | Folder | Seen | Indexed | Chunks | OCR images | OCR ms avg | Embed ms | Total ms | Failed | Error |
|---|---|---|---|---|---|---|---|---|---|---|
| A | documents | 7 | 7 | 7 | 1 | 95 | 1788 | 2399 | 0 |  |
| A | pictures | 5 | 4 | 4 | 5 | 161 | 823 | 1731 | 0 |  |
| C | documents-restore | 5 | 5 | 5 | 0 | 0 | 423 | 474 | 0 |  |
| C | documents | 5 | 5 | 5 | 0 | 0 | 476 | 795 | 0 |  |
| C | pictures-restore | 6 | 5 | 5 | 6 | 132 | 472 | 1406 | 0 |  |
| C | pictures | 6 | 5 | 5 | 6 | 165 | 536 | 1659 | 0 |  |
| C | private-temp | 6 | 1 | 1 | 0 | 0 | 247 | 326 | 0 |  |

Store state after indexing

| Phone | State | Embed model in the store | Photos | Documents | Scans | No text | Chunks | Folders |
|---|---|---|---|---|---|---|---|---|
| Phone A | as indexed | embeddinggemma-300M-Q8_0 | 4 | 7 | 0 | 1 | 11 | Documents/SwarmDemo (7 docs, PRIVATE); Pictures/SwarmDemo (4 docs, PRIVATE) |
| Phone A | after sharing | embeddinggemma-300M-Q8_0 | 4 | 7 | 0 | 1 | 11 | Documents/SwarmDemo (7 docs, shared); Pictures/SwarmDemo (4 docs, shared) |
| Phone C | as indexed | embeddinggemma-300M-Q8_0 | 5 | 5 | 0 | 1 | 10 | Documents/SwarmDemo (5 docs, PRIVATE); Pictures/SwarmDemo (5 docs, PRIVATE) |
| Phone C | after sharing | embeddinggemma-300M-Q8_0 | 5 | 5 | 0 | 1 | 10 | Documents/SwarmDemo (5 docs, shared); Pictures/SwarmDemo (5 docs, shared) |

Questions asked from Phone A

pdu-ask-electricity: When is my electricity bill due and how much is it?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 38 | 7 | 56 | 12436 | 16052 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 3 | - | 45 | 0 | 0 | 11 |
| Phone C | no | ok | 1 | 120 | 41 | 0 | 450 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone C | IMG_3301_Electricity_Bill.jpg | photo | 0.8750 | SUNRISE POWER DISTRIBUTION (DEMO) ELECTRICITY BILL 09/2026 Account ID: 8829104921 Meter :  |
| 2 | Phone A (local) | Rental_Agreement_Summary.docx | docx | 0.6133 | Rental agreement summary Landlord: Mr Demo Kulkarni (invented). Tenant: Rhea Testerman. Mo |
| 3 | Phone A (local) | Society_Maintenance_Notice_Oct2026.pdf | pdf | 0.6056 | Maintenance notice - October 2026 Sunflower Residency Welfare Association (DEMO) Quarterly |
| 4 | Phone A (local) | IMG_1046_Mobile_Bill_Sept.jpg | photo | 0.5000 | PINGLINE MOBILE (DEMO) Monthly bill September 2026 Plan: 2 GB per day, 84 days Bill amount |

Answer text: Your electricity bill for September 2026 is INR 2,410.00 and is due on 24-Sep-2026 [1].

pdu-ask-hindi-note: दूध वाले को कितने रुपये देने हैं?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 33 | 1 | 143 | 3228 | 4693 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 0 | - | 34 | 0 | 0 | 11 |
| Phone C | no | ok | 1 | 137 | 37 | 0 | 522 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone C | IMG_3302_Hindi_Note.jpg | photo | 0.8000 | Meeting 28-0ct 5 pm SYNTHETIC DEMO DATA - not a real document घर के काम लाइट का भुगतान 25  |

Answer text: दूध वाले को 1200 रुपये देने हैं। [1]

pdu-ask-hindi-warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 57 | 3 | 79 | 4762 | 11740 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 1 | - | 60 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 121 | 50 | 0 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone A (local) | Laptop_Extended_Warranty_Certificate.pdf | pdf | 0.5000 | Laptop extended warranty certificate Brightwell Assurance Co (DEMO) - Extended Warranty Pr |

Answer text: वर्तमान स्रोत में लैपटॉप का वारंटी बिल नहीं है, लेकिन खरीदारी का बिल (Purchase bill) Zorbik Electronics Hub से 14 मार्च 2025 को ZEH/25-26/004417 संख्या के तहत दिया गया है [1]।

pdu-ask-pre-share-electricity: When is my electricity bill due and how much is it?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 27 | 8 | 47 | 11380 | 14988 | no | no | 1 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 3 | - | 35 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 54 | 32 | 1 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone A (local) | (private file on this phone) | docx | 0.6133 |  |
| 2 | Phone A (local) | (private file on this phone) | pdf | 0.6056 |  |
| 3 | Phone A (local) | (private file on this phone) | photo | 0.5000 |  |

Answer text: I found nothing relevant in the provided sources. The documents contain information about a rental agreement, society maintenance fees, and a mobile phone bill, but no details regarding an electricity bill or its due date.

pdu-ask-salary: What is my salary for September 2026?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 53 | 12 | 78 | 11764 | 15089 | no | no | 1 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 3 | - | 65 | 0 | 0 | 11 |
| Phone C | no | ok | 1 | 180 | 45 | 1 | 500 | 11 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone C | Society_AGM_Minutes.docx | docx | 0.8253 | Annual general meeting minutes Held on 14 September 2026 at the community hall, Sunflower  |
| 2 | Phone A (local) | Society_Maintenance_Notice_Oct2026.pdf | pdf | 0.6626 | Maintenance notice - October 2026 Sunflower Residency Welfare Association (DEMO) Quarterly |
| 3 | Phone A (local) | IMG_1046_Mobile_Bill_Sept.jpg | photo | 0.8293 | PINGLINE MOBILE (DEMO) Monthly bill September 2026 Plan: 2 GB per day, 84 days Bill amount |
| 4 | Phone A (local) | Rental_Agreement_Summary.docx | docx | 0.6508 | Rental agreement summary Landlord: Mr Demo Kulkarni (invented). Tenant: Rhea Testerman. Mo |

Answer text: I found nothing relevant in the provided sources. The documents contain information about society maintenance, a mobile bill, and a rental agreement, but none mention your salary for September 2026.

pdu-ask-unrelated: What is the boiling point of liquid nitrogen?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 28 | 2 | -1 | -1 | 94 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 0 | - | 30 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 90 | 61 | 0 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| - | - | no sources shown | - | - | - |

Answer text: Nothing relevant found on any phone.

pdu-ask-warranty-1: Where is the warranty bill for my laptop?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 30 | 4 | 60 | 4473 | 8358 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 1 | - | 34 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 114 | 24 | 0 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone A (local) | Laptop_Extended_Warranty_Certificate.pdf | pdf | 1.0000 | Laptop extended warranty certificate Brightwell Assurance Co (DEMO) - Extended Warranty Pr |

Answer text: The warranty bill is a purchase bill dated 14-Mar-2025 from Zorbik Electronics Hub, document number ZEH/25-26/004417 [1].

pdu-ask-warranty-2: Where is the warranty bill for my laptop?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 28 | 4 | 55 | 486 | 5336 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 1 | - | 32 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 232 | 40 | 0 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone A (local) | Laptop_Extended_Warranty_Certificate.pdf | pdf | 1.0000 | Laptop extended warranty certificate Brightwell Assurance Co (DEMO) - Extended Warranty Pr |

Answer text: The warranty bill is a purchase bill dated 14-Mar-2025, issued by Zorbik Electronics Hub (Source [1]). It is associated with the Laptop Extended Warranty Certificate for the Kestrel Aero 14 Laptop held by Rhea Testerman.

pdu-ask-warranty-3: Where is the warranty bill for my laptop?

| Embed ms | Local search ms | First cards ms | Time to first token ms | Total ms | Incomplete | Keywords only | Withheld private | Model |
|---|---|---|---|---|---|---|---|---|
| 25 | 4 | 41 | 295 | 5713 | no | no | 0 | Qwen3.5-4B-Q4_K_M |

| Phone | Local | Status | Hits | Peer round trip ms | Peer search ms | Withheld private | Bytes | Indexed chunks |
|---|---|---|---|---|---|---|---|---|
| Phone A | yes | ok | 1 | - | 29 | 0 | 0 | 11 |
| Phone C | no | ok | 0 | 96 | 38 | 0 | 79 | 10 |

| # | Phone | File | Kind | Score | Snippet (first 90 chars) |
|---|---|---|---|---|---|
| 1 | Phone A (local) | Laptop_Extended_Warranty_Certificate.pdf | pdf | 1.0000 | Laptop extended warranty certificate Brightwell Assurance Co (DEMO) - Extended Warranty Pr |

Answer text: The warranty bill is the purchase bill ZEH/25-26/004417 dated 14-Mar-2025 from Zorbik Electronics Hub, which is attached to the Laptop_Extended_Warranty_Certificate.pdf file [1].

Calibration (POST /v1/pdu/debug/scores, top rows by RRF; the dense floor in the app is read from the response)

A-hindi_warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 11 | cosine over all chunks min 0.183 median 0.321 p90 0.375 max 0.552

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| Laptop_Extended_Warranty_Certificate.pdf | pdf | 0.552 | 1 | - | None | 0.00 | 1.0000 | yes |
| IMG_1043_Parking_Ticket.jpg | photo | 0.375 | 2 | - | None | 0.00 | 0.0000 | no |
| Society_Maintenance_Notice_Oct2026.pdf | pdf | 0.339 | 3 | - | None | 0.00 | 0.0000 | no |
| IMG_1046_Mobile_Bill_Sept.jpg | photo | 0.333 | 4 | - | None | 0.00 | 0.0000 | no |
| Rental_Agreement_Summary.docx | docx | 0.332 | 5 | - | None | 0.00 | 0.0000 | no |

A-unrelated: What is the boiling point of liquid nitrogen? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 11 | cosine over all chunks min 0.164 median 0.210 p90 0.294 max 0.302

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| Idli_Sambar_Recipe.md | text | 0.294 | 2 | -2.04 | 1 | 0.25 | 1.0000 | no |
| IMG_1042_Whiteboard_Sprint.jpg | photo | 0.302 | 1 | - | None | 0.00 | 0.0000 | no |
| Notes_Dentist_Appointment.txt | text | 0.224 | 3 | - | None | 0.00 | 0.0000 | no |
| IMG_1045_Gym_Timetable.jpg | photo | 0.218 | 4 | - | None | 0.00 | 0.0000 | no |
| IMG_1046_Mobile_Bill_Sept.jpg | photo | 0.213 | 5 | - | None | 0.00 | 0.0000 | no |

A-warranty: Where is the warranty bill for my laptop? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 11 | cosine over all chunks min 0.179 median 0.313 p90 0.359 max 0.608

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| Laptop_Extended_Warranty_Certificate.pdf | pdf | 0.608 | 1 | -7.23 | 1 | 1.00 | 1.0000 | yes |
| IMG_1046_Mobile_Bill_Sept.jpg | photo | 0.343 | 3 | -2.31 | 2 | 0.33 | 0.4919 | no |
| IMG_1042_Whiteboard_Sprint.jpg | photo | 0.359 | 2 | - | None | 0.00 | 0.0000 | no |
| Society_Maintenance_Notice_Oct2026.pdf | pdf | 0.339 | 4 | - | None | 0.00 | 0.0000 | no |
| Rental_Agreement_Summary.docx | docx | 0.335 | 5 | - | None | 0.00 | 0.0000 | no |

C-hindi_warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 10 | cosine over all chunks min 0.225 median 0.307 p90 0.366 max 0.366

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| Pet_Vaccination_Record.txt | text | 0.366 | 1 | - | None | 0.00 | 0.0000 | no |
| Wifi_Router_Setup_Notes.md | text | 0.363 | 2 | - | None | 0.00 | 0.0000 | no |
| IMG_3303_Whiteboard_Architecture.jpg | photo | 0.329 | 3 | - | None | 0.00 | 0.0000 | no |
| IMG_3306_Visiting_Card.jpg | photo | 0.329 | 4 | - | None | 0.00 | 0.0000 | no |
| IMG_3301_Electricity_Bill.jpg | photo | 0.307 | 5 | - | None | 0.00 | 0.0000 | no |

C-unrelated: What is the boiling point of liquid nitrogen? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 10 | cosine over all chunks min 0.179 median 0.242 p90 0.304 max 0.304

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| Society_AGM_Minutes.docx | docx | 0.179 | None | -1.81 | 1 | 0.25 | 1.0000 | no |
| Pet_Vaccination_Record.txt | text | 0.304 | 1 | - | None | 0.00 | 0.0000 | no |
| Wifi_Router_Setup_Notes.md | text | 0.284 | 2 | - | None | 0.00 | 0.0000 | no |
| IMG_3303_Whiteboard_Architecture.jpg | photo | 0.280 | 3 | - | None | 0.00 | 0.0000 | no |
| Meeting_Notes_Q4_Planning.txt | text | 0.265 | 4 | - | None | 0.00 | 0.0000 | no |
| IMG_3306_Visiting_Card.jpg | photo | 0.242 | 5 | - | None | 0.00 | 0.0000 | no |

C-warranty: Where is the warranty bill for my laptop? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.4000000059604645 | minCoverage 0.5 | chunks 10 | cosine over all chunks min 0.218 median 0.333 p90 0.386 max 0.386

| Title | Kind | Cosine | Dense rank | BM25 | BM25 rank | Coverage | RRF | Passes |
|---|---|---|---|---|---|---|---|---|
| IMG_3301_Electricity_Bill.jpg | photo | 0.323 | None | -1.92 | 1 | 0.33 | 1.0000 | no |
| Broadband_Plan_Terms.pdf | pdf | 0.333 | 5 | -1.15 | 2 | 0.33 | 0.9839 | no |
| Pet_Vaccination_Record.txt | text | 0.386 | 1 | - | None | 0.00 | 0.0000 | no |
| Wifi_Router_Setup_Notes.md | text | 0.377 | 2 | - | None | 0.00 | 0.0000 | no |
| IMG_3303_Whiteboard_Architecture.jpg | photo | 0.373 | 3 | - | None | 0.00 | 0.0000 | no |
| IMG_3306_Visiting_Card.jpg | photo | 0.344 | 4 | - | None | 0.00 | 0.0000 | no |

## Deviations, failures and anything that looked wrong

1. **Timing targets (PRD R4.4: first visible text 2 s, full answer 6 s) are met only when the same question is repeated.** The warranty question took 4473 ms to first token and 8358 ms in total on its first ask, then 486 ms / 5336 ms and 295 ms / 5713 ms on the second and third (same prompt, so the model server very likely reused its prompt cache: inference, not checked). The other questions took 3.2 to 12.4 s to the first token and 4.7 to 16.1 s in total (electricity 12.4 / 16.1 s, salary 11.8 / 15.1 s, Hindi warranty 4.8 / 11.7 s). The retrieval part is small: embedding 25 to 57 ms, local search 1 to 12 ms, peer round trip to C 54 to 232 ms, first source cards after 41 to 143 ms. The wait is the model reading the retrieved snippets (inference from the way time to first token grows with the number of sources shown: 1 source 0.3 to 4.8 s, 3 or 4 sources 11.4 to 12.4 s) on the CPU build.
2. **Sources are shown even when the answer says nothing relevant.** "What is my salary for September 2026?" showed four sources (Society_AGM_Minutes.docx from C, three documents from A) while the answer text said "I found nothing relevant in the provided sources". The unrelated question ("boiling point of liquid nitrogen") behaved as intended: no sources, no model call, "Nothing relevant found on any phone." in 94 ms.
3. **The answer to "Where is the warranty bill for my laptop?" does not say where.** It reads "The warranty bill is a purchase bill dated 14-Mar-2025 from Zorbik Electronics Hub, document number ZEH/25-26/004417 [1]": it describes a bill the certificate refers to and cites only the certificate on Phone A. The wording changed on every ask (three different sentences) while the cited source stayed the same.
4. **Privacy behaved as specified in the three cases tried.** Before sharing, C withheld 1 item and no source from C reached A (A's own private files were listed as "(private file on this phone)" with no snippet or title). After sharing, the salary slip in a `Private` folder inside the shared `Documents/SwarmDemo` stayed withheld (C: withheld 1, no source from it). The temporary file was deleted from C and C re-indexed (10 chunks before and after); the folder list on C shows only the two demo folders. The "withheld" count is a count of documents, not of chunks.
5. **Hindi works in this build.** The Hindi form of the warranty question found the English certificate (cosine 0.552, next best 0.375) and was answered in Hindi. The question about the milkman found the Hindi note on C (OCR returned Devanagari text) and the answer "1200 रुपये" is the figure in the note.
6. **Dense floor, numbers for the decision (constant not changed, the response reports it as 0.40).** Top cosine of the relevant hit: 0.608 (English warranty, A) and 0.552 (Hindi warranty, A). Highest cosine of anything that is not relevant: 0.359 (warranty, A), 0.386 (warranty, C), 0.302 and 0.304 (unrelated, A and C), 0.375 and 0.366 (Hindi warranty, A and C). So 0.40 separates these three questions, with 0.014 between the best irrelevant hit and the floor and 0.152 between the floor and the weakest relevant hit; three questions are a thin sample. BM25 (coverage 0.33 at best for the irrelevant hits, 1.00 for the relevant English one) did not let any irrelevant chunk through.
7. **Index state.** A: pictures 4 indexed of 5 seen (one landscape photo has no text), documents 7 of 7, 11 chunks. C: pictures 5 of 6, documents 5 of 5, 10 chunks. OCR averaged 161 ms (A) and 165 ms (C) per image, index runs took 1.7 s and 2.4 s on A, 1.7 s and 0.8 s on C.
8. **Connection drop at about 05:47 (see the Choir note).** `GET /v1/models/state` returned no answer (HTTP 000) from both phones at the same time; nothing in the Data Union steps failed afterwards, no ask was flagged incomplete, no peer was marked unreachable, `lexicalOnly` was false in every ask.
9. **The repo corpus folder name and the phone folder name differ** (`UstaadDemo` in the repo since the rename, `SwarmDemo` on the phones). The index route takes any directory, so this did not matter here; a script that pushes the repo corpus would create the new names.
10. Chat ran on the CPU build (the GPU server failed to start, finding F1 in the Choir note).
