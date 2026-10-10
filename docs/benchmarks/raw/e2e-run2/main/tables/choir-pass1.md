Model: Qwen3.5-4B-Q4_K_M; phones in the run: Phone A, Phone B, Phone C; check against the problem's own test: false; problems run: n = 20 of 20; rows with an error: 0.

| Arm | n | Correct | Accuracy | Mean wall-clock per problem (s) | Invalid answers |
|---|---|---|---|---|---|
| One phone alone (coordinator's own first answer) | 20 | 14 | 70 % | 11.4 | 1 |
| One phone, three samples, majority | 20 | 18 | 90 % | 32.3 | 2 |
| Choir, all phones, no tool check | 20 | 16 | 80 % | 20.1 | 0 |
| Choir, all phones, with tool check | 20 | 16 | 80 % | 20.1 (same run) | 0 |

Tool check as the app defines it: verified 11 of 20 (against the problem's own test: 0; only consistent with the models' written arithmetic: 11); abstained 0; mean rounds 1.55; rows where fewer phones answered than were asked: 1.
Invalid answers = no answer, or one that is not a number (MATH), an option letter (MCQ) or a short output (CODE); sample failures count as invalid. Total over all four arms: 3.

| Kind | n | One phone | Three samples | Choir | Choir + tool |
|---|---|---|---|---|---|
| MATH | 12 | 10 | 11 of 12 | 10 | 10 |
| MCQ | 4 | 3 | 4 of 4 | 3 | 3 |
| CODE | 4 | 1 | 3 of 4 | 3 | 3 |

| Slice | Problems | Thermal at start | Peak thermal | Wall (s) | HTTP | Rows | Note |
|---|---|---|---|---|---|---|---|
| 1 | m1,p1,q1,c1,m2,p2,q2,c2,m3,p3 | Phone B=1 Phone C=0 Phone A=1 | 2 0 1 | 550 | 200 | 10 |  |
| 2 | q3,c3,m4,p4,q4,c4,m5,m6,m7,m8 | Phone B=1 Phone C=0 Phone A=0 | 3 0 1 | 513 | 200 | 10 |  |

| Problem | Kind | Expected | One phone | 3 samples | Choir | Choir + tool | Verified | Rounds | Choir s | Phones answered |
|---|---|---|---|---|---|---|---|---|---|---|
| c1 | CODE | 37 | 50 (wrong) | 37 (ok) | 37 (ok) | 37 (ok) | no | 2 | 32.3 | 3/3 |
| c2 | CODE | 4 13 | 4 13 (ok) | 3 8 (wrong) | 4 13 (ok) | 4 13 (ok) | no | 2 | 29.4 | 3/3 |
| c3 | CODE | mra | mrw (wrong) | mra (ok) | mrw (wrong) | mrw (wrong) | no | 2 | 21.2 | 3/3 |
| c4 | CODE | 105 | 106 (wrong) | 105 (ok) | 105 (ok) | 105 (ok) | no | 2 | 31.0 | 3/3 |
| m1 | MATH | 3654 | 3843 (wrong) | 3654 (ok) | 3654 (ok) | 3654 (ok) | consistent | 2 | 28.4 | 3/3 |
| m2 | MATH | 384 | 396 (wrong) | 384 (ok) | 360 (wrong) | 648 (wrong) | consistent | 2 | 25.6 | 3/3 |
| m3 | MATH | 164.5 | 164.5 (ok) | 163 (wrong) | 164.5 (ok) | 164.5 (ok) | consistent | 2 | 26.0 | 3/3 |
| m4 | MATH | 80 | 80 (ok) | 80 (ok) | 80 (ok) | 80 (ok) | consistent | 1 | 12.5 | 3/3 |
| m5 | MATH | 6 | 6 (ok) | 6 (ok) | 6 (ok) | 6 (ok) | consistent | 2 | 24.0 | 3/3 |
| m6 | MATH | 48 | 48 (ok) | 48 (ok) | 48 (ok) | 48 (ok) | consistent | 1 | 12.0 | 3/3 |
| m7 | MATH | 300 | 300 (ok) | 300 (ok) | 300 (ok) | 300 (ok) | consistent | 1 | 13.6 | 3/3 |
| m8 | MATH | 486 | 486.0000000000000644 (ok) | 486 (ok) | 486 (ok) | 486 (ok) | consistent | 2 | 24.9 | 3/3 |
| p1 | MATH | 800 | 800 (ok) | 800 (ok) | 800 (ok) | 800 (ok) | consistent | 1 | 7.2 | 3/3 |
| p2 | MATH | 240 | 240 (ok) | 240 (ok) | 120 (wrong) | 120 (wrong) | no | 2 | 30.8 | 3/3 |
| p3 | MATH | 80000 | 79999.9999999999999999 (ok) | 80000 (ok) | 80000 (ok) | 80000 (ok) | consistent | 1 | 10.8 | 3/3 |
| p4 | MATH | 30 | 30 (ok) | 30 (ok) | 30 (ok) | 30 (ok) | consistent | 1 | 11.5 | 3/3 |
| q1 | MCQ | A | A (ok) | A (ok) | A (ok) | A (ok) | no | 1 | 13.3 | 3/3 |
| q2 | MCQ | C | C (ok) | C (ok) | C (ok) | C (ok) | no | 2 | 23.5 | 3/3 |
| q3 | MCQ | D | D (ok) | D (ok) | D (ok) | D (ok) | no | 1 | 12.0 | 3/3 |
| q4 | MCQ | B | none | B (ok) | C (wrong) | C (wrong) | no | 1 | 11.0 | 2/3 |
