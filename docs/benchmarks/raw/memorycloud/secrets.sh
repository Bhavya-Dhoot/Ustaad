#!/usr/bin/env bash
# which pairing secret does each phone accept (signed GET /v1/link/peers)
SC=H:/UStaaf/.claude/worktrees/agent-ac34ad0bc1b4ddd46/scripts/swarm-call.sh
for s in swarmos-dev-secret 35db0ea42d72efc0cb8862dc8eace07fe71b900a3ace8929748a7a76eece9017; do
  for h in 192.168.242.23 192.168.242.89 192.168.242.76; do
    r=$(SWARM_SECRET=$s bash $SC $h GET /link/peers 2>&1 | head -c 100 | tr '\n' ' ')
    echo "$h secret=${s:0:8}: $r"
  done
done
