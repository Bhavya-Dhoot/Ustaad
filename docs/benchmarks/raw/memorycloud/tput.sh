#!/usr/bin/env bash
# tput.sh: 200 MB raw probe C->A and C->B over the DIRECT path
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for n in c6f72247 477684c1; do
  bash $D/mc3.sh C POST /link/tput "{\"peerNodeId\":\"$n\",\"megabytes\":200,\"link\":\"DIRECT\",\"pings\":50}" | head -c 700
  echo
done
