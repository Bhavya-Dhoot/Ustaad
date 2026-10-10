Model: Qwen3.5-4B-Q4_K_M; phones in the run: Phone A, Phone B, Phone C; check against the problem's own test: true; problems run: n = 20 of 20; rows with an error: 0.

| Arm | n | Correct | Accuracy | Mean wall-clock per problem (s) | Invalid answers |
|---|---|---|---|---|---|
| One phone alone (coordinator's own first answer) | 20 | 11 | 55 % | 12.8 | 2 |
| One phone, three samples, majority | 0 | 0 | - | 0.0 | 0 |
| Choir, all phones, no tool check | 20 | 14 | 70 % | 23.4 | 0 |
| Choir, all phones, with tool check | 20 | 15 | 75 % | 23.4 (same run) | 0 |

Tool check as the app defines it: verified 15 of 20 (against the problem's own test: 15; only consistent with the models' written arithmetic: 0); abstained 0; mean rounds 1.70; rows where fewer phones answered than were asked: 2.
Invalid answers = no answer, or one that is not a number (MATH), an option letter (MCQ) or a short output (CODE); sample failures count as invalid. Total over all four arms: 2.

| Kind | n | One phone | Three samples | Choir | Choir + tool |
|---|---|---|---|---|---|
| MATH | 12 | 6 | 0 of 0 | 8 | 8 |
| MCQ | 4 | 4 | 0 of 0 | 4 | 4 |
| CODE | 4 | 1 | 0 of 0 | 2 | 3 |

| Slice | Problems | Thermal at start | Peak thermal | Wall (s) | HTTP | Rows | Note |
|---|---|---|---|---|---|---|---|
| 1 | m1,p1,q1,c1,m2,p2,q2,c2,m3,p3,q3,c3,m4,p4,q4,c4,m5,m6,m7,m8 | Phone B=1 Phone C=0 Phone A=0 | 2 2 2 | 474 | 200 | 20 |  |

| Problem | Kind | Expected | One phone | 3 samples | Choir | Choir + tool | Verified | Rounds | Choir s | Phones answered |
|---|---|---|---|---|---|---|---|---|---|---|
| c1 | CODE | 37 | ,confidence": 1.0 (wrong) | not run | 37 (ok) | 37 (ok) | test | 3 | 39.5 | 3/3 |
| c2 | CODE | 4 13 | 4 13 (ok) | not run | 4 13 (ok) | 4 13 (ok) | test | 2 | 33.9 | 3/3 |
| c3 | CODE | mra | mrw (wrong) | not run | mrw (wrong) | mra (ok) | test | 2 | 34.2 | 3/3 |
| c4 | CODE | 105 | none | not run | 210 (wrong) | 210 (wrong) | no | 1 | 18.0 | 2/3 |
| m1 | MATH | 3654 | none | not run | 3780 (wrong) | 3780 (wrong) | no | 2 | 36.9 | 2/3 |
| m2 | MATH | 384 | 648 (wrong) | not run | 648 (wrong) | 648 (wrong) | no | 2 | 28.7 | 3/3 |
| m3 | MATH | 164.5 | 164.5 (ok) | not run | 164.5 (ok) | 164.5 (ok) | test | 2 | 26.9 | 3/3 |
| m4 | MATH | 80 | 80 (ok) | not run | 80 (ok) | 80 (ok) | test | 2 | 20.4 | 3/3 |
| m5 | MATH | 6 | 6 (ok) | not run | 6 (ok) | 6 (ok) | test | 1 | 11.2 | 3/3 |
| m6 | MATH | 48 | 6 (wrong) | not run | 48 (ok) | 48 (ok) | test | 2 | 26.8 | 3/3 |
| m7 | MATH | 300 | 6 (wrong) | not run | 6 (wrong) | 6 (wrong) | no | 1 | 9.8 | 3/3 |
| m8 | MATH | 486 | 486 (ok) | not run | 486 (ok) | 486 (ok) | test | 1 | 10.7 | 3/3 |
| p1 | MATH | 800 | 800 (ok) | not run | 800 (ok) | 800 (ok) | test | 1 | 12.9 | 3/3 |
| p2 | MATH | 240 | 120 (wrong) | not run | -330 (wrong) | -330 (wrong) | no | 2 | 25.8 | 3/3 |
| p3 | MATH | 80000 | 76128 (wrong) | not run | 80000 (ok) | 80000 (ok) | test | 2 | 25.1 | 3/3 |
| p4 | MATH | 30 | 30 (ok) | not run | 30 (ok) | 30 (ok) | test | 2 | 20.2 | 3/3 |
| q1 | MCQ | A | A (ok) | not run | A (ok) | A (ok) | test | 1 | 13.5 | 3/3 |
| q2 | MCQ | C | C (ok) | not run | C (ok) | C (ok) | test | 2 | 31.2 | 3/3 |
| q3 | MCQ | D | D (ok) | not run | D (ok) | D (ok) | test | 1 | 11.7 | 3/3 |
| q4 | MCQ | B | B (ok) | not run | B (ok) | B (ok) | test | 2 | 30.3 | 3/3 |
