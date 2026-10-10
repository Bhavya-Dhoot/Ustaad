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
