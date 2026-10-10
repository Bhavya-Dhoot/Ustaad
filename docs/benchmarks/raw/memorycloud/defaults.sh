#!/usr/bin/env bash
# defaults.sh: load the default chat and embed models on A and C (B restored them itself)
D=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc
for p in A C; do
  bash $D/mc3.sh $p POST /models/load '{"role":"chat","file":"Qwen3.5-4B-Q4_K_M.gguf","wait":false}' | head -c 60; echo
  bash $D/mc3.sh $p POST /models/load '{"role":"embed","file":"embeddinggemma-300M-Q8_0.gguf","wait":false}' | head -c 60; echo
done
sleep 30
for p in A B C; do
  echo "== $p $(bash $D/mc3.sh $p GET /models/state | python -I -c "import sys,json; r=sys.stdin.read().split('\nHTTP')[0]; d=json.loads(r); print({k:(v['state'],v['file']) for k,v in d['roles'].items()})")"
done
