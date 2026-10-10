#!/usr/bin/env bash
# mc3.sh <A|B|C> <METHOD> <path> [json]   signed call to the base app on phone A, B or C (dev secret; all three phones accept it)
export SWARM_SECRET=swarmos-dev-secret
case "$1" in A) H=192.168.242.89 ;; B) H=192.168.242.23 ;; C) H=192.168.242.76 ;; *) echo "A, B or C"; exit 2 ;; esac
shift
bash H:/UStaaf/.claude/worktrees/agent-ac34ad0bc1b4ddd46/scripts/swarm-call.sh "$H" "$@" 2>/dev/null
