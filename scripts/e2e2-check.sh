#!/usr/bin/env bash
# Second end-to-end check on the current master: Data Union, Knowledge Bank, swarm throughput, Agent Choir on 2 or 3 phones.
#
#   scripts/e2e2-check.sh [options] <asker-ip[:port]> <peer-ip[:port]> [third-ip[:port]]
#
# The first address is the phone that asks and coordinates (phone B in the run of 2026-10-10), the second the peer, the optional third
# a further peer. Only these addresses (Node API, port 8787 unless given) and adb <ip>:5555 of the same phones are used.
# Every request is signed like scripts/swarm-call.sh does (X-Swarm-Node, X-Swarm-Ts, X-Swarm-Sig = hex HMAC-SHA256(secret,
# "$method\n$rawuri\n$ts\n$body"), X-Swarm-Req) and every answer is checked: X-Swarm-Rsp must equal hex HMAC-SHA256(secret,
# "rsp\n$status\n$reqid\n$body"). Prints PASS / FAIL / WARN / INFO per check; raw responses and markdown tables go under the results dir.
#
# Steps (--steps, default s,d,p,k,t):
#   s  setup: hello, signed health (answer MAC), 401 unsigned, every phone lists every other online, models READY, chat backend
#   d  Data Union: index, private by default, share, the PRD pair (bill photo on the asker, certificate on a peer), electricity,
#      unrelated, Hindi, extra first-time questions, withheld private, the incomplete path (peer app force-stopped), calibration
#   p  a folder that is not shared is withheld (unshare, ask from a peer, share again)
#   k  Knowledge Bank two-phone procedure (docs/benchmarks/2026-10-10-knowledge-bank.md), on the base app
#   t  throughput: bench32 on each phone alone, then all phones together, twice, from a cool start
#   c  Agent Choir evaluation in slices (--pass1 strict scorer without the problem's test, --pass2 with it)
# Options: --results DIR  --chat-file NAME  --keep-models  --slice N  --pass2  --tag NAME (file prefix of the eval slices)
# Environment: SWARM_SECRET (else the debug dev secret, else read from the app's DataStore with run-as), ADB, PYTHON,
#   E2E_COOL_MAX_MIN (longest thermal wait, default 45), E2E_PKG (default com.swarmos.app).
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
COOL_MAX=$(( ${E2E_COOL_MAX_MIN:-45} * 60 ))
STEPS="s,d,p,k,t"
SLICE=5
KEEP_MODELS=0
PASS2=0
TAG="eval"
RESULTS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --steps) STEPS="$2"; shift 2 ;;
    --results) RESULTS="$2"; shift 2 ;;
    --slice) SLICE="$2"; shift 2 ;;
    --chat-file) CHAT_FILE="$2"; shift 2 ;;
    --keep-models) KEEP_MODELS=1; shift ;;
    --pass2) PASS2=1; shift ;;
    --tag) TAG="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ $# -ge 2 ] && [ $# -le 3 ] || { echo "usage: e2e2-check.sh [options] <asker-ip> <peer-ip> [third-ip]" >&2; exit 2; }
N=$#
declare -A HP SER SEC SRC NAME NID TH PEAK
i=0
for a in "$@"; do
  i=$((i + 1))
  printf '%s' "$a" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]+)?$' || { echo "not an IPv4 address: $a" >&2; exit 2; }
  case "$a" in *:*) HP[$i]="$a" ;; *) HP[$i]="$a:8787" ;; esac
  SER[$i]="${HP[$i]%%:*}:5555"; NAME[$i]="phone$i"; NID[$i]=""
done
[ -n "$RESULTS" ] || RESULTS="C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/e2e2-results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS/tables" || exit 2

PASSES=0; FAILS=0; WARNS=0; MODELS_TOUCHED=0; BG_PIDS=""
RSP_OK=0; RSP_BAD=0; RSP_NONE=0

say()  { printf '%s\n' "$*" | tee -a "$RESULTS/checks.log"; }
pass() { PASSES=$((PASSES+1)); say "PASS  $1  $2"; }
fail() { FAILS=$((FAILS+1));   say "FAIL  $1  $2"; }
warn() { WARNS=$((WARNS+1));   say "WARN  $1  $2"; }
info() { say "INFO  $1  $2"; }
jx()   { "$PY" "$HERE/e2e/e2e2.py" "$@"; }
now_ms() { date +%s%3N; }
step_on() { case ",$STEPS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
all() { seq 1 "$N"; }
nm() { echo "${NAME[$1]}"; }

# ---- signed HTTP ----------------------------------------------------------------------------------------

# call <idx> <METHOD> <path-with-query> [body] [outfile] [max-seconds] -> CODE, CALL_MS, RSP (ok|bad|none|skip)
# NOSIG=1 call ... sends no signature headers. A GET that fails at the transport level (000) is repeated up to three times.
call() {
  local i=$1 method=$2 path=$3 body=${4-} out=${5:-$RESULTS/last.json} maxt=${6:-60}
  local ts sig msg nl=$'\n' t0 reqid="" hdr="$RESULTS/hdr.tmp" try got want
  local args=(-sS -m "$maxt" -o "$out" -D "$hdr" -w '%{http_code}' -X "$method")
  if [ "${NOSIG:-0}" != 1 ]; then
    ts=$(now_ms); reqid="e2e2-$(openssl rand -hex 8)"
    msg="${method}${nl}${path}${nl}${ts}${nl}${body}"
    sig=$(printf '%s' "$msg" | openssl dgst -sha256 -hmac "${SEC[$i]:-$DEV_SECRET}" -hex | awk '{print $NF}')
    args+=(-H "X-Swarm-Node: e2e-pc" -H "X-Swarm-Ts: $ts" -H "X-Swarm-Sig: $sig" -H "X-Swarm-Req: $reqid")
  fi
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
  : > "$out"; t0=$(now_ms)
  for try in 1 2 3 4; do
    CODE=$(curl "${args[@]}" "http://${HP[$i]}$path" 2>"$RESULTS/curl.err") || CODE=${CODE:-000}
    [ -n "$CODE" ] || CODE=000
    [ "$CODE" = 000 ] && [ "$method" = GET ] && [ "$try" -lt 4 ] && [ "$maxt" -le 60 ] || break
    sleep 20
  done
  CALL_MS=$(( $(now_ms) - t0 )); RSP=skip
  if [ "${NOSIG:-0}" != 1 ] && [ "$CODE" != 000 ]; then
    got=$(tr -d '\r' < "$hdr" | awk -F': *' 'tolower($1) == "x-swarm-rsp" { v = tolower($2) } END { print v }')
    if [ -z "$got" ]; then RSP=none; RSP_NONE=$((RSP_NONE+1)); echo "no X-Swarm-Rsp: $method $path HTTP $CODE" >> "$RESULTS/rsp-problems.log"
    else
      want=$({ printf 'rsp\n%s\n%s\n' "$CODE" "$reqid"; cat "$out"; } | openssl dgst -sha256 -hmac "${SEC[$i]:-$DEV_SECRET}" -hex | awk '{print $NF}')
      if [ "$got" = "$want" ]; then RSP=ok; RSP_OK=$((RSP_OK+1)); else RSP=bad; RSP_BAD=$((RSP_BAD+1)); echo "WRONG X-Swarm-Rsp: $method $path HTTP $CODE" >> "$RESULTS/rsp-problems.log"; fi
    fi
  fi
}

resolve_secret() { # idx: SWARM_SECRET, the dev secret, or the secret in the app's DataStore (debug build, run-as)
  local i=$1 s
  if [ -n "${SWARM_SECRET:-}" ]; then SEC[$i]="$SWARM_SECRET"; SRC[$i]="SWARM_SECRET"; return 0; fi
  SEC[$i]="$DEV_SECRET"; SRC[$i]="debug dev secret"
  call "$i" GET /v1/health "" "$RESULTS/secret-probe.json" 8
  [ "$CODE" = 200 ] && return 0
  s=$("$ADB" -s "${SER[$i]}" shell "run-as $PKG cat files/datastore/swarm.preferences_pb" </dev/null 2>/dev/null | grep -a -o '[0-9a-f]\{64\}' | head -1)
  [ -n "$s" ] || return 1
  SEC[$i]="$s"; SRC[$i]="secret read from the app DataStore (run-as); the dev secret was rejected"
  call "$i" GET /v1/health "" "$RESULTS/secret-probe.json" 8
  [ "$CODE" = 200 ]
}

# ---- thermal guard, models, app control -----------------------------------------------------------------

thermal_of() {
  local i=$1 t
  t=$("$ADB" -s "${SER[$i]}" shell 'dumpsys thermalservice | grep "Thermal Status" | head -1' </dev/null 2>/dev/null | tr -d '\r' | grep -o '[0-9]\+' | head -1)
  if [ -z "$t" ]; then call "$i" GET /v1/health "" "$RESULTS/health-$i-now.json" 8; t=$(jx get "$RESULTS/health-$i-now.json" "d['thermalStatus']"); fi
  echo "${t:-?}"
}

models_assert() { # idx -> PASS/FAIL line
  call "$1" GET /v1/models/state "" "$RESULTS/models-$1.json" 15
  [ "$CODE" = 200 ] || { echo "FAIL models/state HTTP $CODE"; return; }
  jx models-assert "$RESULTS/models-$1.json" "$CHAT_FILE"
}

wait_models_ready() { # idx seconds
  local i=$1 limit=$2 t0 r; t0=$(date +%s)
  while :; do
    r=$(models_assert "$i"); [ "${r%% *}" = PASS ] && return 0
    (( $(date +%s) - t0 >= limit )) && return 1
    sleep 5
  done
}

ensure_models() { # idx...
  local i r cs es cf rc=0
  for i in "$@"; do
    r=$(models_assert "$i"); [ "${r%% *}" = PASS ] && continue
    MODELS_TOUCHED=1
    cs=$(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['state']"); es=$(jx get "$RESULTS/models-$i.json" "d['roles']['embed']['state']")
    info models "$(nm "$i"): ${r#FAIL }; loading"
    if [ "$cs" != READY ] || [ "$es" != READY ]; then
      call "$i" POST /v1/models/load '{"role":"defaults"}' "$RESULTS/models-load-$i.json" 30
      wait_models_ready "$i" 300 || true
      r=$(models_assert "$i")
    fi
    cf=$(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['file']")
    if [ "${r%% *}" != PASS ] && [ -n "$cf" ] && [ "$cf" != "$CHAT_FILE" ] && [ "$(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['state']")" = READY ]; then
      call "$i" POST /v1/models/load "{\"role\":\"chat\",\"file\":\"$CHAT_FILE\"}" "$RESULTS/models-load-$i.json" 300
      wait_models_ready "$i" 120 || true
    fi
    r=$(models_assert "$i")
    [ "${r%% *}" = PASS ] || { info models "$(nm "$i") still not ready: ${r#FAIL }"; rc=1; }
  done
  return $rc
}

# wait until every listed phone shows Thermal Status 0 or 1 and has its models; TH[i] = start status, PEAK[i] starts there
ready_cool() { # label idx...
  local label=$1; shift
  local waited=0 i st ok
  MODELS_TOUCHED=1
  while :; do
    ok=1
    for i in "$@"; do st=$(thermal_of "$i"); TH[$i]=$st; case "$st" in 0|1) ;; *) ok=0 ;; esac; done
    if [ $ok = 1 ]; then
      ensure_models "$@" || { fail "$label" "models not ready on every phone"; return 1; }
      ok=1
      for i in "$@"; do st=$(thermal_of "$i"); TH[$i]=$st; case "$st" in 0|1) ;; *) ok=0 ;; esac; done
      [ $ok = 1 ] && break
    fi
    if (( waited >= COOL_MAX )); then fail "$label" "thermal status did not reach 0 or 1 within $((COOL_MAX / 60)) min; step not run hot"; return 1; fi
    info "$label" "thermal $(for i in "$@"; do printf '%s=%s ' "$(nm "$i")" "${TH[$i]:-?}"; done): waiting 60 s (waited $waited s)"
    if (( waited == 180 )); then
      for i in "$@"; do case "${TH[$i]:-?}" in 0|1) ;; *) call "$i" POST /v1/models/stop '{"role":"all"}' "$RESULTS/models-stop-$i.json" 30; info "$label" "$(nm "$i") still hot after 3 min: model servers stopped while it cools" ;; esac; done
    fi
    sleep 60; waited=$((waited + 60))
  done
  for i in "$@"; do PEAK[$i]=${TH[$i]}; echo "$(date +%T) $label $(nm "$i") start-thermal=${TH[$i]}" >> "$RESULTS/thermal.log"; done
  return 0
}

th_str() { local i s=""; for i in "$@"; do s="$s$(nm "$i")=${TH[$i]:-?} "; done; echo "${s% }"; }
note_peak() { local i st; for i in "$@"; do st=$(thermal_of "$i"); case "$st" in [0-9]) [ "$st" -gt "${PEAK[$i]:-0}" ] && PEAK[$i]=$st ;; esac; done; }

launch_app() { # idx   (name, setup done, models autoload)
  "$ADB" -s "${SER[$1]}" shell "am start -n $PKG/.MainActivity --es name '${NAME[$1]}' --ez setupDone true --ez autoload true" </dev/null >/dev/null 2>&1
}
stop_app() { "$ADB" -s "${SER[$1]}" shell "am force-stop $PKG" </dev/null >/dev/null 2>&1; }

wait_api() { # idx seconds -> 0 when hello answers
  local i=$1 limit=$2 t0; t0=$(date +%s)
  while (( $(date +%s) - t0 < limit )); do
    NOSIG=1 call "$i" GET /v1/hello "" "$RESULTS/hello-wait.tmp" 5
    [ "$CODE" = 200 ] && return 0; sleep 3
  done; return 1
}

# poll until phone idx lists phone j with the given state; prints the seconds it took, returns 1 on timeout
wait_peer() { # idx j state seconds
  local i=$1 j=$2 want=$3 limit=$4 t0 st; t0=$(now_ms)
  while (( $(now_ms) - t0 < limit * 1000 )); do
    call "$i" GET /v1/link/peers "" "$RESULTS/peers-$i.json" 10
    if [ "$CODE" = 200 ]; then
      st=$(jx get "$RESULTS/peers-$i.json" "[p['state'] for p in d['peers'] if p['nodeId'] == '${NID[$j]}']")
      case "$want" in
        online) case "$st" in *online*) echo $(( $(now_ms) - t0 )); return 0 ;; esac ;;
        away) case "$st" in *online*) ;; *) echo $(( $(now_ms) - t0 )); return 0 ;; esac ;;
      esac
    fi
    sleep 1
  done
  return 1
}

finish() {
  trap - EXIT INT TERM
  [ -n "$BG_PIDS" ] && kill $BG_PIDS 2>/dev/null
  if [ "$MODELS_TOUCHED" = 1 ] && [ "$KEEP_MODELS" = 0 ]; then
    for i in $(all); do call "$i" POST /v1/models/stop '{"role":"all"}' "$RESULTS/models-stop-final-$i.json" 30; echo "models stopped on $(nm "$i"): HTTP $CODE" >> "$RESULTS/checks.log"; done
  fi
  say "RESPONSE-MAC  ok=$RSP_OK wrong=$RSP_BAD missing=$RSP_NONE (calls made from the main shell only; problems in rsp-problems.log)"
  say "SUMMARY  pass=$PASSES fail=$FAILS warn=$WARNS  results=$RESULTS"
  [ "$FAILS" = 0 ]; exit $?
}
trap 'finish' EXIT
trap 'say "INTERRUPTED"; FAILS=$((FAILS+1)); finish' INT TERM

write_meta() {
  {
    echo "date: $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "repo head: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null) ($(git -C "$REPO" log -1 --format=%s 2>/dev/null | cut -c1-80))"
    echo "steps: $STEPS; chat file $CHAT_FILE"
    local i
    for i in $(all); do
      echo "phone $i: ${NAME[$i]} ${HP[$i]} node ${NID[$i]}; secret: ${SRC[$i]:-unresolved}"
      "$ADB" -s "${SER[$i]}" shell "dumpsys package $PKG | grep -E 'versionName|lastUpdateTime'; ls -l /sdcard/Android/data/$PKG/files/models/ | tr -s ' ' | cut -d' ' -f5-" </dev/null 2>/dev/null | tr -d '\r' | sed 's/^/  /'
    done
  } > "$RESULTS/meta.txt"
}

# =============================================================================================================
# s. setup checks
# =============================================================================================================
step_s() {
  local i j r el
  for i in $(all); do
    NOSIG=1 call "$i" GET /v1/hello "" "$RESULTS/hello-$i.json" 10
    if [ "$CODE" = 200 ]; then
      NAME[$i]=$(jx get "$RESULTS/hello-$i.json" "d['name']"); NID[$i]=$(jx get "$RESULTS/hello-$i.json" "d['nodeId']")
      pass "s1-hello-$i" "${NAME[$i]} node ${NID[$i]}: HTTP 200 in $CALL_MS ms, chat '$(jx get "$RESULTS/hello-$i.json" "d['chatModel']")', embed '$(jx get "$RESULTS/hello-$i.json" "d['embedModel']")'"
    else fail "s1-hello-$i" "GET /v1/hello gave HTTP $CODE on ${HP[$i]}"; continue; fi
    if resolve_secret "$i"; then
      call "$i" GET /v1/health "" "$RESULTS/health-$i.json" 10
      if [ "$CODE" = 200 ]; then
        pass "s2-health-$i" "${NAME[$i]}: signed GET /v1/health HTTP 200 in $CALL_MS ms (secret: ${SRC[$i]}; answer MAC: $RSP); thermal $(jx get "$RESULTS/health-$i.json" "d['thermalStatus']"), battery $(jx get "$RESULTS/health-$i.json" "d['batteryPct']") %, link $(jx get "$RESULTS/health-$i.json" "d['linkStandard']") $(jx get "$RESULTS/health-$i.json" "d['linkSpeedMbps']") Mbps"
        [ "$RSP" = ok ] || fail "s2-rsp-$i" "answer MAC of the signed health call is '$RSP'"
      else fail "s2-health-$i" "signed GET /v1/health gave HTTP $CODE"; fi
    else fail "s2-health-$i" "no secret signs successfully"; fi
    NOSIG=1 call "$i" GET /v1/health "" "$RESULTS/unsigned-$i.json" 10
    [ "$CODE" = 401 ] && pass "s3-unsigned-$i" "${NAME[$i]}: unsigned GET /v1/health -> 401" || fail "s3-unsigned-$i" "${NAME[$i]}: unsigned GET /v1/health gave HTTP $CODE, expected 401"
  done
  for i in $(all); do
    for j in $(all); do
      [ "$i" = "$j" ] && continue
      if el=$(wait_peer "$i" "$j" online 20); then pass "s4-peer-$i-$j" "${NAME[$i]} lists ${NAME[$j]} online after $el ms"
      else fail "s4-peer-$i-$j" "${NAME[$i]} does not list ${NAME[$j]} online within 20 s"; fi
    done
  done
  for i in $(all); do
    r=$(models_assert "$i")
    if [ "${r%% *}" != PASS ]; then info "s5-models-$i" "${NAME[$i]}: ${r#FAIL }; loading"; ensure_models "$i"; r=$(models_assert "$i"); fi
    if [ "${r%% *}" = PASS ]; then
      pass "s5-models-$i" "${NAME[$i]}: ${r#PASS }"
      [ "$(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['backend']")" = GPU ] && pass "s6-backend-$i" "${NAME[$i]}: chat backend GPU" || warn "s6-backend-$i" "${NAME[$i]}: chat backend is $(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['backend']"), expected GPU; notice: $(jx get "$RESULTS/models-$i.json" "d['roles']['chat']['notice']")"
    else fail "s5-models-$i" "${NAME[$i]}: ${r#FAIL }"; fi
  done
}

# =============================================================================================================
# d. Data Union
# =============================================================================================================
corpus_name() { if "$ADB" -s "${SER[$1]}" shell "ls -d /sdcard/Pictures/UstaadDemo" </dev/null >/dev/null 2>&1; then echo UstaadDemo; else echo SwarmDemo; fi; }

index_dir() { # idx dir force tag
  local i=$1 dir=$2 force=$3 tag=$4 f="$RESULTS/pdu-index-$1-$4.json" t0
  call "$i" POST /v1/pdu/index "{\"dir\":\"$dir\",\"force\":$force}" "$f" 900
  if [ "$CODE" != 200 ] && [ "$CODE" != 409 ]; then
    info "d-index-$i" "HTTP $CODE; waiting for the index run to end"; t0=$(date +%s)
    while (( $(date +%s) - t0 < 600 )); do
      call "$i" GET /v1/pdu/status "" "$RESULTS/pdu-status-poll-$i.json" 15
      [ "$(jx get "$RESULTS/pdu-status-poll-$i.json" "d['running']")" = false ] && break; sleep 10
    done
    "$PY" -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); json.dump(d.get('lastIndex') or {}, open(sys.argv[2],'w',encoding='utf-8'))" "$RESULTS/pdu-status-poll-$i.json" "$f"
  fi
}

share_folder() { # idx folder [true|false]   (the debug route stands in for the owner's switch in the library)
  local i=$1 folder=$2 want=${3:-true} t0 cond="not x['isPrivate']"
  [ "$want" = false ] && cond="x['isPrivate']"
  call "$i" POST /v1/pdu/debug/share "{\"folder\":\"$folder\",\"shared\":$want}" "$RESULTS/pdu-share-$i.json" 20
  [ "$CODE" = 200 ] || { warn "d-share-$i" "POST /v1/pdu/debug/share $folder gave HTTP $CODE"; return 1; }
  t0=$(date +%s)
  while (( $(date +%s) - t0 < 20 )); do
    call "$i" GET /v1/pdu/status "" "$RESULTS/pdu-status-$i.json" 15
    [ "$(jx get "$RESULTS/pdu-status-$i.json" "any(x['folder'] == '$folder' and $cond for x in d['folders'])")" = true ] && return 0; sleep 1
  done
  warn "d-share-$i" "folder $folder not $( [ "$want" = true ] && echo shared || echo private ) after 20 s"; return 1
}

# ask <from-idx> <id> <question-key> <kind>   (kind: relevant | none | any | incomplete); every ask waits for a cool start first
ask() {
  local i=$1 id=$2 key=$3 kind=$4 f="$RESULTS/pdu-ask-$2.json" body r
  if [ "$kind" = incomplete ]; then TH[$i]=$(thermal_of "$i"); else ready_cool "d-$id" $(all) || return 1; fi
  body=$(jx ask-body "$key" 5 1500)
  call "$i" POST /v1/pdu/ask "$body" "$f" 240
  r=$(jx ask-assert "$f" "$kind")
  local th; th="thermal start $(th_str $(all))"
  if [ "${r%% *}" = PASS ]; then pass "d-$id" "from $(nm "$i"): '$(jx question "$key")' -> ${r#PASS }; $th; answer MAC $RSP"
  else fail "d-$id" "from $(nm "$i"): '$(jx question "$key")' -> ${r#FAIL }; $th"; fi
  echo "pdu-ask-$id	$th" >> "$RESULTS/thermal.log"
  [ "$RSP" = ok ] || [ "$RSP" = skip ] || warn "d-$id-rsp" "answer MAC '$RSP'"
}

has_src() { # file expr-on-s   -> true/false
  [ "$(jx get "$1" "any($2 for s in d['sources'])")" = true ]
}

step_d() {
  local i nm_ st cert="" pushed=0 hasA=0 w
  for i in $(all); do # indexing a folder over adb needs all-files access for the app
    st=$("$ADB" -s "${SER[$i]}" shell "appops get $PKG MANAGE_EXTERNAL_STORAGE" </dev/null | tr -d '\r')
    case "$st" in *allow*) info "d-perm-$i" "$(nm "$i"): all-files access already allowed" ;;
      *) "$ADB" -s "${SER[$i]}" shell "appops set $PKG MANAGE_EXTERNAL_STORAGE allow" </dev/null; warn "d-perm-$i" "$(nm "$i"): was '${st//$'\n'/ }'; set MANAGE_EXTERNAL_STORAGE allow for $PKG (left in place)" ;; esac
    [ "${NAME[$i]}" = "Phone A" ] && hasA=1
  done
  ready_cool d-index $(all) || return
  declare -A CN
  for i in $(all); do
    CN[$i]=$(corpus_name "$i")
    if [ "$i" = 2 ] && [ $hasA = 0 ]; then # no phone holds the certificate: put the repo sample on the second phone for this run
      cert=$(ls "$REPO"/docs/demo-corpus/A/Documents/*/Laptop_Extended_Warranty_Certificate.pdf 2>/dev/null | head -1)
      if [ -n "$cert" ]; then "$ADB" -s "${SER[$i]}" push "$cert" "/sdcard/Documents/${CN[$i]}/" </dev/null >/dev/null 2>&1 && pushed=1 && warn d-cert "Phone A is not in this run: the sample Laptop_Extended_Warranty_Certificate.pdf was pushed to $(nm "$i") (Documents/${CN[$i]}) for the acceptance pair and is removed at the end"; fi
    fi
    index_dir "$i" "/sdcard/Pictures/${CN[$i]}" true pictures
    index_dir "$i" "/sdcard/Documents/${CN[$i]}" false documents
    call "$i" GET /v1/pdu/status "" "$RESULTS/pdu-status-$i.json" 15
    cp "$RESULTS/pdu-status-$i.json" "$RESULTS/pdu-status-$i-as-indexed.json"
    local emb chunks errs priv
    emb=$(jx get "$RESULTS/pdu-status-$i.json" "d['embedModel']"); chunks=$(jx get "$RESULTS/pdu-status-$i.json" "d['chunks']" 0)
    priv=$(jx get "$RESULTS/pdu-status-$i.json" "sum(1 for x in d['folders'] if x['isPrivate'])" 0)
    errs="$(jx get "$RESULTS/pdu-index-$i-pictures.json" "d['error']") $(jx get "$RESULTS/pdu-index-$i-documents.json" "d['error']")"
    if [ -n "${errs// /}" ]; then fail "d-index-$i" "$(nm "$i"): index error: $errs"
    elif [ -z "$emb" ] || [ "$emb" = FAKE ]; then fail "d-index-$i" "$(nm "$i"): store built with embedder '$emb'"
    elif [ "$chunks" -lt 1 ]; then fail "d-index-$i" "$(nm "$i"): no chunks"
    else pass "d-index-$i" "$(nm "$i") folders ${CN[$i]}: $chunks chunks with '$emb'; pictures $(jx get "$RESULTS/pdu-index-$i-pictures.json" "d['indexed']") of $(jx get "$RESULTS/pdu-index-$i-pictures.json" "d['seen']") in $(jx get "$RESULTS/pdu-index-$i-pictures.json" "d['totalMs']") ms (OCR avg $(jx get "$RESULTS/pdu-index-$i-pictures.json" "d['ocrMsAvg']") ms), documents $(jx get "$RESULTS/pdu-index-$i-documents.json" "d['indexed']") of $(jx get "$RESULTS/pdu-index-$i-documents.json" "d['seen']") in $(jx get "$RESULTS/pdu-index-$i-documents.json" "d['totalMs']") ms; folders private as indexed: $priv of $(jx get "$RESULTS/pdu-status-$i.json" "len(d['folders'])" 0)"; fi
  done

  # every folder starts private. Only the first phone has its first-install state (the others keep the sharing choice of an earlier run),
  # so the default is tested on it: the second phone asks for something that only the first phone holds, before anything is shared there.
  ask 2 pre-share-restaurant restaurant any
  w=$(jx get "$RESULTS/pdu-ask-pre-share-restaurant.json" "sum(x['withheldPrivate'] for x in d['devices'] if x['phone'] == '$(nm 1)')" 0)
  info d-private-default "folders private as indexed: $(for i in $(all); do printf '%s=%s of %s ' "$(nm "$i")" "$(jx get "$RESULTS/pdu-status-$i-as-indexed.json" "sum(1 for x in d['folders'] if x['isPrivate'])" 0)" "$(jx get "$RESULTS/pdu-status-$i-as-indexed.json" "len(d['folders'])" 0)"; done)"
  if [ "$w" -ge 1 ] && [ "$(jx get "$RESULTS/pdu-ask-pre-share-restaurant.json" "any(s['phone'] == '$(nm 1)' for s in d['sources'])")" != true ]; then
    pass d-private-default "before $(nm 1) shared anything, it withheld $w private item(s) from $(nm 2) and none of its files reached $(nm 2)"
  else fail d-private-default "before sharing: withheld by $(nm 1) = $w, or a file of $(nm 1) was shown to $(nm 2)"; fi
  for i in $(all); do share_folder "$i" "Pictures/${CN[$i]}" && share_folder "$i" "Documents/${CN[$i]}" && info "d-share-$i" "$(nm "$i"): Pictures/${CN[$i]} and Documents/${CN[$i]} marked shared (debug route)"; done

  ask 1 warranty-1 warranty relevant
  if has_src "$RESULTS/pdu-ask-warranty-1.json" "'IMG_2231' in s['title'] and s['local']"; then pass d-cite-bill "the bill photo IMG_2231.jpg cited as $(nm 1) (local)"; else fail d-cite-bill "the bill photo on $(nm 1) is not among the cited sources"; fi
  if has_src "$RESULTS/pdu-ask-warranty-1.json" "'Warranty_Certificate' in s['title'] and not s['local']"; then pass d-cite-certificate "Laptop_Extended_Warranty_Certificate.pdf cited from a peer ($(jx get "$RESULTS/pdu-ask-warranty-1.json" "[s['phone'] for s in d['sources'] if 'Warranty_Certificate' in s['title']][0]"))"; else fail d-cite-certificate "the certificate on a peer is not among the cited sources"; fi
  ask 1 electricity electricity relevant
  has_src "$RESULTS/pdu-ask-electricity.json" "'Electricity' in s['title'] and not s['local']" && pass d-cite-peer "IMG_3301_Electricity_Bill.jpg cited from a peer" || fail d-cite-peer "the electricity bill on a peer is not among the cited sources"
  ask 1 unrelated unrelated none
  ask 1 hindi-warranty hindi_warranty relevant
  ask 1 hindi-note hindi_note any
  ask 1 restaurant restaurant any
  ask 1 yoga yoga any
  ask 1 cab cab any
  ask 1 warranty-2-repeat warranty relevant
  ask 2 salary-from-peer salary any
  w=$(jx get "$RESULTS/pdu-ask-salary-from-peer.json" "sum(x['withheldPrivate'] for x in d['devices'] if not x['local'])" 0)
  if [ "$w" -ge 1 ] && [ "$(jx get "$RESULTS/pdu-ask-salary-from-peer.json" "any('alary' in s['title'] for s in d['sources'])")" != true ]; then pass d-withheld "asked from $(nm 2): $(nm 1) withheld $w private item(s) (its Private folder) and no salary source reached $(nm 2)"
  else fail d-withheld "asked from $(nm 2): withheld by peers = $w, or a salary source was shown"; fi

  for i in $(all); do
    for key in warranty unrelated hindi_warranty; do
      call "$i" POST /v1/pdu/debug/scores "$(jx ask-body "$key" 5)" "$RESULTS/pdu-scores-$i-$key.json" 60
      [ "$CODE" = 200 ] || warn "d-scores-$i-$key" "HTTP $CODE"
    done
  done
  pass d-scores "calibration recorded for 3 questions on every phone"

  # the incomplete path: the peer app is force-stopped, then asked
  TH[2]=$(thermal_of 2); ready_cool d-incomplete $(all) || return
  stop_app 2; info d-incomplete "$(nm 2) app force-stopped at $(date +%T)"
  ask 1 incomplete-electricity electricity incomplete
  launch_app 2; wait_api 2 90 && info d-incomplete "$(nm 2) app relaunched, API back" || warn d-incomplete "$(nm 2) API did not come back within 90 s"
  wait_peer 1 2 online 60 >/dev/null && info d-incomplete "$(nm 1) lists $(nm 2) online again" || warn d-incomplete "$(nm 1) does not list $(nm 2) online again within 60 s"

  if [ $pushed = 1 ]; then # take the sample certificate away again and rebuild that phone's index from its own folders
    "$ADB" -s "${SER[2]}" shell "rm -f /sdcard/Documents/${CN[2]}/Laptop_Extended_Warranty_Certificate.pdf" </dev/null
    ready_cool d-restore 2 >/dev/null
    index_dir 2 "/sdcard/Pictures/${CN[2]}" true pictures-restore; index_dir 2 "/sdcard/Documents/${CN[2]}" false documents-restore
    call 2 GET /v1/pdu/status "" "$RESULTS/pdu-status-2-after.json" 15
    info d-cert "sample certificate removed from $(nm 2); chunks now $(jx get "$RESULTS/pdu-status-2-after.json" "d['chunks']") (before: $(jx get "$RESULTS/pdu-status-as-indexed-2.json" "d['chunks']"))"
  fi
  jx report-pdu "$RESULTS" > "$RESULTS/tables/pdu.md" 2>/dev/null
}

# =============================================================================================================
# p. a folder that is not shared is withheld (the first-install default cannot be re-tested: sharing choices persist in the database)
# =============================================================================================================
step_p() {
  local nm_ w
  nm_=$(corpus_name 1)
  share_folder 1 "Pictures/$nm_" false || return
  info p-unshare "$(nm 1): Pictures/$nm_ set to private through the debug route"
  ask 2 unshared-restaurant restaurant any
  w=$(jx get "$RESULTS/pdu-ask-unshared-restaurant.json" "sum(x['withheldPrivate'] for x in d['devices'] if x['phone'] == '$(nm 1)')" 0)
  if [ "$w" -ge 1 ] && [ "$(jx get "$RESULTS/pdu-ask-unshared-restaurant.json" "any(s['phone'] == '$(nm 1)' for s in d['sources'])")" != true ]; then
    pass p-withheld "with its Pictures folder private $(nm 1) withheld $w item(s) from $(nm 2) and none of its files reached $(nm 2)"
  else fail p-withheld "withheld by $(nm 1) = $w, or a file of $(nm 1) was shown to $(nm 2)"; fi
  share_folder 1 "Pictures/$nm_" true && info p-reshare "$(nm 1): Pictures/$nm_ shared again"
}

# =============================================================================================================
# k. Knowledge Bank
# =============================================================================================================
step_k() {
  local f="$RESULTS/kb-real-answer.json" id kept r t0 el b
  ready_cool k-real-answer 1 2 || return
  call 1 POST /v1/pdu/ask "$(jx ask-body warranty 5 1500)" "$f" 240; r=$(jx ask-assert "$f" relevant)
  [ "${r%% *}" = PASS ] && info k-real-answer "real answer on $(nm 1): ${r#PASS }; thermal $(th_str 1 2)" || { fail k-real-answer "no real answer to save: ${r#FAIL }"; return; }
  # 1. save: a shared card from the real answer, a kept card, a card that must refuse to be shared
  call 1 POST /v1/kb/debug/save "$(jx kb-save-body "$f" true)" "$RESULTS/kb-save-shared.json" 30
  id=$(jx get "$RESULTS/kb-save-shared.json" "d['id']")
  if [ "$CODE" = 200 ] && [ -n "$id" ] && [ "$(jx get "$RESULTS/kb-save-shared.json" "d['tier']")" = SWARM ] && [ -n "$(jx get "$RESULTS/kb-save-shared.json" "d['auditHash']")" ]; then pass k1-save-shared "card $id saved on $(nm 1), tier SWARM, audit hash $(jx get "$RESULTS/kb-save-shared.json" "d['auditHash'][:16]")..., answer MAC $RSP"
  else fail k1-save-shared "HTTP $CODE: $(head -c 200 "$RESULTS/kb-save-shared.json")"; return; fi
  call 1 POST /v1/kb/debug/save '{"question":"What is my salary?","answer":"Kept on this phone only [1].","sources":[{"phone":"x","title":"Salary_Slip.pdf","dateMs":1760000000000,"priv":true}],"share":false}' "$RESULTS/kb-save-kept.json" 30
  [ "$CODE" = 200 ] && [ "$(jx get "$RESULTS/kb-save-kept.json" "d['tier']")" = PRIVATE ] && pass k1-save-kept "kept card saved with tier PRIVATE" || fail k1-save-kept "HTTP $CODE: $(head -c 200 "$RESULTS/kb-save-kept.json")"
  call 1 POST /v1/kb/debug/save '{"question":"Share the salary?","answer":"No [1].","sources":[{"phone":"x","title":"Salary_Slip.pdf","dateMs":1760000000000,"priv":true}],"share":true}' "$RESULTS/kb-save-refused.json" 30
  [ "$CODE" = 409 ] && pass k1-save-refused "sharing a card with a private source refused (HTTP 409: $(jx get "$RESULTS/kb-save-refused.json" "d['error']"))" || fail k1-save-refused "expected HTTP 409, got $CODE: $(head -c 200 "$RESULTS/kb-save-refused.json")"
  # 2/3. does it replicate by itself, and does only the shared card arrive
  t0=$(now_ms); el=""
  while (( $(now_ms) - t0 < 60000 )); do
    call 2 GET /v1/kb/cards "" "$RESULTS/kb-cards-2.json" 15
    [ -n "$(jx kb-card "$RESULTS/kb-cards-2.json" "$id" id)" ] && { el=$(( $(now_ms) - t0 )); break; }; sleep 2
  done
  if [ -n "$el" ]; then pass k2-replicate "card $id reached $(nm 2) by itself after $el ms (no sync call made)"
  else
    warn k2-replicate "card did not reach $(nm 2) within 60 s on its own; calling POST /v1/kb/debug/sync"
    call 1 POST /v1/kb/debug/sync "{\"peer\":\"${NID[2]}\"}" "$RESULTS/kb-sync-1.json" 60
    info k2-replicate "sync: sent $(jx get "$RESULTS/kb-sync-1.json" "d['sentCards']") got $(jx get "$RESULTS/kb-sync-1.json" "d['gotCards']") rejected $(jx get "$RESULTS/kb-sync-1.json" "d['rejected']") error $(jx get "$RESULTS/kb-sync-1.json" "d['error']")"
    call 2 GET /v1/kb/cards "" "$RESULTS/kb-cards-2.json" 15
    [ -n "$(jx kb-card "$RESULTS/kb-cards-2.json" "$id" id)" ] && pass k2-replicate-sync "card present on $(nm 2) after the explicit sync" || fail k2-replicate-sync "card still missing on $(nm 2)"
  fi
  r=$(jx get "$RESULTS/kb-cards-2.json" "len(d)" 0)
  [ "$r" = 1 ] && pass k3-only-shared "$(nm 2) holds exactly 1 card (author '$(jx kb-card "$RESULTS/kb-cards-2.json" "$id" author)', tier $(jx kb-card "$RESULTS/kb-cards-2.json" "$id" tier)); the kept and the refused card did not travel" || fail k3-only-shared "$(nm 2) holds $r cards, expected 1"
  call 1 GET /v1/kb/cards "" "$RESULTS/kb-cards-1.json" 15
  info k3-kept-hidden "on $(nm 1) the kept card shows as: $(jx get "$RESULTS/kb-cards-1.json" "[c['question'] for c in d if c['tier'] == 'PRIVATE']")"
  # 4. author app force-stopped
  stop_app 1; t0=$(date +%s); info k4-stop "$(nm 1) app force-stopped at $(date +%T)"
  if el=$(wait_peer 2 1 away 60); then pass k4-away "$(nm 2) marks $(nm 1) away after $el ms"; else fail k4-away "$(nm 2) still lists $(nm 1) online after 60 s"; fi
  NOSIG=1 call 1 GET /v1/hello "" "$RESULTS/k4-hello.tmp" 6; [ "$CODE" = 200 ] && fail k4-unreachable "$(nm 1) still answers after force-stop" || pass k4-unreachable "$(nm 1) API is down (HTTP $CODE)"
  # 5. ask on the other phone
  call 2 POST /v1/pdu/ask "$(jx ask-body warranty 5 1500)" "$RESULTS/kb-ask-saved.json" 120
  b="$RESULTS/kb-ask-saved.json"
  if [ "$(jx get "$b" "d['fromCard']")" = "$id" ]; then
    pass k5-from-card "$(nm 2) answered from saved card $id with $(nm 1) away: total $(jx get "$b" "d['timings']['totalMs']") ms, first token $(jx get "$b" "d['timings']['ttftMs']") ms, model '$(jx get "$b" "d['model']")', sources $(jx get "$b" "len(d['sources'])" 0), devices $(jx get "$b" "len(d['devices'])" 0), incomplete $(jx get "$b" "d['incomplete']"); answer MAC $RSP"
    info k5-answer "answer text: $(jx get "$b" "d['answer']")"
    info k5-provenance "sources as shown: $(jx get "$b" "[(s['phone'], s['title']) for s in d['sources']]"); note: $(jx get "$b" "d['note']")"
  else fail k5-from-card "fromCard='$(jx get "$b" "d['fromCard']")' (expected $id); incomplete $(jx get "$b" "d['incomplete']"); total $(jx get "$b" "d['timings']['totalMs']") ms; note $(jx get "$b" "d['note']")"; fi
  ready_cool k5-control 2 >/dev/null
  call 2 POST /v1/pdu/ask "$(jx ask-body warranty 5 1500 | sed 's/}$/, "useSaved": false}/')" "$RESULTS/kb-ask-live.json" 240
  b="$RESULTS/kb-ask-live.json"
  info k5-control "live search (useSaved false), $(nm 1) away: incomplete $(jx get "$b" "d['incomplete']"), cardIds $(jx get "$b" "d['cardIds']"), fromCard '$(jx get "$b" "d['fromCard']")', sources $(jx get "$b" "[(s['phone'], s['title']) for s in d['sources']]"), devices $(jx get "$b" "[(x['phone'], x['status']) for x in d['devices']]"), total $(jx get "$b" "d['timings']['totalMs']") ms"
  # 6. revoke
  launch_app 1; wait_api 1 90 || warn k6-relaunch "$(nm 1) API did not come back within 90 s"
  if el=$(wait_peer 2 1 online 90); then info k6-relaunch "$(nm 2) lists $(nm 1) online again after $el ms"; else warn k6-relaunch "$(nm 2) does not list $(nm 1) online within 90 s"; fi
  call 1 POST /v1/kb/debug/revoke "{\"id\":\"$id\"}" "$RESULTS/kb-revoke.json" 30
  [ "$CODE" = 200 ] && pass k6-revoke "card revoked on $(nm 1)" || { fail k6-revoke "HTTP $CODE: $(head -c 200 "$RESULTS/kb-revoke.json")"; }
  t0=$(now_ms); el=""
  while (( $(now_ms) - t0 < 60000 )); do
    call 2 GET /v1/kb/cards "" "$RESULTS/kb-cards-2b.json" 15
    [ -n "$(jx kb-card "$RESULTS/kb-cards-2b.json" "$id" revokedMs)" ] && { el=$(( $(now_ms) - t0 )); break; }; sleep 2
  done
  if [ -n "$el" ]; then pass k6-revoke-replicates "revocation reached $(nm 2) by itself after $el ms"
  else
    warn k6-revoke-replicates "revocation did not reach $(nm 2) within 60 s on its own; calling POST /v1/kb/debug/sync"
    call 1 POST /v1/kb/debug/sync "{\"peer\":\"${NID[2]}\"}" "$RESULTS/kb-sync-2.json" 60
    info k6-revoke-replicates "sync: sentTombstones $(jx get "$RESULTS/kb-sync-2.json" "d['sentTombstones']") rejected $(jx get "$RESULTS/kb-sync-2.json" "d['rejected']") error $(jx get "$RESULTS/kb-sync-2.json" "d['error']")"
    call 2 GET /v1/kb/cards "" "$RESULTS/kb-cards-2b.json" 15
    [ -n "$(jx kb-card "$RESULTS/kb-cards-2b.json" "$id" revokedMs)" ] && pass k6-revoke-sync "revoked on $(nm 2) after the explicit sync" || fail k6-revoke-sync "card on $(nm 2) not revoked after sync"
  fi
  ready_cool k6-ask 2 >/dev/null
  call 2 POST /v1/pdu/ask "$(jx ask-body warranty 5 1500)" "$RESULTS/kb-ask-revoked.json" 240; b="$RESULTS/kb-ask-revoked.json"
  if [ -z "$(jx get "$b" "d['fromCard']")" ] && [ "$(jx get "$b" "'$id' in d['cardIds']")" != true ]; then pass k6-card-unused "after revocation $(nm 2) no longer uses the card (fromCard empty, cardIds $(jx get "$b" "d['cardIds']")); live answer in $(jx get "$b" "d['timings']['totalMs']") ms"
  else fail k6-card-unused "after revocation: fromCard '$(jx get "$b" "d['fromCard']")', cardIds $(jx get "$b" "d['cardIds']")"; fi
  # 7. tampering
  call 2 GET /v1/kb/cards "" "$RESULTS/kb-cards-2c.json" 15
  call 1 POST /v1/kb/cards "$(jx kb-tamper-body "$RESULTS/kb-cards-2c.json" "$id")" "$RESULTS/kb-tamper.json" 30
  if [ "$(jx get "$RESULTS/kb-tamper.json" "d['accepted']")" = 0 ] && [ "$(jx get "$RESULTS/kb-tamper.json" "any('id does not match' in x for x in d['rejected'])")" = true ]; then pass k7-tamper "tampered card refused by $(nm 1): $(jx get "$RESULTS/kb-tamper.json" "d['rejected']")"
  else fail k7-tamper "HTTP $CODE: $(head -c 300 "$RESULTS/kb-tamper.json")"; fi
  NOSIG=1 call 1 POST /v1/kb/cards '{"cards":[]}' "$RESULTS/kb-unsigned.json" 10; [ "$CODE" = 401 ] && pass k7-unsigned "unsigned POST /v1/kb/cards -> 401" || fail k7-unsigned "unsigned POST gave HTTP $CODE"
  # 8. audit entries
  for i in 1 2; do
    "$ADB" -s "${SER[$i]}" shell "run-as $PKG cat files/audit.jsonl" </dev/null 2>/dev/null | tr -d '\r' > "$RESULTS/audit-$i.jsonl"
    info "k8-audit-$i" "$(nm "$i"): kb.save $(grep -c '"kb.save"' "$RESULTS/audit-$i.jsonl"), kb.revoke $(grep -c '"kb.revoke"' "$RESULTS/audit-$i.jsonl"), kb.sync $(grep -c '"kb.sync"' "$RESULTS/audit-$i.jsonl"), pdu.ask_saved $(grep -c '"pdu.ask_saved"' "$RESULTS/audit-$i.jsonl")"
  done
  grep -q "$id" "$RESULTS/audit-1.jsonl" && pass k8-audit-id "card id $id appears in the audit log of $(nm 1)" || warn k8-audit-id "card id not found in the audit log of $(nm 1)"
}

# =============================================================================================================
# t. swarm throughput
# =============================================================================================================
run_batch() { # tag mode coordinator-idx idx...
  local tag=$1 mode=$2 c=$3; shift 3
  local f="$RESULTS/batch-$tag.json" id t0 st r phones=$#
  ready_cool "t-$tag" "$@" || return 1
  local ths; ths=$(th_str "$@")
  call "$c" POST /v1/sched/batch "{\"builtin\":\"bench32\",\"mode\":\"$mode\"}" "$RESULTS/start-batch-$tag.json" 30
  id=$(jx get "$RESULTS/start-batch-$tag.json" "d['id']")
  if [ "$CODE" != 200 ] || [ -z "$id" ]; then fail "t-$tag" "POST /v1/sched/batch gave HTTP $CODE: $(head -c 150 "$RESULTS/start-batch-$tag.json")"; return 1; fi
  t0=$(date +%s)
  while :; do
    sleep 5; call "$c" GET "/v1/sched/batch/$id" "" "$f" 15; st=$(jx get "$f" "d['state']")
    [ "$st" = done ] && break
    (( $(date +%s) - t0 > 900 )) && { fail "t-$tag" "batch not done after 15 min ($(jx get "$f" "d['done']")/$(jx get "$f" "d['total']"))"; return 1; }
    note_peak "$@"
  done
  note_peak "$@"
  local pk=""; for i in "$@"; do pk="$pk$(nm "$i")=${PEAK[$i]} "; done
  local be; be=$(jx get "$RESULTS/models-$c.json" "d['roles']['chat']['backend']")
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$tag" "$mode" "$(nm "$c")" "$ths" "${pk% }" "$be" >> "$RESULTS/batches.tsv"
  r=$(jx batch-assert "$f" "$mode" "$phones")
  if [ "${r%% *}" = PASS ]; then pass "t-$tag" "$mode on $phones phone(s), coordinator $(nm "$c"): ${r#PASS }; thermal start $ths, peak ${pk% }; backend $be; answer MAC $RSP"
  else fail "t-$tag" "$mode: ${r#FAIL }; thermal start $ths"; fi
  echo "t-$tag	thermal start $ths	peak ${pk% }" >> "$RESULTS/thermal.log"
}

step_t() {
  : > "$RESULTS/batches.tsv"
  local i fastest="" best=0 v
  for i in $(all); do run_batch "single-$(nm "$i" | tr ' ' '-')" local "$i" "$i"; done
  for i in $(all); do
    v=$(jx get "$RESULTS/batch-single-$(nm "$i" | tr ' ' '-').json" "d['aggTokPerSec']" 0)
    if [ "$(awk -v a="$v" -v b="$best" 'BEGIN{print (a > b) ? 1 : 0}')" = 1 ]; then best=$v; fastest=$i; fi
  done
  [ -n "$fastest" ] && info t-fastest "fastest single phone: $(nm "$fastest") at $best tokens/s"
  if [ "$N" -ge 2 ]; then run_batch swarm2-1 swarm 1 1 2; run_batch swarm2-2 swarm 1 1 2; fi
  if [ "$N" -ge 3 ]; then run_batch swarm3-1 swarm 1 1 2 3; run_batch swarm3-2 swarm 1 1 2 3; fi
  jx report-batch "$RESULTS" > "$RESULTS/tables/throughput.md" 2>/dev/null
  tail -n 4 "$RESULTS/tables/throughput.md" | while IFS= read -r l; do [ -n "$l" ] && info t-ratio "$l"; done
}

# t2: a swarm of exactly two phones when three are given: the scheduler uses every phone that is online, so the third app is stopped first
step_t2() {
  [ "$N" -ge 3 ] || { info t2 "only two phones given, step t already ran two-phone swarms"; return; }
  stop_app 3; info t2 "$(nm 3) app force-stopped at $(date +%T) so that the swarm is $(nm 1) + $(nm 2)"
  wait_peer 1 3 away 60 >/dev/null || warn t2 "$(nm 1) still lists $(nm 3) online after 60 s"
  run_batch swarm2-1 swarm 1 1 2; run_batch swarm2-2 swarm 1 1 2
  launch_app 3; wait_api 3 90 && info t2 "$(nm 3) app relaunched" || warn t2 "$(nm 3) API did not come back within 90 s"
  jx report-batch "$RESULTS" > "$RESULTS/tables/throughput.md" 2>/dev/null
}

# =============================================================================================================
# c. Agent Choir evaluation in slices (the first phone coordinates, the others are peers)
#    pass 1: strict scorer, "check against the problem's test" off, with the one-phone-three-samples arm
#    pass 2 (--pass2): the same problems with the problem's own test switched on, without the samples arm (it only adds time)
# =============================================================================================================
eval_slice() { # idx ids tag use-problem-check with-samples
  local idx=$1 ids=$2 tag=$3 use=$4 samples=$5 f t0 body pid code wall rows note="" i
  f="$RESULTS/eval-$tag-$idx.json"
  ready_cool "c-$tag-$idx" $(all) || return 1
  local ths; ths=$(th_str $(all))
  body="{\"problemIds\":[\"${ids//,/\",\"}\"],\"useProblemCheck\":$use,\"samples\":$samples}"
  info "c-$tag-$idx" "problems $ids; thermal at start $ths"
  t0=$(date +%s); rm -f "$RESULTS/eval-$tag-$idx.code"
  ( call 1 POST /v1/choir/eval "$body" "$f" 3000; echo "$CODE" > "$RESULTS/eval-$tag-$idx.code" ) &
  pid=$!; BG_PIDS="$BG_PIDS $pid"
  while kill -0 "$pid" 2>/dev/null; do
    sleep 20; note_peak $(all)
    call 1 GET /v1/choir/eval/status "" "$RESULTS/eval-status.json" 10
    [ "$CODE" = 200 ] && info "c-$tag-$idx" "$(jx get "$RESULTS/eval-status.json" "d['done']")/$(jx get "$RESULTS/eval-status.json" "d['total']") done, current $(jx get "$RESULTS/eval-status.json" "d['current']"), thermal $(for i in $(all); do printf '%s ' "$(thermal_of "$i")"; done), $(( $(date +%s) - t0 )) s"
  done
  wait "$pid" 2>/dev/null
  code=$(cat "$RESULTS/eval-$tag-$idx.code" 2>/dev/null || echo 000); wall=$(( $(date +%s) - t0 ))
  if [ "$code" != 200 ]; then
    note="HTTP $code on the eval call"
    for _ in 1 2 3 4 5 6 7 8; do
      call 1 GET /v1/choir/eval/status "" "$RESULTS/eval-status.json" 10
      [ "$(jx get "$RESULTS/eval-status.json" "d['running']")" = false ] && break; sleep 15
    done
    if [ "$CODE" = 200 ] && [ "$(jx get "$RESULTS/eval-status.json" "d['running']")" = false ]; then cp "$RESULTS/eval-status.json" "$f"; note="$note; rows from /v1/choir/eval/status: $(jx get "$RESULTS/eval-status.json" "d['error']")"; fi
  fi
  rows=$(jx get "$f" "len(d['rows'])" 0)
  local pk=""; for i in $(all); do pk="$pk${PEAK[$i]:-?} "; done
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$idx" "$ids" "$ths" "${pk% }" "$wall" "$code" "$rows" "$note" >> "$RESULTS/slices-$tag.tsv"
  echo "eval-$tag-$idx	thermal start $ths	peak ${pk% }	${wall}s" >> "$RESULTS/thermal.log"
  [ "$code" = 200 ] || [ "$rows" -gt 0 ] 2>/dev/null
}

step_c() {
  local tag=pass1 use=false samples=true line i=0 failed=0 n_run
  [ "$PASS2" = 1 ] && { tag=pass2; use=true; samples=false; }
  call 1 GET /v1/choir/problems "" "$RESULTS/choir-problems.json" 15
  [ "$CODE" = 200 ] || { fail c-eval "GET /v1/choir/problems gave HTTP $CODE"; return; }
  : > "$RESULTS/slices-$tag.tsv"
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    i=$((i + 1))
    eval_slice "$i" "$line" "$tag" "$use" "$samples" || { failed=1; fail "c-$tag-$i" "slice produced no rows"; break; }
  done <<<"$(jx slices "$RESULTS/choir-problems.json" "$SLICE" | tr -d '\r')"
  jx report-eval "$RESULTS" "$tag" > "$RESULTS/tables/choir-$tag.md" 2>/dev/null
  n_run=$(grep -o 'problems run: n = [0-9]*' "$RESULTS/tables/choir-$tag.md" | grep -o '[0-9]*$'); n_run=${n_run:-0}
  if [ "$n_run" = 20 ] && [ "$failed" = 0 ]; then pass "c-$tag" "all 20 problems evaluated in $i slice(s)"; else fail "c-$tag" "$n_run of 20 problems evaluated, failed=$failed"; fi
  sed -n '3,8p' "$RESULTS/tables/choir-$tag.md" | while IFS= read -r l; do info "c-$tag-arms" "$l"; done
  sed -n '9,11p' "$RESULTS/tables/choir-$tag.md" | while IFS= read -r l; do [ -n "$l" ] && info "c-$tag-notes" "$l"; done
}

# =============================================================================================================
say "e2e2-check $(date '+%F %T') results=$RESULTS phones=$(for i in $(all); do printf '%s ' "${HP[$i]}"; done) steps=$STEPS"
for i in $(all); do "$ADB" -s "${SER[$i]}" get-state </dev/null >/dev/null 2>&1 || warn adb "adb transport ${SER[$i]} not available; thermal falls back to GET /v1/health"; done
for i in $(all); do
  NOSIG=1 call "$i" GET /v1/hello "" "$RESULTS/hello-$i.json" 10
  [ "$CODE" = 200 ] && { NAME[$i]=$(jx get "$RESULTS/hello-$i.json" "d['name']"); NID[$i]=$(jx get "$RESULTS/hello-$i.json" "d['nodeId']"); }
  resolve_secret "$i" >/dev/null 2>&1
done
write_meta
step_on s && step_s
step_on d && step_d
step_on p && step_p
step_on k && step_k
step_on t && step_t
step_on t2 && step_t2
step_on c && step_c
write_meta
exit 0
