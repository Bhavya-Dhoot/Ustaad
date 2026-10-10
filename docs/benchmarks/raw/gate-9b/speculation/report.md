
### data-union

| Pass | Setting | Thermal before / after | Prompts | Decode tok/s median (min-max) | Tokens drafted | Accepted | Same text as first baseline |
|---|---|---|---|---|---|---|---|
| 1 | base: no speculation | 0 / 1 | 6 of 6 | 13.34 (13.22-13.46) | none | none | 6 of 6 |
| 2 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 6 of 6 | 7.57 (5.57-8.94) | 160 | 46 (29 %) | 5 of 6 |
| 3 | base: no speculation | 1 / 1 | 6 of 6 | 11.66 (11.45-11.85) | none | none | 6 of 6 |
| 4 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 6 of 6 | 7.64 (5.50-8.93) | 160 | 46 (29 %) | 5 of 6 |
| 5 | mod: --spec-type ngram-mod | 1 / 1 | 6 of 6 | 11.60 (11.41-11.91) | none | none | 6 of 6 |
| 6 | base: no speculation | 1 / 2 | 6 of 6 | 11.54 (11.32-11.80) | none | none | 6 of 6 |
| 7 | simple12: --spec-type ngram-simple | 1 / 2 | 6 of 6 | 11.54 (8.34-11.75) | 48 | 4 (8 %) | 6 of 6 |
| 8 | base: no speculation | 1 / 2 | 6 of 6 | 12.65 (12.60-12.91) | none | none | 6 of 6 |

### choir

| Pass | Setting | Thermal before / after | Prompts | Decode tok/s median (min-max) | Tokens drafted | Accepted | Same text as first baseline |
|---|---|---|---|---|---|---|---|
| 1 | base: no speculation | 0 / 1 | 3 of 3 | 13.17 (13.02-13.72) | none | none | 3 of 3 |
| 2 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 3 of 3 | 8.88 (6.73-9.06) | 147 | 28 (19 %) | 3 of 3 |
| 3 | base: no speculation | 1 / 1 | 3 of 3 | 11.90 (11.89-11.91) | none | none | 3 of 3 |
| 4 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 3 of 3 | 8.94 (6.78-9.07) | 147 | 28 (19 %) | 3 of 3 |
| 5 | mod: --spec-type ngram-mod | 1 / 1 | 3 of 3 | 11.98 (11.84-12.13) | none | none | 3 of 3 |
| 6 | base: no speculation | 1 / 2 | 3 of 3 | 11.96 (11.90-11.98) | none | none | 3 of 3 |
| 7 | simple12: --spec-type ngram-simple | 1 / 2 | 3 of 3 | 11.97 (10.88-12.11) | 48 | 4 (8 %) | 2 of 3 |
| 8 | base: no speculation | 1 / 2 | 3 of 3 | 13.12 (13.02-13.15) | none | none | 3 of 3 |

### plain

| Pass | Setting | Thermal before / after | Prompts | Decode tok/s median (min-max) | Tokens drafted | Accepted | Same text as first baseline |
|---|---|---|---|---|---|---|---|
| 1 | base: no speculation | 0 / 1 | 3 of 3 | 12.96 (12.85-13.07) | none | none | 3 of 3 |
| 2 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 3 of 3 | 11.98 (11.65-12.07) | none | none | 3 of 3 |
| 3 | base: no speculation | 1 / 1 | 3 of 3 | 11.86 (11.77-12.25) | none | none | 3 of 3 |
| 4 | simple: --spec-type ngram-simple --spec-ngram-simple-size-n 4 --spec-ngram-simple-size-m 8 | 1 / 1 | 3 of 3 | 11.76 (11.73-12.13) | none | none | 3 of 3 |
| 5 | mod: --spec-type ngram-mod | 1 / 1 | 3 of 3 | 11.86 (11.75-11.87) | none | none | 3 of 3 |
| 6 | base: no speculation | 1 / 2 | 3 of 3 | 11.73 (11.62-11.90) | none | none | 3 of 3 |
| 7 | simple12: --spec-type ngram-simple | 1 / 2 | 3 of 3 | 11.75 (11.51-12.04) | none | none | 3 of 3 |
| 8 | base: no speculation | 1 / 2 | 3 of 3 | 12.91 (12.89-13.10) | none | none | 3 of 3 |

### per prompt, decode tok/s by pass (drafted/accepted in brackets)

| Prompt | 1 base | 2 simple | 3 base | 4 simple | 5 mod | 6 base | 7 simple12 | 8 base |
|---|---|---|---|---|---|---|---|---|
| du1 | 13.25 same | 8.94 (drafted 24, accepted 14) same | 11.66 same | 8.15 (drafted 24, accepted 14) same | 11.41 same | 11.32 same | 11.45 same | 12.60 same |
| du2 | 13.22 same | 7.33 (drafted 24, accepted 3) same | 11.50 same | 7.43 (drafted 24, accepted 3) same | 11.55 same | 11.48 same | 11.69 same | 12.60 same |
| du3 | 13.46 same | 7.81 (drafted 32, accepted 10) same | 11.85 same | 7.83 (drafted 32, accepted 10) same | 11.91 same | 11.60 same | 11.75 same | 12.91 same |
| du4 | 13.26 same | 7.28 (drafted 24, accepted 5) same | 11.45 same | 7.44 (drafted 24, accepted 5) same | 11.46 same | 11.39 same | 11.45 same | 12.62 same |
| du5 | 13.42 same | 5.57 (drafted 32, accepted 3) DIFFERENT | 11.70 same | 5.50 (drafted 32, accepted 3) DIFFERENT | 11.70 same | 11.62 same | 11.62 same | 12.68 same |
| du6 | 13.45 same | 8.53 (drafted 24, accepted 11) same | 11.66 same | 8.93 (drafted 24, accepted 11) same | 11.65 same | 11.80 same | 8.34 (drafted 48, accepted 4) same | 12.67 same |
| ch1 | 13.72 same | 9.06 (drafted 40, accepted 8) same | 11.91 same | 9.07 (drafted 40, accepted 8) same | 11.84 same | 11.96 same | 12.11 same | 13.12 same |
| ch2 | 13.17 same | 8.88 (drafted 43, accepted 13) same | 11.90 same | 8.94 (drafted 43, accepted 13) same | 12.13 same | 11.98 same | 10.88 (drafted 48, accepted 4) DIFFERENT | 13.15 same |
| ch3 | 13.02 same | 6.73 (drafted 64, accepted 7) same | 11.89 same | 6.78 (drafted 64, accepted 7) same | 11.98 same | 11.90 same | 11.97 same | 13.02 same |
| pl0 | 12.96 same | 11.98 same | 12.25 same | 12.13 same | 11.87 same | 11.73 same | 11.51 same | 12.91 same |
| pl2 | 12.85 same | 11.65 same | 11.77 same | 11.73 same | 11.75 same | 11.90 same | 11.75 same | 12.89 same |
| pl4 | 13.07 same | 12.07 same | 11.86 same | 11.76 same | 11.86 same | 11.62 same | 12.04 same | 13.10 same |
