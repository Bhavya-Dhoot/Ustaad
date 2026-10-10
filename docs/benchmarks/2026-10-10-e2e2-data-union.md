# End-to-end check 2, Data Union on the GPU build (2026-10-10)

What was run (script `scripts/e2e2-check.sh --steps d,p`): all-files access was set to allow for `com.swarmos.app` on Phone B (it was off there; left in place). Each phone's demo folders (`Pictures/SwarmDemo`, `Documents/SwarmDemo`, which are still the names on the phones) were re-indexed with `POST /v1/pdu/index {"dir":..., "force":true}` for pictures, then documents. The acceptance pair of PRD 5.4: the bill photo `IMG_2231.jpg` is on Phone B, the extended-warranty certificate PDF is on Phone A (A is back, so no sample file had to be pushed). Questions were asked from Phone B with `POST /v1/pdu/ask {"k":5,"deadlineMs":1500}`, each after the thermal guard (0 or 1 on all three phones). The incomplete path: the app on Phone C was force-stopped (`am force-stop`) and Phone B asked at once; C was relaunched afterwards.

Phone B had already been used by the Choir worker, so its sharing state was not first-install (see finding 4).

| Field | Value |
|---|---|
| Date and time | 2026-10-10, 08:18 to 09:17 IST (queue acquired for phones A, B, C at 08:18:25, markers released at 09:17:19). Data Union and privacy 08:22 to 08:29, Knowledge Bank 08:29 to 08:33, throughput 08:33 to 08:43, Choir pass 1 08:46 to 09:05, pass 2 09:06 to 09:17. |
| Commit tested | Data Union, Knowledge Bank and throughput: master `5569e36` (debug APK built 08:08:10, installed on all three phones 08:18 to 08:20 with `adb install -r`, no `pm clear`). Agent Choir: master `e2d41e4` (APK built 08:25:52, installed 08:44). Not `5569e36` for the Choir. |
| Script | `scripts/e2e2-check.sh` with `scripts/e2e/e2e2.py`, committed with these files (hash in the report that came with this run). |
| Phones | B = "Phone B", 192.168.242.23 (asker and coordinator, first address); C = "Phone C", 192.168.242.76; A = "Phone A", 192.168.242.89 (back on the network at 08:16, third phone). App launched with `am start -n com.swarmos.app/.MainActivity --es name "Phone X" --ez setupDone true --ez autoload true`. |
| Models | Chat `Qwen3.5-4B-Q4_K_M.gguf`, embedder `embeddinggemma-300M-Q8_0.gguf` on all three. **`roles.chat.backend` was `GPU` on all three phones** in every state read (s6 checks at 08:21 and 08:44, and in every batch row below). |
| Link | Same Wi-Fi through the access point (11ac; link speed B 325, C 390, A 390 Mbps). No Wi-Fi Direct group was up. `/v1/link/peers` RTT over LAN: B to A 42.8 ms, B to C 58.9 ms, C to A 38.4 ms, C to B 52.6 ms, A to B 58.3 ms, A to C 41.3 ms. |
| Pairing secret | The debug dev secret `swarmos-dev-secret` on all three phones (signed health answered 200). Request signing: raw URI, `X-Swarm-Req` on every call. **Every answer was checked: `X-Swarm-Rsp` equals HMAC-SHA256(secret, "rsp\n$status\n$reqid\n$body")**: 332 answers checked over the whole run (steps 10, 16 in the aborted first Data Union attempt, 46, 69, 78, 28, 56 and 29), 0 wrong, 0 missing. |
| Thermal status at start | Recorded beside every number (`Phone B=` / `C=` / `A=`); the script waits for 0 or 1 on every phone it uses before each generation step. A read 3 at 08:16 (another worker) and 2 at 08:21. |
| Foreground | Base app launched on each phone at install time (08:18 to 08:20 and again 08:44 for the Choir build). Whether another worker's variant took the front during the run was not checked on every phone. |

## Questions asked (Phone B asks unless the column says otherwise)

| Ask | Asked on | Question | Embed ms | Local search ms | Slowest peer round trip ms | Time to first token ms | Total ms | Sources shown (phone: file) | Withheld by peers | Incomplete | Thermal at start |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pre-share-restaurant | Phone C | What was the total on the restaurant bill? | 26 | 4 | 106 | 3747 | 5156 | Phone B: IMG_2233_Restaurant_Bill.jpg; Phone C: IMG_3304_Pharmacy_Receipt.jpg; Phone B: IMG_2232_Grocery_Receipt.jpg; Phone B: IMG_2231.jpg | 0 | no | Phone B=0 Phone C=0 Phone A=1 |
| warranty-1 | Phone B | Where is the warranty bill for my laptop? | 38 | 7 | 116 | 5336 | 11451 | Phone B: IMG_2231.jpg; Phone A: Laptop_Extended_Warranty_Certificate.pdf; Phone B: IMG_2238_Washing_Machine_Warranty_Card.jpg; Phone B: (private file on this phone) | 0 | no | Phone B=0 Phone C=0 Phone A=1 |
| electricity | Phone B | When is my electricity bill due and how much is it? | 41 | 7 | 91 | 561 | 3643 | Phone C: IMG_3301_Electricity_Bill.jpg; Phone B: IMG_2232_Grocery_Receipt.jpg; Phone A: Rental_Agreement_Summary.docx; Phone A: Society_Maintenance_Notice_Oct2026.pdf; Phone A: IMG_1046_Mobile_Bill_Sept.jpg | 0 | no | Phone B=0 Phone C=0 Phone A=1 |
| unrelated | Phone B | What is the boiling point of liquid nitrogen? | 37 | 1 | 111 | -1 | 122 | none | 0 | no | Phone B=0 Phone C=0 Phone A=0 |
| hindi-warranty | Phone B | मेरे लैपटॉप का वारंटी बिल कहाँ है? | 47 | 4 | 93 | 4191 | 12912 | Phone B: IMG_2231.jpg; Phone A: Laptop_Extended_Warranty_Certificate.pdf; Phone B: IMG_2238_Washing_Machine_Warranty_Card.jpg | 0 | no | Phone B=0 Phone C=0 Phone A=0 |
| hindi-note | Phone B | दूध वाले को कितने रुपये देने हैं? | 29 | 1 | 75 | 1433 | 2737 | Phone C: IMG_3302_Hindi_Note.jpg | 0 | no | Phone B=0 Phone C=0 Phone A=0 |
| restaurant | Phone B | What was the total on the restaurant bill? | 27 | 6 | 83 | 4773 | 6585 | Phone B: IMG_2233_Restaurant_Bill.jpg; Phone C: IMG_3304_Pharmacy_Receipt.jpg; Phone B: IMG_2232_Grocery_Receipt.jpg; Phone B: IMG_2231.jpg | 0 | no | Phone B=0 Phone C=0 Phone A=0 |
| yoga | Phone B | When is the yoga class? | 23 | 2 | 71 | 4991 | 9281 | Phone B: Yoga_Class_Schedule.txt; Phone C: Meeting_Notes_Q4_Planning.txt; Phone A: IMG_1045_Gym_Timetable.jpg; Phone C: IMG_3302_Hindi_Note.jpg; Phone A: Notes_Dentist_Appointment.txt; Phone B: Weekend_Trek_Packing_List.txt | 0 | no | Phone B=1 Phone C=0 Phone A=0 |
| cab | Phone B | How much was the cab ride? | 23 | 3 | 71 | 1405 | 3634 | Phone B: IMG_2234_Cab_Receipt.jpg | 0 | no | Phone B=1 Phone C=0 Phone A=0 |
| warranty-2-repeat | Phone B | Where is the warranty bill for my laptop? | 50 | 7 | 101 | 554 | 5332 | Phone B: IMG_2231.jpg; Phone A: Laptop_Extended_Warranty_Certificate.pdf; Phone B: IMG_2238_Washing_Machine_Warranty_Card.jpg; Phone B: (private file on this phone) | 0 | no | Phone B=1 Phone C=0 Phone A=0 |
| salary-from-peer | Phone C | What is my salary for September 2026? | 33 | 5 | 81 | 2565 | 4733 | Phone C: Society_AGM_Minutes.docx; Phone A: Society_Maintenance_Notice_Oct2026.pdf; Phone A: IMG_1046_Mobile_Bill_Sept.jpg; Phone A: Rental_Agreement_Summary.docx | 1 | no | Phone B=1 Phone C=0 Phone A=0 |
| incomplete-electricity | Phone B | When is my electricity bill due and how much is it? | 40 | 6 | 99 | 4296 | 7846 | Phone B: IMG_2232_Grocery_Receipt.jpg; Phone A: Rental_Agreement_Summary.docx; Phone A: Society_Maintenance_Notice_Oct2026.pdf; Phone A: IMG_1046_Mobile_Bill_Sept.jpg | 0 | yes | Phone B=1 Phone C=0 Phone A=0 |
| unshared-restaurant | Phone C | What was the total on the restaurant bill? | 30 | 3 | 101 | 1477 | 4454 | Phone C: IMG_3304_Pharmacy_Receipt.jpg | 3 | no | Phone B=1 Phone C=0 Phone A=0 |

| Ask | Phone | Local | Status | Detail | Hits | Peer round trip ms | Search ms | Withheld private | Indexed chunks |
|---|---|---|---|---|---|---|---|---|---|
| pre-share-restaurant | Phone C | yes | ok |  | 1 | - | 30 | 0 | 10 |
| pre-share-restaurant | Phone B | no | ok |  | 3 | 106 | 45 | 0 | 11 |
| pre-share-restaurant | Phone A | no | ok |  | 0 | 78 | 39 | 0 | 11 |
| warranty-1 | Phone B | yes | ok |  | 3 | - | 45 | 0 | 11 |
| warranty-1 | Phone C | no | ok |  | 0 | 112 | 37 | 0 | 10 |
| warranty-1 | Phone A | no | ok |  | 1 | 116 | 40 | 0 | 11 |
| electricity | Phone B | yes | ok |  | 1 | - | 48 | 0 | 11 |
| electricity | Phone C | no | ok |  | 1 | 76 | 40 | 0 | 10 |
| electricity | Phone A | no | ok |  | 3 | 91 | 51 | 0 | 11 |
| unrelated | Phone B | yes | ok |  | 0 | - | 38 | 0 | 11 |
| unrelated | Phone C | no | ok |  | 0 | 111 | 41 | 0 | 10 |
| unrelated | Phone A | no | ok |  | 0 | 111 | 35 | 0 | 11 |
| hindi-warranty | Phone B | yes | ok |  | 2 | - | 51 | 0 | 11 |
| hindi-warranty | Phone C | no | ok |  | 0 | 82 | 44 | 0 | 10 |
| hindi-warranty | Phone A | no | ok |  | 1 | 93 | 41 | 0 | 11 |
| hindi-note | Phone B | yes | ok |  | 0 | - | 30 | 0 | 11 |
| hindi-note | Phone C | no | ok |  | 1 | 69 | 39 | 0 | 10 |
| hindi-note | Phone A | no | ok |  | 0 | 75 | 43 | 0 | 11 |
| restaurant | Phone B | yes | ok |  | 3 | - | 33 | 0 | 11 |
| restaurant | Phone C | no | ok |  | 1 | 83 | 52 | 0 | 10 |
| restaurant | Phone A | no | ok |  | 0 | 77 | 46 | 0 | 11 |
| yoga | Phone B | yes | ok |  | 2 | - | 25 | 0 | 11 |
| yoga | Phone C | no | ok |  | 3 | 70 | 33 | 0 | 10 |
| yoga | Phone A | no | ok |  | 3 | 71 | 39 | 0 | 11 |
| cab | Phone B | yes | ok |  | 1 | - | 26 | 0 | 11 |
| cab | Phone C | no | ok |  | 0 | 71 | 24 | 0 | 10 |
| cab | Phone A | no | ok |  | 0 | 70 | 42 | 0 | 11 |
| warranty-2-repeat | Phone B | yes | ok |  | 3 | - | 57 | 0 | 11 |
| warranty-2-repeat | Phone C | no | ok |  | 0 | 98 | 48 | 0 | 10 |
| warranty-2-repeat | Phone A | no | ok |  | 1 | 101 | 47 | 0 | 11 |
| salary-from-peer | Phone C | yes | ok |  | 1 | - | 38 | 0 | 10 |
| salary-from-peer | Phone B | no | ok |  | 0 | 81 | 43 | 1 | 11 |
| salary-from-peer | Phone A | no | ok |  | 3 | 81 | 45 | 0 | 11 |
| incomplete-electricity | Phone B | yes | ok |  | 1 | - | 46 | 0 | 11 |
| incomplete-electricity | Phone C | no | unreachable | ConnectException: Connection refused | 0 | 15 | 0 | 0 | 0 |
| incomplete-electricity | Phone A | no | ok |  | 3 | 99 | 60 | 0 | 11 |
| unshared-restaurant | Phone C | yes | ok |  | 1 | - | 33 | 0 | 10 |
| unshared-restaurant | Phone A | no | ok |  | 0 | 57 | 30 | 0 | 11 |
| unshared-restaurant | Phone B | no | ok |  | 0 | 101 | 66 | 3 | 11 |

Answers

- pre-share-restaurant (What was the total on the restaurant bill?): The total on the restaurant bill from Spice Alley Kitchen was 1,056.00. [1]

- warranty-1 (Where is the warranty bill for my laptop?): The warranty bill for your laptop is located in source [1], titled "WARRANTY BILL - LAPTOP". It shows the invoice details for the Kestrel Aero 14 Laptop purchased on March 14, 2025, with a 12-month standard warranty. Source [2] also references this purchase for an extended warranty certificate.

- electricity (When is my electricity bill due and how much is it?): Your electricity bill for September 2026 is INR 2,410.00 and is due on 24-Sep-2026 [1].

- unrelated (What is the boiling point of liquid nitrogen?): Nothing relevant found on any phone.

- hindi-warranty (मेरे लैपटॉप का वारंटी बिल कहाँ है?): आपके लैपटॉप का वारंटी बिल फाइल [2] में है, जो 'Phone A' पर 'Laptop_Extended_Warranty_Certificate.pdf' नाम से 2025-03-16 को ली गई थी। यह बिल 'Brightwell Assurance Co' द्वारा जारी किया गया है और आपके लैपटॉप के 24 महीने के विस्तारित वारंटी का विवरण देता है।

- hindi-note (दूध वाले को कितने रुपये देने हैं?): दूध वाले को 1200 रुपये देने हैं। [1]

- restaurant (What was the total on the restaurant bill?): The total on the restaurant bill from Spice Alley Kitchen was 1056.00. [1]

- yoga (When is the yoga class?): The yoga class is scheduled at Lotus Loft Studio on Mondays and Wednesdays at 6:30 am for beginners, Tuesdays and Thursdays at 7:00 pm for flow, and Saturdays at 8:00 am for stretching [1].

- cab (How much was the cab ride?): The cab ride cost INR 312.00 [1]. This receipt is labeled as synthetic demo data and not a real document.

- warranty-2-repeat (Where is the warranty bill for my laptop?): The warranty bill for your laptop is located in source [1] as "WARRANTY BILL - LAPTOP" with invoice number ZEH/25-26/004417. Source [2] also references this same purchase bill for your extended warranty certificate.

- salary-from-peer (What is my salary for September 2026?): I found nothing relevant in the provided sources. The documents contain only synthetic demo data regarding society meetings, maintenance notices, mobile bills, and a rental agreement; none mention personal salary information.

- incomplete-electricity (When is my electricity bill due and how much is it?): I found nothing relevant in the provided sources. The documents contain information about a grocery receipt, a rental agreement, a society maintenance notice, and a mobile bill, but none mention an electricity bill or its due date and amount.

- unshared-restaurant (What was the total on the restaurant bill?): I found nothing relevant in the provided sources. The document [1] is a pharmacy receipt showing a total of $315.00 for Vitamin D3, cough syrup, and bandage rolls, but it contains no information about a restaurant bill.

Index runs

| Phone | Folder | Seen | Indexed | Chunks | OCR images | OCR ms avg | Embed ms | Total ms | Failed | Error |
|---|---|---|---|---|---|---|---|---|---|---|
| Phone B | pictures | 8 | 7 | 7 | 8 | 99 | 923 | 1812 | 0 |  |
| Phone B | documents | 4 | 4 | 4 | 0 | 0 | 498 | 556 | 0 |  |
| Phone C | pictures | 6 | 5 | 5 | 6 | 77 | 379 | 918 | 0 |  |
| Phone C | documents | 5 | 5 | 5 | 0 | 0 | 338 | 401 | 0 |  |
| Phone A | pictures | 5 | 4 | 4 | 5 | 110 | 394 | 1023 | 0 |  |
| Phone A | documents | 7 | 7 | 7 | 1 | 74 | 885 | 1061 | 0 |  |

Calibration (POST /v1/pdu/debug/scores)

1-hindi_warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.192 median 0.295 p90 0.472 max 0.586

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| IMG_2231.jpg | 0.586 | 1 | - | 0.00 | yes |
| IMG_2238_Washing_Machine_Warranty_Card.jpg | 0.472 | 2 | - | 0.00 | yes |
| IMG_2235_Bus_Ticket.jpg | 0.342 | 3 | - | 0.00 | no |
| Weekend_Trek_Packing_List.txt | 0.315 | 4 | - | 0.00 | no |
| IMG_2237_Shopping_List_Handwritten.jpg | 0.301 | 5 | - | 0.00 | no |

1-unrelated: What is the boiling point of liquid nitrogen? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.144 median 0.211 p90 0.252 max 0.317

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| IMG_2237_Shopping_List_Handwritten.jpg | 0.317 | 1 | - | 0.00 | no |
| Weekend_Trek_Packing_List.txt | 0.252 | 2 | - | 0.00 | no |
| Yoga_Class_Schedule.txt | 0.239 | 3 | - | 0.00 | no |
| IMG_2235_Bus_Ticket.jpg | 0.221 | 4 | - | 0.00 | no |
| IMG_2232_Grocery_Receipt.jpg | 0.218 | 5 | - | 0.00 | no |

1-warranty: Where is the warranty bill for my laptop? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.211 median 0.299 p90 0.507 max 0.601

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| IMG_2231.jpg | 0.601 | 1 | -4.10 | 1.00 | yes |
| IMG_2238_Washing_Machine_Warranty_Card.jpg | 0.507 | 2 | -2.27 | 0.33 | yes |
| (private file) | 0.286 | None | -1.51 | 0.67 | yes |
| IMG_2233_Restaurant_Bill.jpg | 0.294 | None | -0.83 | 0.33 | no |
| IMG_2232_Grocery_Receipt.jpg | 0.310 | 4 | -0.50 | 0.33 | no |

2-hindi_warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 10 | cosine over all chunks min 0.225 median 0.307 p90 0.366 max 0.366

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| Pet_Vaccination_Record.txt | 0.366 | 1 | - | 0.00 | no |
| Wifi_Router_Setup_Notes.md | 0.363 | 2 | - | 0.00 | no |
| IMG_3303_Whiteboard_Architecture.jpg | 0.329 | 3 | - | 0.00 | no |
| IMG_3306_Visiting_Card.jpg | 0.329 | 4 | - | 0.00 | no |
| IMG_3301_Electricity_Bill.jpg | 0.307 | 5 | - | 0.00 | no |

2-unrelated: What is the boiling point of liquid nitrogen? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 10 | cosine over all chunks min 0.179 median 0.242 p90 0.304 max 0.304

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| Society_AGM_Minutes.docx | 0.179 | None | -1.81 | 0.25 | no |
| Pet_Vaccination_Record.txt | 0.304 | 1 | - | 0.00 | no |
| Wifi_Router_Setup_Notes.md | 0.284 | 2 | - | 0.00 | no |
| IMG_3303_Whiteboard_Architecture.jpg | 0.280 | 3 | - | 0.00 | no |
| Meeting_Notes_Q4_Planning.txt | 0.265 | 4 | - | 0.00 | no |

2-warranty: Where is the warranty bill for my laptop? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 10 | cosine over all chunks min 0.218 median 0.333 p90 0.386 max 0.386

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| IMG_3301_Electricity_Bill.jpg | 0.323 | None | -1.92 | 0.33 | no |
| Broadband_Plan_Terms.pdf | 0.333 | 5 | -1.15 | 0.33 | no |
| Pet_Vaccination_Record.txt | 0.386 | 1 | - | 0.00 | no |
| Wifi_Router_Setup_Notes.md | 0.377 | 2 | - | 0.00 | no |
| IMG_3303_Whiteboard_Architecture.jpg | 0.373 | 3 | - | 0.00 | no |

3-hindi_warranty: मेरे लैपटॉप का वारंटी बिल कहाँ है? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.183 median 0.321 p90 0.375 max 0.552

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| Laptop_Extended_Warranty_Certificate.pdf | 0.552 | 1 | - | 0.00 | yes |
| IMG_1043_Parking_Ticket.jpg | 0.375 | 2 | - | 0.00 | no |
| Society_Maintenance_Notice_Oct2026.pdf | 0.339 | 3 | - | 0.00 | no |
| IMG_1046_Mobile_Bill_Sept.jpg | 0.333 | 4 | - | 0.00 | no |
| Rental_Agreement_Summary.docx | 0.332 | 5 | - | 0.00 | no |

3-unrelated: What is the boiling point of liquid nitrogen? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.164 median 0.210 p90 0.294 max 0.302

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| Idli_Sambar_Recipe.md | 0.294 | 2 | -2.04 | 0.25 | no |
| IMG_1042_Whiteboard_Sprint.jpg | 0.302 | 1 | - | 0.00 | no |
| Notes_Dentist_Appointment.txt | 0.224 | 3 | - | 0.00 | no |
| IMG_1045_Gym_Timetable.jpg | 0.218 | 4 | - | 0.00 | no |
| IMG_1046_Mobile_Bill_Sept.jpg | 0.213 | 5 | - | 0.00 | no |

3-warranty: Where is the warranty bill for my laptop? | embed model embeddinggemma-300M-Q8_0 | denseMinCosine 0.40 | chunks 11 | cosine over all chunks min 0.179 median 0.313 p90 0.359 max 0.608

| Title | Cosine | Dense rank | BM25 | Coverage | Passes |
|---|---|---|---|---|---|
| Laptop_Extended_Warranty_Certificate.pdf | 0.608 | 1 | -7.23 | 1.00 | yes |
| IMG_1046_Mobile_Bill_Sept.jpg | 0.343 | 3 | -2.31 | 0.33 | no |
| IMG_1042_Whiteboard_Sprint.jpg | 0.359 | 2 | - | 0.00 | no |
| Society_Maintenance_Notice_Oct2026.pdf | 0.339 | 4 | - | 0.00 | no |
| Rental_Agreement_Summary.docx | 0.335 | 5 | - | 0.00 | no |

In the calibration block the numbers 1, 2 and 3 in front of a question stand for Phone B, Phone C and Phone A.

## Checks (from `checks.log`)

All PASS except `d-private-default` (finding 4). PRD pair: `d-cite-bill` PASS (IMG_2231.jpg cited as Phone B, local) and `d-cite-certificate` PASS (Laptop_Extended_Warranty_Certificate.pdf cited from Phone A) in the first warranty ask and again in the Hindi ask. `d-cite-peer` PASS (IMG_3301_Electricity_Bill.jpg from Phone C). `d-unrelated` PASS (no sources). `d-withheld` PASS (asked from Phone C, Phone B withheld 1 and no salary source arrived). `p-withheld` PASS. `d-incomplete-electricity` PASS (flagged incomplete, Phone C reported unreachable).

## Time to first token and total, first-time questions (the number the GPU should improve)

First-time = a question that phone had not been asked before (the electricity question on Phone B was first asked in an aborted first attempt of this step at 08:23, 5774 ms / 8911 ms with 5 sources, file `aborted-d1/pdu-ask-pre-share-electricity.json`; the 561 ms / 3643 ms in the table is its repeat and a prompt-cache hit, inference).

| First-time ask | Asked on | Sources shown | Time to first token ms | Total ms |
|---|---|---|---|---|
| electricity (first attempt) | B | 5 | 5774 | 8911 |
| restaurant total | C | 4 | 3747 | 5156 |
| warranty bill (PRD pair) | B | 4 | 5336 | 11451 |
| warranty bill in Hindi | B | 3 | 4191 | 12912 |
| milkman note in Hindi | B | 1 | 1433 | 2737 |
| restaurant total | B | 4 | 4773 | 6585 |
| yoga class | B | 6 | 4991 | 9281 |
| cab ride | B | 1 | 1405 | 3634 |
| salary (from the peer) | C | 4 | 2565 | 4733 |

Median 4191 ms to first token (range 1405 to 5774) and 6585 ms total (range 2737 to 12912), n = 9. The first run on the CPU build gave 3.2 to 12.4 s to first token and 4.7 to 16.1 s total over its first-time questions (`2026-10-10-e2e-data-union.md`). The same question on the two builds: electricity 5.8 s to first token on GPU against 12.4 s on CPU, salary 2.6 s against 11.8 s. The PRD R4.4 targets (2 s to first visible text, 6 s full answer) are met by 2 of the 9 on first token (both have a single source) and by 4 of the 9 on total. Repeats of an identical question: 554 and 561 ms to first token, 5.3 s and 3.6 s total.

## Deviations, failures and anything that looked wrong

1. **The GPU helps prefill, not the generation of long answers.** The slowest totals are the Hindi answers (12.9 s) and the 4-source English answers (9 to 11 s); the retrieval part is small (embedding 23 to 50 ms, local search 1 to 7 ms, slowest peer round trip 71 to 116 ms).
2. **Sources shown that the answer does not use.** "When is the yoga class?" listed six sources; only Yoga_Class_Schedule.txt answers it, the other five (Meeting_Notes_Q4_Planning.txt, IMG_1045_Gym_Timetable.jpg, IMG_3302_Hindi_Note.jpg, Notes_Dentist_Appointment.txt, Weekend_Trek_Packing_List.txt) do not, and the answer cites only [1]. The electricity ask listed five. With the peer app stopped, the electricity ask shows four unrelated sources and the answer says "I found nothing relevant", with no word that Phone C was missing; the report carries `incomplete: true`, whether the screen says so was not looked at.
3. **The warranty answers describe the bill but do not name the phone or file in words.** "The warranty bill for your laptop is located in source [1], titled WARRANTY BILL - LAPTOP ... Source [2] also references this purchase for an extended warranty certificate": attribution lives in the citation numbers and the source list (Phone B: IMG_2231.jpg first, Phone A: the certificate second), correct here. The Hindi answer names the certificate on Phone A as the "bill" file and does not name the bill photo on Phone B; a partly wrong answer. The bill photo showed up as source 1 in both asks.
4. **"Private by default" could not be re-tested.** The sharing choice is stored in the database and survived the re-index: before this run Phone C and Phone A had both folders shared (from my first run), Phone B had its two demo folders shared and only its `Private` folder private (1 of 3). `d-private-default` therefore FAILS in the log: Phone B showed its files to Phone C because they were already shared, not because the default is wrong. Instead the policy was tested the other way round: with `Pictures/SwarmDemo` of Phone B set private (debug route), Phone C asked for the restaurant bill and got withheld 3, no file of B, and a pharmacy receipt of its own as the only source (`p-withheld`); then B's folder was shared again. In the salary ask from C, Phone B withheld 1 (its Private folder, a salary slip) and the answer says nothing relevant, with four unrelated sources listed.
5. **Phone B's own private file appears in its own results** as "(private file on this phone)" (fourth source of the warranty ask, no title or text), as designed for the owner.
6. **Calibration (POST /v1/pdu/debug/scores, floor reported as 0.40, not changed).** Top cosine of the relevant hit: 0.608 (English warranty, A, certificate), 0.601 (English warranty, B, bill photo), 0.586 and 0.552 (Hindi warranty, B and A); weaker relevant hits 0.507 and 0.472 (B, the washing-machine warranty card). Highest cosine of anything not relevant, per question and phone: 0.302 to 0.386 (0.386 on C for the English warranty question, 0.375 on A for the Hindi one, 0.302 to 0.317 for the unrelated question). So 0.40 sits 0.014 above the best irrelevant hit and 0.072 below the weakest relevant one: it separates these nine cases, with a thin margin on the low side. BM25 let one private chunk through at coverage 0.67 on B (shown as "(private file)").
7. **My own mistakes in this step.** The first start of the step was stopped after 3 minutes to correct the private-default test (its results are kept in `aborted-d1/`); that is why the electricity ask above is a repeat. A one-minute typo in my helper script `e2e2.py` (fixed at once) broke four helper calls during the Knowledge Bank step, see that note.
8. Index runs took 0.4 to 1.8 s per folder, OCR 74 to 110 ms per image, on this build (embedding does not use the GPU).
9. **Airplane mode: no observation.** It needs a tap on the screen (quick settings) or a radio toggle over adb; I did neither, so there is no measurement of zero WAN bytes here.
