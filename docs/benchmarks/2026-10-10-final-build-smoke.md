# Smoke test of the final build on three phones (2026-10-10, 10:27 to 10:38)

One run each, on the debug build of master 0a71f33 (`com.swarmos.app`, built 10:25 with 699 unit tests passing, installed with `adb install -r` on phones A, B and C at 10:27, no `pm clear`). Chat `Qwen3.5-4B-Q4_K_M` on the GPU and embedder `embeddinggemma-300M-Q8_0` READY on all three; each phone listed the other two online; Thermal Status 0 on all three at every read, including after the batch. Shared Wi-Fi through the access point, no Wi-Fi Direct group. Raw responses: `raw/final-build-smoke/ask-b.json` and `raw/final-build-smoke/batch-final.json`. Screenshots of the same session: `docs/design/qa3/`.

| Check | Result |
|---|---|
| `POST /v1/pdu/ask` on Phone B, "Where is the warranty bill for my laptop?", saved answers off | Sources: Phone B IMG_2231.jpg, Phone A Laptop_Extended_Warranty_Certificate.pdf, Phone B IMG_2238_Washing_Machine_Warranty_Card.jpg, Phone B "(private file on this phone)". Embedding 34 ms, local search 8 ms, first token 4015 ms, total 8687 ms, not incomplete |
| `POST /v1/sched/batch` bench32, swarm mode, posted to Phone B | 32 of 32 done, 0 failed, 896 tokens in 22.61 s: 39.62 tokens/s aggregate. Jobs B 11, C 11, A 10. Decode on each phone 18.2 to 18.3 tokens/s |
| Ask on Phone C with Phone A's app stopped | answer flagged with "Phone A did not answer"; first word 3082 ms, total 6288 ms |
| Shared card saved on Phone B | listed on Phone C within about 2 s (one poll interval), then revoked |

Reading: this run is faster than the five three-phone runs of `2026-10-10-e2e2-swarm-throughput.md` (28.9 to 31.9 tokens/s, decode 12.9 to 14.2 per phone). All three phones were at Thermal Status 0 here and at 0 to 2 there, and the builds differ; the cause was not isolated and no single-phone baseline was taken in this session, so this number has no "times one phone" figure and does not replace the earlier mean. It is one run.
