Model: Qwen3.5-4B-Q4_K_M; nodes: Phone A, Phone C; check against the problem's test: false; problems run: n = 5 of 20; rows with an error: 0.

| Arm | n | Correct | Accuracy | Mean wall-clock per problem (ms) |
|---|---|---|---|---|
| One phone alone (coordinator's own first answer) | 5 | 2 | 40 % | 14582 |
| One phone, three samples, majority | 5 | 4 | 80 % | 41069 |
| Choir, two phones, no tool check | 5 | 4 | 80 % | 28551 |
| Choir, two phones, with tool check | 5 | 4 | 80 % | 28551 (same run) |

Tool check as the app defines it: verified 1 of 5 (verified against the problem's own test: 0; only consistent with the models' written arithmetic: 1); abstained 0; mean rounds 1.80.

| Kind | n | One phone | Three samples | Choir | Choir + tool |
|---|---|---|---|---|---|
| MATH | 3 | 0 | 2 of 3 | 2 | 2 |
| MCQ | 1 | 1 | 1 of 1 | 1 | 1 |
| CODE | 1 | 1 | 1 of 1 | 1 | 1 |

| Slice | Problems | Thermal at start A / C | Peak thermal A / C | Wall (s) | HTTP | Rows | Note |
|---|---|---|---|---|---|---|---|
| 1 | m1,p1,q1,c1,m2 | 1 / 0 | 3 / 1 | 356 | 200 | 5 |  |

| Problem | Kind | Expected | One phone | 3 samples | Choir | Choir + tool | Verified | Rounds | Choir ms | Nodes answered | Error |
|---|---|---|---|---|---|---|---|---|---|---|---|
| c1 | CODE | 37 | 37 (ok) | 37 (ok) | 37 (ok) | 37 (ok) | no | 1 | 16527 | 2/2 |  |
| m1 | MATH | 3654 | And 126 * 9 = 1134 (wrong) | 3234 (wrong) | And 126 * 9 = 1134 (wrong) | And 126 * 9 = 1134 (wrong) | no | 1 | 11607 | 1/2 |  |
| m2 | MATH | 384 | {264+120} (wrong) | 384 (ok) | 384 (ok) | 384 (ok) | consistent | 3 | 48148 | 2/2 |  |
| p1 | MATH | 800 | When price is raised 25% then 20% discount, final price is s (wrong) | 800 (ok) | {800 * 1.25 * 0.80} (ok) | {800 * 1.25 * 0.80} (ok) | no | 3 | 49116 | 2/2 |  |
| q1 | MCQ | A | A (ok) | A (ok) | A (ok) | A (ok) | no | 1 | 17359 | 2/2 |  |
