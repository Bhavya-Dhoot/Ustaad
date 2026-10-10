# Agent Choir answer quality: cause, fix, and a 20-problem check on the phones

Date: 2026-10-10. Model: Qwen3.5-4B Q4_K_M, thinking off, JSON-schema constrained decoding. Phones: iQOO A (build 16.0.24.1, chat model on CPU) and iQOO B (chat model on GPU, `roles.chat.backend` = `GPU`). Every number below comes from a run on those phones with the debug build of this branch. The 20 eval rows exactly as the phones returned them (plus a `phone` field) are in `docs/benchmarks/2026-10-10-choir-answer-quality-rows.json`; the slice files also stay on the phones (`choir-eval-*.json` in the app files directory).

## 1. What went wrong

The end-to-end run (docs/benchmarks/2026-10-10-e2e-choir.md) stored these answers for the Choir arms:

| problem | expected | stored answer | where |
|---|---|---|---|
| m1 | 3654 | `And 126 * 9 = 1134` | one phone alone |
| p1 | 800 | `When price is raised 25% then 20% discount, final price is s` (cut at 60 characters) | one phone alone |
| p1 | 800 | `{800 * 1.25 * 0.80}` | choir pick, scored correct |
| m2 | 384 | `{264+120}` | one phone alone |
| m3 | 164.5 | `{155.5}` | alone, choir and three samples |
| p2 | 240 | `{240}` | choir pick, scored correct |
| p2 | 240 | `}` | three-sample pick |
| m4 | 80 | no answer (alone), `,` (choir), `:` (three samples) | |
| m8 | 486 | `After calculating base cost plus extra days cost, total is 4` (round 1), `,` (round 2) | |
| any | | phone C round 1 answer `math`, confidence 0.0 | |

### Evidence from the model itself

I sent the exact solve request (same system prompt, same schema, same 300 token cap) straight to the llama-server on phone A and read the raw reply. Four of them, copied from the server:

```
m3  {"key_steps": ["3 notebooks at 45 each = 135", "2 pens at 18 each = 36", "Total cost = 171, Change = 329, Half spent = 164.5, Left = 164.5"], "answer": "", ...}
p2  {"key_steps": ["Sugar is 360 ml, water is 600 ml.", "Add x ml water: 360/(600+x) = 3/7.", "Solve for x: x = 120."], "answer": "}", ...}
m1  {"key_steps": ["Total muffins = 48 * 7 = 336", "Sold at Rs 12 = 336 * 5/8 = 210", "Sold at Rs 9 = 336 * 3/8 = 126, Total = 210*12 + 126*9 = 2520 + 1134 = 3654"], "answer": "}", ...}
p1  {"key_steps": ["800 * 1.25 = 1000", "1000 * 0.8 = 800", "800"], "answer": "", ...}
```

(m4 was the same shape: `"answer": ""` after a last step that reads `1200 / 15 = 80 minutes.`)

### Verdict on the four suspects

| suspect | verdict | evidence |
|---|---|---|
| Truncation by the token cap | No | Every raw reply ended by itself (`finish_reason` = `stop`) after 54 to 118 tokens of the 300 allowed. The JSON is complete and closed. |
| A schema that lets `answer` be any string | Yes, the main cause | `answer` was `{"type":"string"}`. After finishing its reasoning in `key_steps` the model emitted `""`, `"}"`, `":"` or `","` there, even though the right number was in the last step. The grammar allowed all of them. |
| A confusing revise prompt | Yes, it made it worse | Round two pasted the other lanes' replies as raw JSON, including their degenerate `"answer": "}"`. A lane then saw `}` as a rival answer and sometimes copied it (m4 round two: `,`). |
| Extraction / normaliser picking the wrong piece | Yes, in the scorer | `Normalizer.parseNumber` took the first number found anywhere in the string, so `{240}` and `{800 * 1.25 * 0.80}` counted as correct (800 happens to be the answer to p1 by luck of the first number). A 60 character cap on the stored answer (`fitWire`) cut prose answers mid-word. |

## 2. The fix

All in `app/app/src/main/java/com/swarmos/app/choir/`.

1. **The answer is typed per kind in the schema** (`ChoirPrompts.solveSchema`). MATH: `{"type":"number"}`. MCQ: an enum of the option letters that appear in the problem text (A to D when none are shown). CODE and TEXT: a string with `minLength` 1 and `maxLength` 60. The model cannot write `}` where a number is required.
2. **Validation after parsing** (`AnswerCheck`). Empty or punctuation-only answers are invalid for every kind; MATH needs a strict number; MCQ needs a strict option letter; CODE and TEXT need an alphanumeric character. A CODE answer with braces is also invalid when the program itself contains no braces (found on phone B, see section 4).
3. **Retry once, then exclude** (`ChoirNode.generate`). An invalid answer triggers one more call with a shorter instruction (one short key step, "answer, never empty") inside the remaining time budget. The tokens of both calls are counted. If that is also invalid the lane returns the error `no valid answer`, the coordinator marks the lane `NO_VALID_ANSWER` ("No answer" on screen), and it is left out of the vote and out of what the other lanes are shown.
4. **Room for the answer.** Not truncation, so `answer` stays after `key_steps` (a short reasoning step first is what makes the number right). Each key step is capped by the schema at 100 characters and at most 3 steps, so the reply cannot ramble. This is a bound on what the model may write, not a mathematical guarantee that 300 tokens always suffice; if a reply were cut, the answer would fail validation and the retry above takes over.
5. **A strict scorer** (`Normalizer.parseNumberStrict`, `mcqLetterStrict`). A number may carry a currency prefix, thousands commas, a simple fraction, and at most one trailing unit word or a percent sign. Braces, expressions, `x = 12`, two numbers and sentences are not numbers. An MCQ answer is a letter, optionally followed by `)`, `.` or `:` and text. `ToolChecker` uses the same functions. The lenient readers stay only for reading the model's own steps.
6. **Clean values in the revise round** (`ChoirPrompts.revise`). Each lane sees `Answer: 3654` and, indented, `Steps: ...`, never JSON.
7. Eval rows now carry the raw answer, the normalised answer, why a lane had no answer, and the individual sample answers, so a run can be audited. The eval route takes `"peers": false` to keep a run on the phone that receives it.

Unit tests (`AnswerQualityTest`, 17 tests) use the exact bad outputs above: `{240}`, `{155.5}`, `{264+120}`, `{800 * 1.25 * 0.80}`, `,`, `:`, `}`, `none`, `math`, `And 126 * 9 = 1134`, the "total is 4" sentence, and the raw `"answer": ""` and `"answer": "}"` replies for the retry. The full suite passes: 618 tests, 0 failures.

## 3. How it was checked

- Build: debug base build (`com.swarmos.app`, port 8787), installed with `adb install -r`, launched with `--es name "Phone X" --ez setupDone true`, default models loaded.
- Route: `POST /v1/choir/eval` with `"useProblemCheck": false, "samples": true, "peers": false`, slices of 5 problems, each slice started at thermal status 0 or 1 (waiting for the phone to cool between slices). Arm 1 is "one phone alone" (the coordinator's own round-one answer). Arm 2 is "one phone, three samples" (three sequential solves, majority pick, 45 s budget each). `useProblemCheck` was off, so no answer key reached the tool checker; the key is only used to score.
- Slices 1 to 3 (m1 to q3, 15 problems) ran on phone A (chat model on CPU: the GPU server failed to link and the app fell back, as recorded in `/v1/models/state`). Thermal status went from 0 or 1 at the start to 3 or 4 at the end of each slice.
- The fourth slice (q4, c1 to c4) started on phone A at thermal 1, got no reply for 25 minutes, and phone A then went offline (USB and wireless). I do not know why; I have no log of it and will not guess. By agreement with the coordinator the last 5 problems were run on phone B (chat model on GPU).
- A first pass of that slice on phone B showed the CODE brace problem (section 4). After adding the brace rule the slice was run again; the table below is that second pass.

## 4. Before and after for the failing problems

"Before" is the stored e2e result or the raw reply with the old schema. "After" is this run (one run per problem; the model samples at temperature 0.3 to 0.6, so a second run can differ).

| problem | before | after, one phone alone | after, three samples |
|---|---|---|---|
| m1 (3654) | `And 126 * 9 = 1134` | `3654` correct | `3654`, `1680`, `3654` gives 3654 correct |
| p1 (800) | prose cut at 60 characters; choir `{800 * 1.25 * 0.80}` counted correct | `800` correct | `800`, `800`, `800` correct |
| m2 (384) | `{264+120}` | `384` correct | `384`, `600`, `384` gives 384 correct |
| m3 (164.5) | `{155.5}` everywhere | `329` wrong | `329`, `205`, `157` wrong |
| p2 (240) | alone `200`; choir `{240}` counted correct; samples `}` | `100` wrong | `240`, `800`, `240` gives 240 correct |
| m4 (80) | no answer; `,`; `:` | `80` correct | `80`, `48`, `80` gives 80 correct |
| m8 (486) | round 1 prose "total is 4"; round 2 `,` | `486` correct | `486`, `486`, `486` correct |

m3 and p2-alone are now plain wrong numbers, not format failures. For m3 the model computes the change (329) and answers with it. That is the model's reasoning, and no format fix changes it.

CODE problems on phone B, before the brace rule (first pass of the slice): c1 samples `Oh`, `37`, `49`; c4 samples `525`, `{7 * 5 * 3 * 1}`, `{105}`. The program in c4 has no braces, so `{105}` and `{7 * 5 * 3 * 1}` are a template the model echoed, not output. After the rule those replies are rejected and retried: c1 samples `37`, `37`, `37`; c4 samples `105`, `105`, `105`. A bare `Oh` is not caught by any format rule (a word is a legitimate output for a string program such as c3); it simply was not produced in the second pass.

Degenerate answers left in the 20 problems (empty, punctuation-only, braces around a number, prose): none, in either arm, in the final pass. I checked this on the stored raw answers of all 20 rows and all 60 sample answers.

## 5. The two arms, n = 20

Per problem (raw answer, normalised answer, correct or not, tokens, seconds). Normalised form: `n:` number, `l:` option letter, `c:` code output with spaces removed and lower-cased. Tokens and seconds are for the whole arm on that problem (two model calls at most for "alone" when the retry fires; three solves for the samples arm).

| id | phone | kind | expected | alone: answer | normalised | ok | tokens | s | 3 samples: answers | pick (normalised) | ok | tokens | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| m1 | A (CPU) | MATH | `3654` | `3654` | `n:3654` | yes | 126 | 9.7 | `3654`, `1680`, `3654` | `n:3654` | yes | 318 | 22.4 |
| m2 | A (CPU) | MATH | `384` | `384` | `n:384` | yes | 143 | 10.0 | `384`, `600`, `384` | `n:384` | yes | 384 | 26.0 |
| m3 | A (CPU) | MATH | `164.5` | `329` | `n:329` | NO | 71 | 6.2 | `329`, `205`, `157` | `n:329` | NO | 231 | 17.6 |
| m4 | A (CPU) | MATH | `80` | `80` | `n:80` | yes | 80 | 7.3 | `80`, `48`, `80` | `n:80` | yes | 223 | 17.9 |
| m5 | A (CPU) | MATH | `6` | `6` | `n:6` | yes | 93 | 7.9 | `6`, `9`, `6` | `n:6` | yes | 253 | 19.8 |
| m6 | A (CPU) | MATH | `48` | `12` | `n:12` | NO | 80 | 6.5 | `12`, `48`, `24` | `n:12` | NO | 286 | 20.9 |
| m7 | A (CPU) | MATH | `300` | `300` | `n:300` | yes | 124 | 9.8 | `300`, `270`, `300` | `n:300` | yes | 335 | 25.9 |
| m8 | A (CPU) | MATH | `486` | `486` | `n:486` | yes | 102 | 8.6 | `486`, `486`, `486` | `n:486` | yes | 272 | 21.3 |
| p1 | A (CPU) | MATH | `800` | `800` | `n:800` | yes | 69 | 6.2 | `800`, `800`, `800` | `n:800` | yes | 223 | 19.2 |
| p2 | A (CPU) | MATH | `240` | `100` | `n:100` | NO | 96 | 9.4 | `240`, `800`, `240` | `n:240` | yes | 312 | 27.8 |
| p3 | A (CPU) | MATH | `80000` | `80000` | `n:80000` | yes | 72 | 5.8 | `80000`, `80000`, `80000` | `n:80000` | yes | 278 | 19.7 |
| p4 | A (CPU) | MATH | `30` | `30` | `n:30` | yes | 95 | 7.6 | `30`, `30`, `30` | `n:30` | yes | 247 | 19.8 |
| q1 | A (CPU) | MCQ | `A` | `A` | `l:A` | yes | 84 | 9.9 | `A`, `A`, `A` | `l:A` | yes | 333 | 28.6 |
| q2 | A (CPU) | MCQ | `C` | `C` | `l:C` | yes | 92 | 9.5 | `D`, `C`, `C` | `l:C` | yes | 353 | 38.1 |
| q3 | A (CPU) | MCQ | `D` | `D` | `l:D` | yes | 86 | 9.0 | `D`, `D`, `D` | `l:D` | yes | 323 | 36.6 |
| q4 | B (GPU) | MCQ | `B` | `B` | `l:B` | yes | 123 | 12.3 | `B`, `B`, `B` | `l:B` | yes | 321 | 29.5 |
| c1 | B (GPU) | CODE | `37` | `37` | `c:37` | yes | 101 | 9.8 | `37`, `37`, `37` | `c:37` | yes | 260 | 23.1 |
| c2 | B (GPU) | CODE | `4 13` | `3 8` | `c:38` | NO | 102 | 10.1 | `3 8`, `4 13`, `4 13` | `c:413` | yes | 439 | 45.0 |
| c3 | B (GPU) | CODE | `mra` | `swm` | `c:swm` | NO | 65 | 7.7 | `mra`, `mra`, `mra` | `c:mra` | yes | 208 | 22.3 |
| c4 | B (GPU) | CODE | `105` | `105` | `c:105` | yes | 179 | 21.3 | `105`, `105`, `105` | `c:105` | yes | 374 | 37.6 |

Totals (n = 20, one run per problem, phone A and phone B mixed, so the times are not one machine's):

| arm | correct | MATH (12) | MCQ (4) | CODE (4) | mean seconds | mean tokens |
|---|---|---|---|---|---|---|
| one phone alone | 15 of 20 (75%) | 9 | 4 | 2 | 9.2 | 99 |
| one phone, three samples | 18 of 20 (90%) | 10 | 4 | 4 | 26.0 | 299 |

On the ten problems of the e2e run that were scored then (m1, p1, q1, c1, m2, m3, p2, q2, c2, m4), "alone" went from 4 of 10 to 7 of 10 and "three samples" from 6 of 10 to 9 of 10. Read this as direction only: 10 problems, one run each, and the earlier numbers were scored with the lenient reader (which also credited `{240}` and `{800 * 1.25 * 0.80}`). It is not a measured improvement in the model.

## 6. What this does not show

- The multi-phone Choir arm was not re-run. Both arms here are one phone; the fix to the revise prompt and the lane exclusion are covered by unit tests, not by a new three-phone run.
- I did not record how many calls needed the retry; the eval rows do not carry that. The token counts include retries.
- Remaining wrong answers (m3, m6, p2 alone, c2 alone, c3 alone) are ordinary reasoning errors of a 4B model, answers that are well-formed numbers, letters or output.
- Phone A (CPU) and phone B (GPU) were mixed in one table because phone A went offline; the problems on each phone are labelled.
