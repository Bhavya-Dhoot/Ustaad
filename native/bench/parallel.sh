#!/system/bin/sh
# Phone-side: fire N concurrent 128-token chat requests at a running llama-server and report
# per-request decode tok/s (server timings), wall time and aggregate tokens / wall second.
# Usage: sh parallel.sh <port> <N> [max_tokens]
PORT=${1:-8080}; N=${2:-1}; MT=${3:-128}
D=/data/local/tmp/llama
cat > $D/par_req.json <<JSON
{"model":"x","messages":[{"role":"user","content":"Write a long, detailed story about a lighthouse keeper and a storm."}],"max_tokens":$MT,"ignore_eos":true,"temperature":0.7,"cache_prompt":false,"chat_template_kwargs":{"enable_thinking":false}}
JSON
rm -f $D/par_*.out
s0=$(date +%s); n0=$(date +%N | sed 's/^0*//'); n0=${n0:-0}
i=1
while [ $i -le $N ]; do
  curl -s -m 900 http://127.0.0.1:$PORT/v1/chat/completions -H 'Content-Type: application/json' -d @$D/par_req.json -o $D/par_$i.out &
  i=$((i+1))
done
wait
s1=$(date +%s); n1=$(date +%N | sed 's/^0*//'); n1=${n1:-0}
wall_ms=$(( (s1 - s0) * 1000 + (n1 - n0) / 1000000 ))
tot=0; i=1
while [ $i -le $N ]; do
  ct=$(grep -o '"completion_tokens":[0-9]*' $D/par_$i.out | head -1 | cut -d: -f2)
  ps_=$(grep -o '"predicted_per_second":[0-9.]*' $D/par_$i.out | head -1 | cut -d: -f2)
  pp_=$(grep -o '"prompt_per_second":[0-9.]*' $D/par_$i.out | head -1 | cut -d: -f2)
  echo "req$i completion_tokens=$ct decode_tok_s=$ps_ prefill_tok_s=$pp_"
  tot=$((tot + ${ct:-0})); i=$((i+1))
done
ag=$(( tot * 1000 * 100 / wall_ms ))
echo "N=$N wall_ms=$wall_ms total_completion_tokens=$tot aggregate_tok_s=$((ag/100)).$(printf %02d $((ag%100)))"
