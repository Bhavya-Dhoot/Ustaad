#!/usr/bin/env bash
# mc.sh <C|A> <METHOD> <path> [json]   signed call to the base app on phone C or A (secret: A's rotated pairing secret)
export SWARM_SECRET=35db0ea42d72efc0cb8862dc8eace07fe71b900a3ace8929748a7a76eece9017
case "$1" in A) H=192.168.242.89 ;; C) H=192.168.242.76 ;; *) echo "only A or C"; exit 2 ;; esac
shift
bash H:/UStaaf/.claude/worktrees/agent-ac34ad0bc1b4ddd46/scripts/swarm-call.sh "$H" "$@" 2>/dev/null
