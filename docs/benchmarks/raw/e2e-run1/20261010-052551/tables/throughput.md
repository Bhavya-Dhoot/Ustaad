| Run | Mode | Thermal at start A / C | Peak thermal A / C | Wall-clock s | Items done / failed | Total tokens | Aggregate tokens/s | Model | Fake |
|---|---|---|---|---|---|---|---|---|---|
| local-1 | local | 1 / - | 2 / - | 116.4 | 32 / 0 | 916 | 7.87 | Qwen3.5-4B-Q4_K_M | no |
| local-2 | local | 1 / - | 2 / - | 122.9 | 32 / 0 | 916 | 7.45 | Qwen3.5-4B-Q4_K_M | no |
| swarm-1 | swarm | 1 / 0 | 2 / 1 | 44.8 | 32 / 0 | 916 | 20.45 | Qwen3.5-4B-Q4_K_M | no |
| swarm-2 | swarm | 1 / 0 | 2 / 0 | 88.8 | 32 / 0 | 916 | 10.32 | Qwen3.5-4B-Q4_K_M | no |

| Run | Phone | Jobs | Tokens | Share of tokens | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |
|---|---|---|---|---|---|---|
| local-1 | Phone A | 32 | 916 | 100 % | 7.87 | 10.24 |
| local-2 | Phone A | 32 | 916 | 100 % | 7.45 | 9.17 |
| swarm-1 | Phone C | 20 | 549 | 60 % | 12.26 | 15.85 |
| swarm-1 | Phone A | 12 | 367 | 40 % | 8.19 | 10.42 |
| swarm-2 | Phone C | 12 | 336 | 37 % | 3.78 | 16.00 |
| swarm-2 | Phone A | 20 | 580 | 63 % | 6.53 | 10.97 |

Local mean 7.66 tokens/s over 2 run(s) (7.87, 7.45); swarm mean 15.38 tokens/s over 2 run(s) (20.45, 10.32); ratio swarm / local 2.01.
