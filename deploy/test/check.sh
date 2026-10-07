#!/usr/bin/env bash
# Starts the host's stack on this machine, against a stand-in for the certificate authority, and
# checks what it answers: nothing without the secret, the fixed answer with it, and nothing on
# port 80 once it has its certificate.
set -euo pipefail
cd "$(dirname "$0")/.."

work="$(mktemp -d)"
export AUTHORITY_ROOT="$work/authority-root.pem"
export ORIGIN_SECRET="a-secret-for-this-check"
compose() {
  docker compose -p vehicle-catalog-host-check -f compose.yaml -f test/compose.yaml "$@"
}
trap 'compose down --volumes >/dev/null 2>&1; rm -rf "$work"' EXIT

# Runs a step that has nothing to say, and shows what it said if it fails.
quietly() {
  local said
  if ! said="$("$@" 2>&1)"; then
    echo "$said"
    echo "FAILED: $*"
    exit 1
  fi
}

# Asks the stack as CloudFront would, and prints the status and then the body.
ask() {
  curl --silent --insecure --max-time 5 --resolve origin.test:18443:127.0.0.1 \
    --write-out '%{http_code}\n' "$@"
}
failed=0
expect() { # what, wanted, got
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAILED: $1: wanted $2, got $3"
    failed=1
  fi
}
# Says yes when the command succeeds and no when it does not.
whether() {
  if "$@" >/dev/null 2>&1; then echo yes; else echo no; fi
}

# The certificate takes a while; until it is there, nothing answers on 443.
await_the_stack() {
  for _ in $(seq 1 180); do
    [ "$(ask --output /dev/null https://origin.test:18443/ || true)" != 000 ] && return
    sleep 1
  done
  compose logs
  echo "FAILED: the stack did not answer within three minutes"
  exit 1
}
certificate() { # what to show of it
  echo | openssl s_client -connect 127.0.0.1:18443 -servername origin.test 2>/dev/null |
    openssl x509 -noout "$@" 2>/dev/null || true
}

# The address the stand-in signs its own certificate with, which Caddy has to trust.
quietly compose create --quiet-pull authority
quietly compose cp authority:/test/certs/pebble.minica.pem "$AUTHORITY_ROOT"
quietly compose up --detach --quiet-pull
await_the_stack

with_secret=(--header "X-Origin-Secret: $ORIGIN_SECRET")
expect "no secret is refused" 403 "$(ask --output /dev/null https://origin.test:18443/api/)"
expect "a wrong secret is refused" 403 \
  "$(ask --output /dev/null --header 'X-Origin-Secret: not-it' https://origin.test:18443/api/)"
expect "the secret gets the fixed answer" 200 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/)"
expect "another address under /api is not there" 404 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/nothing-here)"
expect "nor is anything outside /api" 404 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/)"
expect "a request that names another host is turned away, with the secret or without" "421 421" \
  "$(ask --output /dev/null --header 'Host: elsewhere.test' https://origin.test:18443/api/) $(ask \
    --output /dev/null "${with_secret[@]}" --header 'Host: elsewhere.test' https://origin.test:18443/api/)"

said="$(ask "${with_secret[@]}" --header 'Cookie: session=abc' --header 'X-Probe: sent' \
  'https://origin.test:18443/api/?color=red')"
for line in 'GET /api/?color=red' 'host: origin.test' 'cookie: session=abc' 'x-probe: sent'; do
  expect "the fixed answer says \"$line\"" yes "$(whether grep -qF "$line" <<<"$said")"
done
expect "the fixed answer does not say the secret" no \
  "$(whether grep -qF "$ORIGIN_SECRET" <<<"$said")"

expect "the certificate is for the host's name" yes \
  "$(whether grep -q 'DNS:origin.test' <<<"$(certificate -ext subjectAltName)")"
port_80() {
  curl --silent --max-time 5 --output /dev/null --write-out '%{http_code}' \
    --header 'Host: origin.test' http://127.0.0.1:18080/ || true
}
expect "port 80 answers no one once the certificate is there" 000 "$(port_80)"
expect "the certificate was got over port 80" yes \
  "$(whether grep -q 'validate w/ HTTP: http://origin.test:80/' <<<"$(compose logs authority 2>&1)")"
expect "the authority is not asked to check anywhere else" yes \
  "$(whether grep -qF '"tls-alpn":{"disabled":true}' <<<"$(compose run --rm --no-deps caddy \
    caddy adapt --config /etc/caddy/Caddyfile 2>/dev/null)")"
expect "Caddy's own controls answer no one" no \
  "$(whether compose exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)"

# The host starts its stack anew at every change to it, and at every restart of the host.
issued="$(certificate -serial)"
quietly compose up --detach --force-recreate --no-deps caddy
await_the_stack
expect "the certificate is kept when the stack is started anew" "${issued:-none was issued}" \
  "$(certificate -serial)"
expect "Caddy comes back when the host restarts" unless-stopped \
  "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$(compose ps --quiet caddy)")"
# The ports of this check are its own, so the host's are read from its file alone.
expect "the host listens on ports 80 and 443" "80:80 443:443" \
  "$(docker compose -f compose.yaml config --format json |
    jq --raw-output '[.services.caddy.ports[] | "\(.published):\(.target)"] | join(" ")')"

# Without a secret, Caddy does not start at all.
expect "no secret, no start" no \
  "$(ORIGIN_SECRET=' ' whether compose run --rm --no-deps caddy \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile)"

exit "$failed"
