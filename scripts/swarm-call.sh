#!/usr/bin/env bash
# Signed call to a SwarmOS node (CONTRACTS section 6 and 9.7).
#   scripts/swarm-call.sh <ip[:port]> <METHOD> <path> [json-body]
# path is the URL path; "/v1" is added when missing, so `/link/peers` and `/v1/link/peers` both work.
# Port defaults to 8787 (base build); the link variant listens on 8797, e.g. 192.168.242.89:8797.
# Environment:
#   SWARM_SECRET   pairing secret (default: debug dev secret swarmos-dev-secret)
#   SWARM_NODE     value for X-Swarm-Node (default: pc)
#   SWARM_UNSIGNED=1   send no signature headers (to see the 401)
#   SWARM_MAXTIME  curl --max-time in seconds (default 300, a 100 MB probe takes a while)
# The response body goes to stdout; "HTTP <code>" goes to stderr. Exit status is 0 only for a 2xx answer.
set -u
HOST="${1:?usage: swarm-call.sh <ip[:port]> <METHOD> <path> [json-body]}"
METHOD="$(printf '%s' "${2:?method}" | tr '[:lower:]' '[:upper:]')"
URLPATH="${3:?path}"
BODY="${4-}"
SECRET="${SWARM_SECRET:-swarmos-dev-secret}"
NODE="${SWARM_NODE:-pc}"

case "$URLPATH" in /v1|/v1/*) ;; /*) URLPATH="/v1$URLPATH" ;; *) URLPATH="/v1/$URLPATH" ;; esac
case "$HOST" in *:*) ;; *) HOST="$HOST:8787" ;; esac

# the signed path is the path without any query string
SIGPATH="${URLPATH%%\?*}"
TS="$(date +%s%3N)"
NL=$'\n'
MSG="${METHOD}${NL}${SIGPATH}${NL}${TS}${NL}${BODY}"
SIG="$(printf '%s' "$MSG" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')"

args=(-sS -m "${SWARM_MAXTIME:-300}" -X "$METHOD" -w '\nHTTP %{http_code}\n')
if [ "${SWARM_UNSIGNED:-0}" != "1" ]; then
  args+=(-H "X-Swarm-Node: $NODE" -H "X-Swarm-Ts: $TS" -H "X-Swarm-Sig: $SIG")
fi
if [ -n "$BODY" ]; then
  args+=(-H 'Content-Type: application/json' --data-binary "$BODY")
fi

out="$(curl "${args[@]}" "http://${HOST}${URLPATH}")" || exit $?
code="${out##*HTTP }"
printf '%s\n' "${out%$'\n'HTTP *}"
printf 'HTTP %s\n' "$code" >&2
case "$code" in 2??) exit 0 ;; *) exit 22 ;; esac
