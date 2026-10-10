| Run | Mode | Phones | Coordinator | Thermal at start | Peak thermal | Wall-clock s | Done / failed | Tokens | Aggregate tokens/s | Backend |
|---|---|---|---|---|---|---|---|---|---|---|
| single-Phone-B | local | 1 | Phone B | Phone B=0 | Phone B=1 | 88.8 | 32 / 0 | 896 | 10.09 | GPU |
| single-Phone-C | local | 1 | Phone C | Phone C=0 | Phone C=0 | 74.5 | 32 / 0 | 896 | 12.03 | GPU |
| single-Phone-A | local | 1 | Phone A | Phone A=0 | Phone A=1 | 87.1 | 32 / 0 | 896 | 10.29 | GPU |
| swarm3-a | swarm | 3 | Phone B | Phone B=0 Phone C=0 | Phone B=0 Phone C=0 | 29.0 | 32 / 0 | 896 | 30.94 | GPU |
| swarm3-b | swarm | 3 | Phone B | Phone B=0 Phone C=0 | Phone B=0 Phone C=0 | 28.1 | 32 / 0 | 896 | 31.94 | GPU |
| swarm3-c | swarm | 3 | Phone B | Phone B=1 Phone C=0 Phone A=1 | Phone B=1 Phone C=0 Phone A=1 | 28.2 | 32 / 0 | 896 | 31.76 | GPU |
| swarm3-d | swarm | 3 | Phone B | Phone B=1 Phone C=0 Phone A=1 | Phone B=1 Phone C=0 Phone A=2 | 28.9 | 32 / 0 | 896 | 31.00 | GPU |
| swarm2-1 | swarm | 2 | Phone B | Phone B=1 Phone C=0 | Phone B=1 Phone C=0 | 47.6 | 32 / 0 | 896 | 18.83 | GPU |
| swarm2-2 | swarm | 3 | Phone B | Phone B=1 Phone C=0 | Phone B=2 Phone C=0 | 31.0 | 32 / 0 | 896 | 28.90 | GPU |

| Run | Phone | Jobs | Tokens | Share of tokens | Busy s (sum of generation time) | Tokens/s over the batch wall-clock | Decode tokens/s on that phone |
|---|---|---|---|---|---|---|---|
| single-Phone-B | Phone B | 32 | 896 | 100 % | 67.2 | 10.09 | 13.34 |
| single-Phone-C | Phone C | 32 | 896 | 100 % | 56.6 | 12.03 | 15.84 |
| single-Phone-A | Phone A | 32 | 896 | 100 % | 64.8 | 10.29 | 13.83 |
| swarm3-a | Phone B | 11 | 304 | 34 % | 23.2 | 10.50 | 13.10 |
| swarm3-a | Phone C | 11 | 317 | 35 % | 22.4 | 10.95 | 14.13 |
| swarm3-a | Phone A | 10 | 275 | 31 % | 20.6 | 9.50 | 13.38 |
| swarm3-b | Phone B | 10 | 267 | 30 % | 20.8 | 9.52 | 12.86 |
| swarm3-b | Phone C | 12 | 322 | 36 % | 22.8 | 11.48 | 14.15 |
| swarm3-b | Phone A | 10 | 307 | 34 % | 22.9 | 10.94 | 13.41 |
| swarm3-c | Phone B | 10 | 293 | 33 % | 22.6 | 10.39 | 12.98 |
| swarm3-c | Phone C | 12 | 322 | 36 % | 23.0 | 11.41 | 13.99 |
| swarm3-c | Phone A | 10 | 281 | 31 % | 20.9 | 9.96 | 13.43 |
| swarm3-d | Phone B | 11 | 295 | 33 % | 22.9 | 10.21 | 12.91 |
| swarm3-d | Phone C | 11 | 306 | 34 % | 22.9 | 10.59 | 13.39 |
| swarm3-d | Phone A | 10 | 295 | 33 % | 22.0 | 10.21 | 13.39 |
| swarm2-1 | Phone B | 16 | 460 | 51 % | 35.3 | 9.67 | 13.03 |
| swarm2-1 | Phone C | 16 | 436 | 49 % | 32.5 | 9.16 | 13.40 |
| swarm2-2 | Phone B | 10 | 296 | 33 % | 22.2 | 9.55 | 13.34 |
| swarm2-2 | Phone C | 11 | 306 | 34 % | 22.8 | 9.87 | 13.41 |
| swarm2-2 | Phone A | 11 | 294 | 33 % | 21.9 | 9.48 | 13.44 |

Single phones: single-Phone-B 10.09, single-Phone-C 12.03, single-Phone-A 10.29. Fastest single phone: single-Phone-C at 12.03 tokens/s.
Swarm of 2 phones: 18.83 tokens/s (mean 18.83); against the fastest single phone: 1.56x (mean 1.56x).
Swarm of 3 phones: 30.94, 31.94, 31.76, 31.00, 28.90 tokens/s (mean 30.91); against the fastest single phone: 2.57x, 2.65x, 2.64x, 2.58x, 2.40x (mean 2.57x).
