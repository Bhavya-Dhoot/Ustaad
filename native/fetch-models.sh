#!/usr/bin/env bash
# Resumable, verified GGUF download to <repo>/models (gitignored). INTERNET IS METERED: run each file once.
# Usage: native/fetch-models.sh <hf-repo> <file> [<expected-bytes>]
# Example: native/fetch-models.sh unsloth/Qwen3.5-4B-GGUF Qwen3.5-4B-Q4_K_M.gguf
# The expected sha256 is read from the Hugging Face tree API (LFS oid) and checked after download.
set -euo pipefail
REPO="$1"; FILE="$2"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/models/$FILE"
mkdir -p "$ROOT/models"
META="$(curl -s "https://huggingface.co/api/models/$REPO/tree/main" | python -c "
import sys,json
for f in json.load(sys.stdin):
    if f['path']=='$FILE': print(f['size'], (f.get('lfs') or {}).get('oid',''))")"
SIZE="${META%% *}"; SHA="${META##* }"
[ -n "$SIZE" ] || { echo "file not found in $REPO" >&2; exit 1; }
for i in $(seq 1 50); do
  have=$(stat -c %s "$OUT" 2>/dev/null || echo 0)
  [ "$have" = "$SIZE" ] && break
  curl -L -C - --fail --retry 5 --retry-delay 5 -o "$OUT" "https://huggingface.co/$REPO/resolve/main/$FILE" || sleep 10
done
have=$(stat -c %s "$OUT")
[ "$have" = "$SIZE" ] || { echo "size mismatch $have != $SIZE" >&2; exit 1; }
got=$(sha256sum "$OUT" | cut -d' ' -f1)
[ "$got" = "$SHA" ] && echo "OK $FILE $SIZE sha256=$got" || { echo "SHA MISMATCH $got != $SHA" >&2; exit 1; }
