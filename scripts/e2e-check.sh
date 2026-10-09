#!/usr/bin/env bash
# End-to-end check of the merged app on two phones (gates 3 and 4 and the swarm throughput number).
#
#   scripts/e2e-check.sh [options] <phone-A-ip[:port]> <phone-C-ip[:port]>
#
# Talks only to the two addresses given (Node API on port 8787 unless a port is part of the address) and, through
# adb, to <ip>:5555 of the same two phones. Phone B (192.168.242.23) is refused. Every request is signed like
# scripts/swarm-call.sh does (X-Swarm-Node, X-Swarm-Ts, X-Swarm-Sig = hex HMAC-SHA256(secret, "$method\n$path\n$ts\n$body"),
# plus X-Swarm-Req). Prints one PASS / FAIL / WARN / INFO line per check, keeps every raw response under the results
# directory and writes markdown tables (tables/*.md) from those responses for the benchmark notes.
#
# Checks (--steps a,b,c,d,e,f,g, default all):
#   a  both phones answer GET /v1/hello and a signed GET /v1/health; an unsigned request to a signed route gets 401
#   b  each phone lists the other as online in /v1/link/peers within 20 s
#   c  chat READY with a real model (not FAKE) and the embedder READY on each phone; models are loaded if not
#   d  Agent Choir: one built-in arithmetic problem from A with C as peer, both lanes answered by a real model
#   e  Agent Choir evaluation: the built-in problem set in slices with thermal waits between (four arms)
#   f  Private Data Union: index the demo corpus, ask from A, calibration scores, private item withheld
#   g  swarm throughput: bench32 local on A, then swarm A+C, from a cool start each, twice
#
# Options:
#   --steps LIST        which checks to run (default a,b,c,d,e,f,g)
#   --results DIR       where raw responses and tables go (default: scratchpad/e2e-results/<timestamp>)
#   --slice N           problems per eval slice (default 5)
#   --max-problems N   run at most N of the 20 built-in problems (default 20)
#   --min-problems N   eval counts as PASS only with at least N problems run (default 10)
#   --chat-file NAME   chat model file both phones must run (default Qwen3.5-4B-Q4_K_M.gguf)
#   --keep-models      do not stop the model servers when the script ends
# Environment: SWARM_SECRET (else the debug dev secret, else the secret the debug build publishes at
#   /v1/link/pairing-payload), ADB, PYTHON, E2E_COOL_MAX_MIN (longest thermal wait per guard, default 45),
#   E2E_CHOIR_PROBLEM (check d, default m8), E2E_PRIVATE_TEST=0 (skip the temporary private file), E2E_PKG (default com.swarmos.app).
set -u
export MSYS_NO_PATHCONV=1
export PYTHONUTF8=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
REPO="$(cd "$HERE/.." && { pwd -W 2>/dev/null || pwd; })"
PY="${PYTHON:-python}"
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
DEV_SECRET="swarmos-dev-secret"
PKG="${E2E_PKG:-com.swarmos.app}"
CHAT_FILE="Qwen3.5-4B-Q4_K_M.gguf"
CHOIR_PROBLEM="${E2E_CHOIR_PROBLEM:-m8}"
COOL_MAX=$(( ${E2E_COOL_MAX_MIN:-45} * 60 ))
PEER_WAIT=20
STEPS="a,b,c,d,e,f,g"
SLICE=5
MAX_PROBLEMS=20
MIN_PROBLEMS=10
KEEP_MODELS=0
RESULTS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --steps) STEPS="$2"; shift 2 ;;
    --results) RESULTS="$2"; shift 2 ;;
    --slice) SLICE="$2"; shift 2 ;;
    --max-problems) MAX_PROBLEMS="$2"; shift 2 ;;
    --min-problems) MIN_PROBLEMS="$2"; shift 2 ;;
    --chat-file) CHAT_FILE="$2"; shift 2 ;;
    --keep-models) KEEP_MODELS=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    --) shift; break ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ $# -eq 2 ] || { echo "usage: e2e-check.sh [options] <phone-A-ip[:port]> <phone-C-ip[:port]>" >&2; exit 2; }

declare -A HP SER SEC SRC TH PEAK
HP[A]="$1"; HP[C]="$2"
for ph in A C; do
  case "${HP[$ph]}" in
    192.168.242.23|192.168.242.23:*) echo "refusing ${HP[$ph]}: phone B is reserved for the founder" >&2; exit 2 ;;
  esac
  printf '%s' "${HP[$ph]}" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]+)?$' || { echo "not an IPv4 address: ${HP[$ph]}" >&2; exit 2; }
  case "${HP[$ph]}" in *:*) ;; *) HP[$ph]="${HP[$ph]}:8787" ;; esac
  SER[$ph]="${HP[$ph]%%:*}:5555"
done
[ "${HP[A]%%:*}" != "${HP[C]%%:*}" ] || { echo "A and C must be different phones" >&2; exit 2; }

if [ -z "$RESULTS" ]; then
  RESULTS="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/e2e-results/$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$RESULTS/tables" || exit 2

PASSES=0; FAILS=0; WARNS=0
MODELS_TOUCHED=0
BG_PIDS=""

# ---- output ---------------------------------------------------------------------------------------------

say()  { printf '%s\n' "$*" | tee -a "$RESULTS/checks.log"; }
pass() { PASSES=$((PASSES+1)); say "PASS  $1  $2"; }
fail() { FAILS=$((FAILS+1));   say "FAIL  $1  $2"; }
warn() { WARNS=$((WARNS+1));   say "WARN  $1  $2"; }
info() { say "INFO  $1  $2"; }
jx()   { "$PY" "$HERE/e2e/e2e.py" "$@"; }
now_ms() { date +%s%3N; }
step_on() { case ",$STEPS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# ---- signed HTTP ----------------------------------------------------------------------------------------

# call <A|C> <METHOD> <path> [body] [outfile] [max-seconds]  ->  sets CODE (HTTP status, 000 on transport failure) and CALL_MS.
# NOSIG=1 call ...  sends no signature headers.
call() {
  local ph=$1 method=$2 path=$3 body=${4-} out=${5:-$RESULTS/last.json} maxt=${6:-60}
  local ts sig msg nl=$'\n' t0 sigpath="${3%%\?*}"
  local args=(-sS -m "$maxt" -o "$out" -w '%{http_code}' -X "$method")
  if [ "${NOSIG:-0}" != 1 ]; then
    ts=$(now_ms)
    msg="${method}${nl}${sigpath}${nl}${ts}${nl}${body}"
    sig=$(printf '%s' "$msg" | openssl dgst -sha256 -hmac "${SEC[$ph]:-$DEV_SECRET}" -hex | awk '{print $NF}')
    args+=(-H "X-Swarm-Node: e2e-pc" -H "X-Swarm-Ts: $ts" -H "X-Swarm-Sig: $sig" -H "X-Swarm-Req: e2e-$(openssl rand -hex 8)")
  fi
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
  : > "$out"
  t0=$(now_ms)
  CODE=$(curl "${args[@]}" "http://${HP[$ph]}$path" 2>"$RESULTS/curl.err") || CODE=${CODE:-000}
  [ -n "$CODE" ] || CODE=000
  CALL_MS=$(( $(now_ms) - t0 ))
}

# secret to sign with: SWARM_SECRET, the debug dev secret, or what the debug build publishes for adb tooling
resolve_secret() {
  local ph=$1
  if [ -n "${SWARM_SECRET:-}" ]; then SEC[$ph]="$SWARM_SECRET"; SRC[$ph]="SWARM_SECRET"; return 0; fi
  SEC[$ph]="$DEV_SECRET"; SRC[$ph]="debug dev secret"
  call "$ph" GET /v1/health "" "$RESULTS/secret-probe.json" 8
  [ "$CODE" = 200 ] && return 0
  NOSIG=1 call "$ph" GET /v1/link/pairing-payload "" "$RESULTS/secret-payload-$ph.tmp" 8
  local s; s=$(jx get "$RESULTS/secret-payload-$ph.tmp" "d['secret']")
  rm -f "$RESULTS/secret-payload-$ph.tmp"
  [ -n "$s" ] || return 1
  SEC[$ph]="$s"; SRC[$ph]="secret published by the debug build (/v1/link/pairing-payload)"
  call "$ph" GET /v1/health "" "$RESULTS/secret-probe.json" 8
  [ "$CODE" = 200 ]
}

# ---- thermal guard, models ------------------------------------------------------------------------------

thermal_of() {
  local ph=$1 t
  t=$("$ADB" -s "${SER[$ph]}" shell 'dumpsys thermalservice | grep "Thermal Status" | head -1' </dev/null 2>/dev/null | tr -d '\r' | grep -o '[0-9]\+' | head -1)
  if [ -z "$t" ]; then
    call "$ph" GET /v1/health "" "$RESULTS/health-$ph-now.json" 8
    t=$(jx get "$RESULTS/health-$ph-now.json" "d['thermalStatus']")
  fi
  echo "${t:-?}"
}

models_assert() { # ph -> prints PASS/FAIL line
  call "$1" GET /v1/models/state "" "$RESULTS/models-$1.json" 15
  [ "$CODE" = 200 ] || { echo "FAIL models/state HTTP $CODE"; return; }
  jx models-assert "$RESULTS/models-$1.json" "$CHAT_FILE"
}

wait_models_ready() { # ph seconds
  local ph=$1 limit=$2 t0 r; t0=$(date +%s)
  while :; do
    r=$(models_assert "$ph")
    [ "${r%% *}" = PASS ] && return 0
    (( $(date +%s) - t0 >= limit )) && return 1
    sleep 5
  done
}

# load what is missing: defaults when a role is down, the pinned chat file when another model is loaded
ensure_models() { # ph...
  local ph r chat_state chat_file embed_state rc=0
  for ph in "$@"; do
    r=$(models_assert "$ph")
    [ "${r%% *}" = PASS ] && continue
    MODELS_TOUCHED=1
    chat_state=$(jx get "$RESULTS/models-$ph.json" "d['roles']['chat']['state']")
    chat_file=$(jx get "$RESULTS/models-$ph.json" "d['roles']['chat']['file']")
    embed_state=$(jx get "$RESULTS/models-$ph.json" "d['roles']['embed']['state']")
    info models "$ph: ${r#FAIL }; loading"
    if [ "$chat_state" != READY ] || [ "$embed_state" != READY ]; then
      call "$ph" POST /v1/models/load '{"role":"defaults"}' "$RESULTS/models-load-$ph.json" 30
      wait_models_ready "$ph" 240 || true
      r=$(models_assert "$ph")
      chat_file=$(jx get "$RESULTS/models-$ph.json" "d['roles']['chat']['file']")
    fi
    if [ "${r%% *}" != PASS ] && [ -n "$chat_file" ] && [ "$chat_file" != "$CHAT_FILE" ] && [ "$(jx get "$RESULTS/models-$ph.json" "d['roles']['chat']['state']")" = READY ]; then
      call "$ph" POST /v1/models/load "{\"role\":\"chat\",\"file\":\"$CHAT_FILE\"}" "$RESULTS/models-load-$ph.json" 240
      wait_models_ready "$ph" 120 || true
    fi
    r=$(models_assert "$ph")
    [ "${r%% *}" = PASS ] || { info models "$ph still not ready: ${r#FAIL }"; rc=1; }
  done
  return $rc
}

# wait until every listed phone shows Thermal Status 0 or 1 and has its models; sets TH[ph] (start status) and PEAK[ph]
ready_cool() { # label phone...
  local label=$1; shift
  local waited=0 ph st all
  MODELS_TOUCHED=1 # from here on something may generate: the exit trap stops the model servers
  while :; do
    all=1
    for ph in "$@"; do st=$(thermal_of "$ph"); TH[$ph]=$st; case "$st" in 0|1) ;; *) all=0 ;; esac; done
    if [ $all = 1 ]; then
      ensure_models "$@" || { fail "$label" "models not ready on every phone"; return 1; }
      all=1
      for ph in "$@"; do st=$(thermal_of "$ph"); TH[$ph]=$st; case "$st" in 0|1) ;; *) all=0 ;; esac; done
      [ $all = 1 ] && break
    fi
    if (( waited >= COOL_MAX )); then
      fail "$label" "thermal status did not reach 0 or 1 within $((COOL_MAX / 60)) min (A=${TH[A]:-?} C=${TH[C]:-?}); step not run hot"
      return 1
    fi
    info "$label" "thermal A=${TH[A]:-?} C=${TH[C]:-?}: waiting 60 s (waited $waited s)"
    if (( waited == 180 )); then # still hot after 3 min: unload the models on the hot phones, they come back in ensure_models
      for ph in "$@"; do
        case "${TH[$ph]:-?}" in 0|1) ;; *) call "$ph" POST /v1/models/stop '{"role":"all"}' "$RESULTS/models-stop-$ph.json" 30; MODELS_TOUCHED=1; info "$label" "$ph still hot after 3 min: model servers stopped while it cools" ;; esac
      done
    fi
    sleep 60; waited=$((waited + 60))
  done
  for ph in "$@"; do PEAK[$ph]=${TH[$ph]}; echo "$(date +%T) $label $ph start-thermal=${TH[$ph]}" >> "$RESULTS/thermal.log"; done
  return 0
}

note_peak() { # ph...   keep the highest status seen while something runs
  local ph st
  for ph in "$@"; do st=$(thermal_of "$ph"); case "$st" in [0-9]) [ "$st" -gt "${PEAK[$ph]:-0}" ] && PEAK[$ph]=$st ;; esac; done
}

# ---- cleanup --------------------------------------------------------------------------------------------

finish() {
  trap - EXIT INT TERM
  [ -n "$BG_PIDS" ] && kill $BG_PIDS 2>/dev/null
  if [ "$MODELS_TOUCHED" = 1 ] && [ "$KEEP_MODELS" = 0 ]; then
    for ph in A C; do call "$ph" POST /v1/models/stop '{"role":"all"}' "$RESULTS/models-stop-final-$ph.json" 30; echo "models stopped on $ph: HTTP $CODE" >> "$RESULTS/checks.log"; done
  fi
  say "SUMMARY  pass=$PASSES fail=$FAILS warn=$WARNS  results=$RESULTS"
  [ "$FAILS" = 0 ]
  exit $?
}
trap 'finish' EXIT
trap 'say "INTERRUPTED"; FAILS=$((FAILS+1)); finish' INT TERM

# ---- meta -----------------------------------------------------------------------------------------------

write_meta() {
  {
    echo "date: $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "repo head: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null) ($(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null))"
    echo "steps: $STEPS; slice $SLICE; chat file $CHAT_FILE"
    for ph in A C; do
      echo "phone $ph: ${HP[$ph]}; secret source: ${SRC[$ph]:-unresolved}"
      "$ADB" -s "${SER[$ph]}" shell "dumpsys package $PKG | grep -E 'versionName|lastUpdateTime'; ls -l /sdcard/Android/data/$PKG/files/models/ | tr -s ' ' | cut -d' ' -f5-" </dev/null 2>/dev/null | tr -d '\r' | sed "s/^/  /"
    done
  } > "$RESULTS/meta.txt"
}

# =============================================================================================================
# a. hello, signed health, 401
# =============================================================================================================
step_a() {
  local ph
  for ph in A C; do
    NOSIG=1 call "$ph" GET /v1/hello "" "$RESULTS/hello-$ph.json" 10
    if [ "$CODE" = 200 ] && [ -n "$(jx get "$RESULTS/hello-$ph.json" "d['nodeId']")" ]; then
      pass "a1-hello-$ph" "HTTP 200 in $CALL_MS ms, $(jx get "$RESULTS/hello-$ph.json" "d['name']") node $(jx get "$RESULTS/hello-$ph.json" "d['nodeId']"), chat '$(jx get "$RESULTS/hello-$ph.json" "d['chatModel']")', embed '$(jx get "$RESULTS/hello-$ph.json" "d['embedModel']")'"
    else
      fail "a1-hello-$ph" "GET /v1/hello gave HTTP $CODE ($(head -c 120 "$RESULTS/curl.err" 2>/dev/null))"
    fi
    if resolve_secret "$ph"; then
      call "$ph" GET /v1/health "" "$RESULTS/health-$ph.json" 10
      if [ "$CODE" = 200 ] && [ -n "$(jx get "$RESULTS/health-$ph.json" "d['nodeId']")" ]; then
        pass "a2-health-$ph" "signed GET /v1/health HTTP 200 in $CALL_MS ms (secret: ${SRC[$ph]}); thermal $(jx get "$RESULTS/health-$ph.json" "d['thermalStatus']"), battery $(jx get "$RESULTS/health-$ph.json" "d['batteryPct']") %, ram avail $(jx get "$RESULTS/health-$ph.json" "d['ramAvailMb']") MB, link $(jx get "$RESULTS/health-$ph.json" "d['linkStandard']") $(jx get "$RESULTS/health-$ph.json" "d['linkSpeedMbps']") Mbps"
      else
        fail "a2-health-$ph" "signed GET /v1/health gave HTTP $CODE"
      fi
    else
      fail "a2-health-$ph" "no secret signs successfully (dev secret rejected and no pairing payload)"
    fi
    NOSIG=1 call "$ph" GET /v1/health "" "$RESULTS/unsigned-$ph.json" 10
    if [ "$CODE" = 401 ]; then pass "a3-unsigned-$ph" "unsigned GET /v1/health -> 401 ($(jx get "$RESULTS/unsigned-$ph.json" "d['error']"))"
    else fail "a3-unsigned-$ph" "unsigned GET /v1/health gave HTTP $CODE, expected 401"; fi
  done
}

# =============================================================================================================
# b. peers
# =============================================================================================================
step_b() {
  local me other oid t0 r
  for pair in "A C" "C A"; do
    set -- $pair; me=$1; other=$2
    oid=$(jx get "$RESULTS/hello-$other.json" "d['nodeId']")
    [ -n "$oid" ] || { fail "b-peers-$me" "no node id of $other from step a"; continue; }
    t0=$(now_ms); r=none
    while :; do
      call "$me" GET /v1/link/peers "" "$RESULTS/peers-$me.json" 10
      [ "$CODE" = 200 ] && r=$(jx peer-assert "$RESULTS/peers-$me.json" "$oid")
      [ "${r%% *}" = online ] && break
      (( $(now_ms) - t0 >= PEER_WAIT * 1000 )) && break
      sleep 1
    done
    local el=$(( $(now_ms) - t0 ))
    if [ "${r%% *}" = online ] && [ "$el" -le $((PEER_WAIT * 1000)) ]; then
      pass "b-peers-$me" "$other listed online after $el ms (${r#online })"
    elif [ "${r%% *}" = online ]; then
      fail "b-peers-$me" "$other listed online only after $el ms, limit $PEER_WAIT s (${r#online })"
    else
      fail "b-peers-$me" "$other not listed online within $PEER_WAIT s (HTTP $CODE)"
      call "$me" POST /v1/link/peers/add "{\"ip\":\"${HP[$other]%%:*}\"}" "$RESULTS/peers-add-$me.json" 20
      info "b-peers-$me" "workaround: POST /v1/link/peers/add for $other gave HTTP $CODE; later steps depend on it"
    fi
  done
}

# =============================================================================================================
# c. models
# =============================================================================================================
step_c() {
  local ph r
  for ph in A C; do
    r=$(models_assert "$ph")
    if [ "${r%% *}" != PASS ]; then
      info "c-models-$ph" "${r#FAIL }; loading defaults"
      ensure_models "$ph"
      r=$(models_assert "$ph")
    fi
    if [ "${r%% *}" = PASS ]; then
      call "$ph" GET /v1/hello "" "$RESULTS/hello-$ph.json" 10
      local hm; hm=$(jx get "$RESULTS/hello-$ph.json" "d['chatModel']")
      if [ -n "$hm" ] && [ "$hm" != FAKE ]; then pass "c-models-$ph" "${r#PASS }; node reports chat '$hm'"
      else fail "c-models-$ph" "servers READY but /v1/hello still reports chat '$hm'"; fi
    else
      fail "c-models-$ph" "${r#FAIL }"
    fi
  done
}

# =============================================================================================================
# d. one choir run
# =============================================================================================================
step_d() {
  local f="$RESULTS/choir-run.json" r t0 n
  ready_cool d-choir-run A C || return
  info d-choir-run "problem $CHOIR_PROBLEM from A with C as peer; thermal at start A=${TH[A]} C=${TH[C]}"
  call A POST /v1/choir/run "{\"problemId\":\"$CHOIR_PROBLEM\"}" "$f" 180
  local code=$CODE ms=$CALL_MS
  if [ "$code" != 200 ]; then # the node server may drop a connection that stays idle for 45 s: read the trace it keeps
    info d-choir-run "HTTP $code after $ms ms; polling GET /v1/choir/last"
    t0=$(date +%s)
    while (( $(date +%s) - t0 < 150 )); do
      call A GET /v1/choir/last "" "$f" 10
      [ "$(jx get "$f" "d['phase']")" = DONE ] && break
      sleep 5
    done
  fi
  r=$(jx choir-assert "$f")
  if [ "${r%% *}" = PASS ]; then pass d-choir-run "${r#PASS } (HTTP $code, $ms ms at the client; thermal start A=${TH[A]} C=${TH[C]})"
  else fail d-choir-run "${r#FAIL } (HTTP $code, thermal start A=${TH[A]} C=${TH[C]})"; fi
  jx report-choir-run "$f" > "$RESULTS/tables/choir-run.md" 2>/dev/null
  note_peak A C
  echo "choir-run	thermal start A=${TH[A]} C=${TH[C]}	peak A=${PEAK[A]} C=${PEAK[C]}" >> "$RESULTS/thermal.log"
}

# =============================================================================================================
# e. choir evaluation in slices
# =============================================================================================================
eval_slice() { # idx ids(comma separated)
  local idx=$1 ids=$2 t0 body pid f
  f="$RESULTS/eval-slice-$idx.json"
  ready_cool "e-slice-$idx" A C || return 1
  local tha=${TH[A]} thc=${TH[C]}
  body="{\"problemIds\":[\"${ids//,/\",\"}\"],\"useProblemCheck\":false,\"samples\":true}"
  info "e-slice-$idx" "problems $ids; thermal at start A=$tha C=$thc"
  t0=$(date +%s)
  rm -f "$RESULTS/eval-slice-$idx.code"
  ( call A POST /v1/choir/eval "$body" "$f" 2400; echo "$CODE" > "$RESULTS/eval-slice-$idx.code" ) &
  pid=$!; BG_PIDS="$BG_PIDS $pid"
  while kill -0 "$pid" 2>/dev/null; do
    sleep 15
    note_peak A C
    call A GET /v1/choir/eval/status "" "$RESULTS/eval-status.json" 10
    if [ "$CODE" = 200 ]; then
      info "e-slice-$idx" "$(jx get "$RESULTS/eval-status.json" "d['done']")/$(jx get "$RESULTS/eval-status.json" "d['total']") done, current $(jx get "$RESULTS/eval-status.json" "d['current']"), thermal A=$(thermal_of A) C=$(thermal_of C), $(( $(date +%s) - t0 )) s"
    fi
  done
  wait "$pid" 2>/dev/null
  local code; code=$(cat "$RESULTS/eval-slice-$idx.code" 2>/dev/null || echo 000)
  local wall=$(( $(date +%s) - t0 )) note="" rows
  if [ "$code" != 200 ]; then # connection dropped or refused: fall back to the progress the app keeps
    note="HTTP $code on the eval call"
    for _ in 1 2 3 4 5 6 7 8; do
      call A GET /v1/choir/eval/status "" "$RESULTS/eval-status.json" 10
      [ "$(jx get "$RESULTS/eval-status.json" "d['running']")" = false ] && break
      sleep 15
    done
    if [ "$CODE" = 200 ] && [ "$(jx get "$RESULTS/eval-status.json" "d['running']")" = false ]; then
      cp "$RESULTS/eval-status.json" "$f"; note="$note; rows taken from /v1/choir/eval/status: $(jx get "$RESULTS/eval-status.json" "d['error']")"
    fi
  fi
  rows=$(jx get "$f" "len(d['rows'])" 0)
  printf '%s\t%s\t%s / %s\t%s / %s\t%s\t%s\t%s\t%s\n' "$idx" "$ids" "$tha" "$thc" "${PEAK[A]}" "${PEAK[C]}" "$wall" "$code" "$rows" "$note" >> "$RESULTS/slices.tsv"
  echo "eval-slice-$idx	thermal start A=$tha C=$thc	peak A=${PEAK[A]} C=${PEAK[C]}	${wall}s" >> "$RESULTS/thermal.log"
  [ "$code" = 200 ] || [ "$rows" -gt 0 ] 2>/dev/null
}

step_e() {
  local i=0 line failed=0
  call A GET /v1/choir/problems "" "$RESULTS/choir-problems.json" 15
  [ "$CODE" = 200 ] || { fail e-eval "GET /v1/choir/problems gave HTTP $CODE"; return; }
  : > "$RESULTS/slices.tsv"
  local all; all=$(jx slices "$RESULTS/choir-problems.json" "$SLICE" | tr -d '\r')
  local picked=0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    (( picked >= MAX_PROBLEMS )) && break
    local n; n=$(awk -F, '{print NF}' <<<"$line")
    if (( picked + n > MAX_PROBLEMS )); then
      line=$(cut -d, -f1-$((MAX_PROBLEMS - picked)) <<<"$line"); n=$((MAX_PROBLEMS - picked))
    fi
    [ "$n" -gt 0 ] || break
    i=$((i + 1)); picked=$((picked + n))
    eval_slice "$i" "$line" || { failed=1; fail "e-slice-$i" "slice produced no rows"; break; }
  done <<<"$all"
  jx report-eval "$RESULTS" > "$RESULTS/tables/choir-eval.md" 2>/dev/null
  local n_run; n_run=$(grep -o 'problems run: n = [0-9]*' "$RESULTS/tables/choir-eval.md" | grep -o '[0-9]*$')
  n_run=${n_run:-0}
  if [ "$n_run" -ge "$MIN_PROBLEMS" ] && [ "$failed" = 0 ]; then pass e-eval "$n_run of 20 problems evaluated in $i slice(s) (target 20, minimum $MIN_PROBLEMS)"
  else fail e-eval "$n_run of 20 problems evaluated (minimum $MIN_PROBLEMS), failed=$failed"; fi
  sed -n '3,8p' "$RESULTS/tables/choir-eval.md" | while IFS= read -r l; do info e-arms "$l"; done
  local errs; errs=$(grep -o 'rows with an error: [0-9]*' "$RESULTS/tables/choir-eval.md" | grep -o '[0-9]*$')
  [ "${errs:-0}" = 0 ] || warn e-rows "$errs row(s) carry an error (see tables/choir-eval.md)"
}

# =============================================================================================================
# f. Private Data Union
# =============================================================================================================
index_dir() { # ph dir force(true|false) tag
  local ph=$1 dir=$2 force=$3 tag=$4 f="$RESULTS/pdu-index-$1-$4.json" t0
  call "$ph" POST /v1/pdu/index "{\"dir\":\"$dir\",\"force\":$force}" "$f" 900
  if [ "$CODE" != 200 ] && [ "$CODE" != 409 ]; then # connection dropped while indexing: wait for the app, read its last report
    info "f-index-$ph" "HTTP $CODE after $CALL_MS ms; waiting for the index run to end"
    t0=$(date +%s)
    while (( $(date +%s) - t0 < 600 )); do
      call "$ph" GET /v1/pdu/status "" "$RESULTS/pdu-status-poll-$ph.json" 15
      [ "$(jx get "$RESULTS/pdu-status-poll-$ph.json" "d['running']")" = false ] && break
      sleep 10
    done
    "$PY" -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); json.dump(d.get('lastIndex') or {}, open(sys.argv[2],'w',encoding='utf-8'))" "$RESULTS/pdu-status-poll-$ph.json" "$f"
  fi
}

ask() { # id key kind [expect-note]
  local id=$1 key=$2 kind=$3 f="$RESULTS/pdu-ask-$1.json" body r
  ready_cool "f-$id" A C || return 1
  body=$(jx ask-body "$key" 5 1500)
  call A POST /v1/pdu/ask "$body" "$f" 150
  r=$(jx ask-assert "$f" "$kind")
  local th="thermal start A=${TH[A]} C=${TH[C]}"
  if [ "${r%% *}" = PASS ]; then pass "f-$id" "'$(jx question "$key")' -> ${r#PASS }; $th"
  else fail "f-$id" "'$(jx question "$key")' -> ${r#FAIL }; $th"; fi
  echo "pdu-ask-$id	$th" >> "$RESULTS/thermal.log"
}

corpus_name() { # ph -> UstaadDemo when the phone has that folder, else SwarmDemo (the folder name changed in the repo corpus)
  if "$ADB" -s "${SER[$1]}" shell "ls -d /sdcard/Pictures/UstaadDemo" </dev/null >/dev/null 2>&1; then echo UstaadDemo; else echo SwarmDemo; fi
}

share_folder() { # ph folder   (the debug route that the owner's switch in the library stands in for)
  local ph=$1 folder=$2 t0
  call "$ph" POST /v1/pdu/debug/share "{\"folder\":\"$folder\",\"shared\":true}" "$RESULTS/pdu-share-$ph.json" 20
  [ "$CODE" = 200 ] || { warn "f-share-$ph" "POST /v1/pdu/debug/share $folder gave HTTP $CODE"; return 1; }
  t0=$(date +%s)
  while (( $(date +%s) - t0 < 20 )); do # the route answers before the store is updated
    call "$ph" GET /v1/pdu/status "" "$RESULTS/pdu-status-$ph.json" 15
    [ "$(jx get "$RESULTS/pdu-status-$ph.json" "any(x['folder'] == '$folder' and not x['isPrivate'] for x in d['folders'])")" = true ] && return 0
    sleep 1
  done
  warn "f-share-$ph" "folder $folder still private after 20 s"; return 1
}

step_f() {
  local ph st r nm
  for ph in A C; do # indexing a folder over adb needs all-files access for the app
    st=$("$ADB" -s "${SER[$ph]}" shell "appops get $PKG MANAGE_EXTERNAL_STORAGE" </dev/null | tr -d '\r')
    case "$st" in
      *allow*) info "f-perm-$ph" "all-files access already allowed for $PKG" ;;
      *) "$ADB" -s "${SER[$ph]}" shell "appops set $PKG MANAGE_EXTERNAL_STORAGE allow" </dev/null
         warn "f-perm-$ph" "was '$st'; set MANAGE_EXTERNAL_STORAGE allow for $PKG (app permission, needed to index a folder without a picker; left in place)" ;;
    esac
  done
  ready_cool f-index A C || return
  declare -A NM
  for ph in A C; do
    nm=$(corpus_name "$ph"); NM[$ph]=$nm
    index_dir "$ph" "/sdcard/Pictures/$nm" true pictures
    index_dir "$ph" "/sdcard/Documents/$nm" false documents
    call "$ph" GET /v1/pdu/status "" "$RESULTS/pdu-status-$ph.json" 15
    cp "$RESULTS/pdu-status-$ph.json" "$RESULTS/pdu-status-$ph-as-indexed.json"
    local emb chunks priv errs
    emb=$(jx get "$RESULTS/pdu-status-$ph.json" "d['embedModel']"); chunks=$(jx get "$RESULTS/pdu-status-$ph.json" "d['chunks']" 0)
    priv=$(jx get "$RESULTS/pdu-status-$ph.json" "sum(1 for x in d['folders'] if x['isPrivate'])" 0)
    errs="$(jx get "$RESULTS/pdu-index-$ph-pictures.json" "d['error']") $(jx get "$RESULTS/pdu-index-$ph-documents.json" "d['error']")"
    if [ -n "${errs// /}" ]; then fail "f-index-$ph" "index error: $errs"
    elif [ -z "$emb" ] || [ "$emb" = FAKE ]; then fail "f-index-$ph" "store was built with embedder '$emb'"
    elif [ "$chunks" -lt 1 ]; then fail "f-index-$ph" "no chunks indexed"
    else pass "f-index-$ph" "folders $nm: $chunks chunks with '$emb'; pictures $(jx get "$RESULTS/pdu-index-$ph-pictures.json" "d['indexed']") indexed of $(jx get "$RESULTS/pdu-index-$ph-pictures.json" "d['seen']") seen in $(jx get "$RESULTS/pdu-index-$ph-pictures.json" "d['totalMs']") ms (OCR avg $(jx get "$RESULTS/pdu-index-$ph-pictures.json" "d['ocrMsAvg']") ms), documents $(jx get "$RESULTS/pdu-index-$ph-documents.json" "d['indexed']") of $(jx get "$RESULTS/pdu-index-$ph-documents.json" "d['seen']") in $(jx get "$RESULTS/pdu-index-$ph-documents.json" "d['totalMs']") ms; folders private as indexed: $priv of $(jx get "$RESULTS/pdu-status-$ph.json" "len(d['folders'])" 0)"; fi
  done

  # every folder starts private: before the owner shares anything, a peer must get nothing from the demo folders
  ask pre-share-electricity electricity any
  local wc; wc=$(jx get "$RESULTS/pdu-ask-pre-share-electricity.json" "sum(x['withheldPrivate'] for x in d['devices'] if not x['local'])" 0)
  if [ "$wc" -ge 1 ] && [ "$(jx get "$RESULTS/pdu-ask-pre-share-electricity.json" "any(not s['local'] for s in d['sources'])")" != true ]; then
    pass f-private-default "before sharing, Phone C withheld $wc private item(s) and no source from C reached A"
  else fail f-private-default "before sharing: withheld from C = $wc, or a peer source was shown"; fi

  for ph in A C; do # the debug route stands in for the owner's switch in the library; say so in the notes
    share_folder "$ph" "Pictures/${NM[$ph]}" && share_folder "$ph" "Documents/${NM[$ph]}" \
      && info "f-share-$ph" "Pictures/${NM[$ph]} and Documents/${NM[$ph]} marked shared through POST /v1/pdu/debug/share"
  done

  ask warranty-1 warranty relevant
  ask warranty-2 warranty relevant
  ask warranty-3 warranty relevant
  # the certificate is on A; the bill photo is on phone B, which is not part of this run
  if [ "$(jx get "$RESULTS/pdu-ask-warranty-1.json" "any('Warranty_Certificate' in s['title'] and s['local'] and s['phone'] == 'Phone A' for s in d['sources'])")" = true ]; then
    pass f-cite-certificate "Laptop_Extended_Warranty_Certificate.pdf cited as Phone A (local)"
  else fail f-cite-certificate "the certificate on Phone A is not among the cited sources"; fi
  info f-cite-bill "the photographed bill (IMG_2231.jpg) belongs to phone B's corpus; B is reserved, so the A+B citation pair of PRD 5.4 was not tested"
  ask electricity electricity relevant
  if [ "$(jx get "$RESULTS/pdu-ask-electricity.json" "any('Electricity' in s['title'] and not s['local'] and s['phone'] == 'Phone C' for s in d['sources'])")" = true ]; then
    pass f-cite-peer "IMG_3301_Electricity_Bill.jpg cited as Phone C (peer)"
  else fail f-cite-peer "the electricity bill on Phone C is not among the cited sources"; fi
  ask unrelated unrelated none
  ask hindi-warranty hindi_warranty relevant
  ask hindi-note hindi_note any

  for ph in A C; do # calibration of the dense floor: numbers only, the constant is not changed
    for key in warranty unrelated hindi_warranty; do
      call "$ph" POST /v1/pdu/debug/scores "$(jx ask-body "$key" 5)" "$RESULTS/pdu-scores-$ph-$key.json" 60
      [ "$CODE" = 200 ] || warn "f-scores-$ph-$key" "HTTP $CODE"
    done
  done
  pass f-scores "calibration recorded for 3 questions on A and C (tables/pdu.md)"

  if [ "${E2E_PRIVATE_TEST:-1}" = 1 ]; then
    # a Private folder inside a shared folder must stay private: push the sample salary slip to C for the duration of the test
    local priv_src priv_dir="/sdcard/Documents/${NM[C]}/Private"
    priv_src=$(ls "$REPO"/docs/demo-corpus/B/Documents/*/Private/Salary_Slip_Sep2026.pdf 2>/dev/null | head -1)
    if [ -n "$priv_src" ]; then
      "$ADB" -s "${SER[C]}" shell "mkdir -p $priv_dir" </dev/null
      "$ADB" -s "${SER[C]}" push "$priv_src" "$priv_dir/" </dev/null >/dev/null
      index_dir C "/sdcard/Documents/${NM[C]}" false private-temp
      info f-private "temporary ${priv_dir#/sdcard/}/Salary_Slip_Sep2026.pdf indexed on C: $(jx get "$RESULTS/pdu-index-C-private-temp.json" "d['indexed']") new document(s)"
      ask salary salary any
      local w; w=$(jx get "$RESULTS/pdu-ask-salary.json" "sum(x['withheldPrivate'] for x in d['devices'] if not x['local'])" 0)
      if [ "$w" -ge 1 ] && [ "$(jx get "$RESULTS/pdu-ask-salary.json" "any('alary' in s['title'] for s in d['sources'])")" != true ]; then
        pass f-withheld "Phone C withheld $w private item(s) and no source from the Private folder reached A"
      else fail f-withheld "withheld count from C = $w, or a private source was shown"; fi
      "$ADB" -s "${SER[C]}" shell "rm -rf $priv_dir" </dev/null
      index_dir C "/sdcard/Pictures/${NM[C]}" true pictures-restore
      index_dir C "/sdcard/Documents/${NM[C]}" false documents-restore
      call C GET /v1/pdu/status "" "$RESULTS/pdu-status-C-after-private.json" 15
      info f-private "temporary file removed from C and C re-indexed; chunks now $(jx get "$RESULTS/pdu-status-C-after-private.json" "d['chunks']") (before: $(jx get "$RESULTS/pdu-status-C.json" "d['chunks']"))"
    else
      warn f-withheld "no private sample under $REPO/docs/demo-corpus/B"
    fi
  else
    info f-withheld "private-item test skipped (E2E_PRIVATE_TEST=0); A and C hold no private folder"
  fi
  jx report-pdu "$RESULTS" > "$RESULTS/tables/pdu.md" 2>/dev/null
}

# =============================================================================================================
# g. swarm throughput
# =============================================================================================================
run_batch() { # tag mode phones...
  local tag=$1 mode=$2; shift 2
  local f="$RESULTS/batch-$tag.json" id t0 st r
  ready_cool "g-$tag" "$@" || return 1
  local tha=${TH[A]:-} thc=${TH[C]:-}
  case " $* " in *" C "*) ;; *) thc="-" ;; esac
  call A POST /v1/sched/batch "{\"builtin\":\"bench32\",\"mode\":\"$mode\"}" "$RESULTS/start-batch-$tag.json" 30
  id=$(jx get "$RESULTS/start-batch-$tag.json" "d['id']")
  if [ "$CODE" != 200 ] || [ -z "$id" ]; then fail "g-$tag" "POST /v1/sched/batch gave HTTP $CODE: $(head -c 150 "$RESULTS/start-batch-$tag.json")"; return 1; fi
  t0=$(date +%s)
  while :; do
    sleep 5
    call A GET "/v1/sched/batch/$id" "" "$f" 15
    st=$(jx get "$f" "d['state']")
    [ "$st" = done ] && break
    (( $(date +%s) - t0 > 900 )) && { fail "g-$tag" "batch not done after 15 min ($(jx get "$f" "d['done']")/$(jx get "$f" "d['total']"))"; return 1; }
    note_peak "$@"
  done
  note_peak "$@"
  local eta=${PEAK[A]:-?} etc=${PEAK[C]:-?}; case " $* " in *" C "*) ;; *) etc="-" ;; esac
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$tag" "$mode" "$tha" "$thc" "$eta" "$etc" >> "$RESULTS/batches.tsv"
  r=$(jx batch-assert "$f" "$mode")
  if [ "${r%% *}" = PASS ]; then pass "g-$tag" "$mode: ${r#PASS }; thermal start A=$tha C=$thc, peak A=$eta C=$etc"
  else fail "g-$tag" "$mode: ${r#FAIL }; thermal start A=$tha C=$thc"; fi
  echo "g-$tag	thermal start A=$tha C=$thc	peak A=$eta C=$etc" >> "$RESULTS/thermal.log"
}

step_g() {
  : > "$RESULTS/batches.tsv"
  call A GET /v1/sched/phones "" "$RESULTS/sched-phones.json" 20
  if [ "$(jx get "$RESULTS/sched-phones.json" "sum(1 for p in d if p['up'])" 0)" -lt 2 ] 2>/dev/null; then
    call A POST /v1/sched/debug/peers "{\"ips\":[\"${HP[C]%%:*}\"]}" "$RESULTS/sched-peers.json" 20
    warn g-phones "A saw fewer than two phones up in the scheduler; registered C by IP (HTTP $CODE)"
  fi
  for run in 1 2; do
    run_batch "local-$run" local A
    run_batch "swarm-$run" swarm A C
  done
  jx report-batch "$RESULTS" > "$RESULTS/tables/throughput.md" 2>/dev/null
  tail -n 1 "$RESULTS/tables/throughput.md" | while IFS= read -r l; do info g-ratio "$l"; done
}

# =============================================================================================================
say "e2e-check $(date '+%F %T') results=$RESULTS A=${HP[A]} C=${HP[C]} steps=$STEPS"
for ph in A C; do "$ADB" -s "${SER[$ph]}" get-state </dev/null >/dev/null 2>&1 || warn adb "adb transport ${SER[$ph]} not available; thermal falls back to GET /v1/health"; done
for ph in A C; do resolve_secret "$ph" >/dev/null 2>&1; done
write_meta
step_on a && step_a
step_on b && step_b
step_on c && step_c
step_on d && step_d
step_on e && step_e
step_on f && step_f
step_on g && step_g
write_meta
exit 0
