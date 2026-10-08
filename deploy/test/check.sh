#!/usr/bin/env bash
# Runs the host's stack on this machine as the host runs it, against stand-ins for the certificate
# authority, the image registry, Parameter Store, and the CloudWatch agent, and checks what it
# does: who is answered, how an image is released, what happens to one that does not come up
# healthy, and what the backend reports of itself.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

# The stack's files in a directory of their own, as on the host, so that nothing is left here.
work="$(mktemp -d)"
cp -r "$here/.." "$work/stack"
cd "$work/stack"
# What the backend is told about signing in, which Terraform writes for the host.
cp test/settings.env settings.env
export AUTHORITY_ROOT="$work/authority-root.pem"
export COMPOSE_PROJECT_NAME=vehicle-catalog-host-check
export COMPOSE_FILE=compose.yaml:test/compose.yaml
# The stand-in for the AWS CLI, which answers with these two secrets.
export PATH="$PWD/test/bin:$PATH"
secret=a-secret-for-this-check
registry=127.0.0.1:15000

# Compose asks for the secrets whatever it is told to do. Before the stack's own file of them is
# written, and for what does not use them, they are given as nothing in particular.
without_secrets() {
  ORIGIN_SECRET=unused DATABASE_PASSWORD=unused OIDC_CLIENT_SECRET=unused "$@"
}
trap 'without_secrets docker compose down --volumes >/dev/null 2>&1
  docker rmi --force vehicle-catalog-backend:current vehicle-catalog-backend:previous >/dev/null 2>&1
  rm -rf "$work"' EXIT

# Runs a step that has nothing to say, and shows what it said if it fails.
quietly() {
  local said
  if ! said="$("$@" 2>&1)"; then
    echo "$said"
    echo "FAILED: $*"
    exit 1
  fi
}

# Asks the stack as CloudFront would, and prints what it answers.
answer() {
  curl --silent --insecure --max-time 5 --resolve origin.test:18443:127.0.0.1 "$@"
}
# The same, for the status alone.
ask() {
  answer --output /dev/null --write-out '%{http_code}\n' "$@"
}
with_secret=(--header "X-Origin-Secret: $secret")
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
# Runs a release and checks how it ended: 0 when the stack came up healthy.
releases() { # what, how it should end, the image if there is one
  local ended=0
  ./release.sh "${@:3}" >"$work/release.log" 2>&1 || ended=$?
  if [ "$ended" != "$2" ]; then
    cat "$work/release.log"
  fi
  expect "$1" "$2" "$ended"
}

# The certificate takes a while; until it is there, nothing answers on 443.
await_the_stack() {
  for _ in $(seq 1 180); do
    [ "$(ask --output /dev/null https://origin.test:18443/ || true)" != 000 ] && return
    sleep 1
  done
  docker compose logs
  echo "FAILED: the stack did not answer within three minutes"
  exit 1
}
certificate() { # what to show of it
  echo | openssl s_client -connect 127.0.0.1:18443 -servername origin.test 2>/dev/null |
    openssl x509 -noout "$@" 2>/dev/null || true
}
running() {
  docker inspect --format '{{.Image}}' "$(docker compose ps --quiet backend)"
}

# The address the stand-in signs its own certificate with, which Caddy has to trust.
quietly without_secrets docker compose create --quiet-pull authority
quietly without_secrets docker compose cp authority:/test/certs/pebble.minica.pem "$AUTHORITY_ROOT"
quietly without_secrets docker compose up --detach --quiet-pull authority registry

# The images to release: the backend itself, and three made for this check from Caddy's.
caddy_image="$(without_secrets docker compose config --format json | jq --raw-output .services.caddy.image)"
declare -A offered
offer() { # name, then how the image is built
  offered[$1]="$(docker build --quiet --tag "$registry/backend:$1" "${@:2}")"
  quietly docker push --quiet "$registry/backend:$1"
  quietly docker rmi "$registry/backend:$1"
}
kept() { # name
  whether docker image inspect "${offered[$1]}"
}
offer real "$here/../../backend"
# Says what reached it, and that it is ready whenever it is asked.
offer echo - <<EOF
FROM $caddy_image
CMD ["caddy", "respond", "--listen", ":8080", "--body", "{http.request.method} {http.request.uri}\nhost: {http.request.host}\ncookie: {http.request.header.Cookie}\nx-probe: {http.request.header.X-Probe}\nsecret: {http.request.header.X-Origin-Secret}\n"]
EOF
offer stops-at-once - <<EOF
FROM $caddy_image
CMD ["false"]
EOF
offer never-gets-ready - <<EOF
FROM $caddy_image
CMD ["sleep", "infinity"]
EOF
# Ready whenever it is asked, like the one that says what reached it, and another image all the same.
offer another - <<EOF
FROM $caddy_image
LABEL another=image
CMD ["caddy", "respond", "--listen", ":8080"]
EOF

releases "with nothing released, the stack starts without a backend" 0
await_the_stack
expect "the secrets are in a file that only its owner reads" 600 "$(stat --format %a .env)"
expect "no secret is refused" 403 "$(ask --output /dev/null https://origin.test:18443/api/health)"
expect "a wrong secret is refused" 403 \
  "$(ask --output /dev/null --header 'X-Origin-Secret: not-it' https://origin.test:18443/api/health)"
expect "the API has no one to answer for it" 502 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/health)"
expect "a request that names another host is turned away, with the secret or without" "421 421" \
  "$(ask --output /dev/null --header 'Host: elsewhere.test' https://origin.test:18443/api/) $(ask \
    --output /dev/null "${with_secret[@]}" --header 'Host: elsewhere.test' https://origin.test:18443/api/)"

expect "the certificate is for the host's name" yes \
  "$(whether grep -q 'DNS:origin.test' <<<"$(certificate -ext subjectAltName)")"
port_80() {
  curl --silent --max-time 5 --output /dev/null --write-out '%{http_code}' \
    --header 'Host: origin.test' http://127.0.0.1:18080/ || true
}
expect "port 80 answers no one once the certificate is there" 000 "$(port_80)"
expect "the certificate was got over port 80" yes \
  "$(whether grep -q 'validate w/ HTTP: http://origin.test:80/' <<<"$(docker compose logs authority 2>&1)")"
expect "the authority is not asked to check anywhere else" yes \
  "$(whether grep -qF '"tls-alpn":{"disabled":true}' <<<"$(docker compose run --rm --no-deps caddy \
    caddy adapt --config /etc/caddy/Caddyfile 2>/dev/null)")"
expect "Caddy's own controls answer no one" no \
  "$(whether docker compose exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)"

releases "a first image that stops at once fails its release" 1 "$registry/backend:stops-at-once"
expect "and the stack runs on without a backend" 502 \
  "$(ask "${with_secret[@]}" https://origin.test:18443/api/health)"
expect "and that image is not kept" no "$(kept stops-at-once)"

releases "an image is released" 0 "$registry/backend:echo"
said="$(answer "${with_secret[@]}" --header 'Cookie: session=abc' --header 'X-Probe: sent' \
  'https://origin.test:18443/api/things?color=red')"
for line in 'GET /api/things?color=red' 'host: origin.test' 'cookie: session=abc' 'x-probe: sent'; do
  expect "the backend is sent \"$line\"" yes "$(whether grep -qxF "$line" <<<"$said")"
done
expect "the backend is not sent the secret" yes "$(whether grep -qxF 'secret: ' <<<"$said")"
expect "nothing outside /api is passed on" 404 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/)"

releases "the backend is released" 0 "$registry/backend:real"
healthy() {
  whether jq --exit-status '.status == "UP" and .components.db.status == "UP"' \
    <<<"$(answer "${with_secret[@]}" https://origin.test:18443/api/health)"
}
expect "health says the backend is up, and its database" yes "$(healthy)"
expect "the API refuses a visitor without a session" 401 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/me)"
expect "the backend knows the demo accounts, each with what a visitor signs in with" \
  "admin author manager" \
  "$(answer "${with_secret[@]}" https://origin.test:18443/api/demo-accounts |
    jq --raw-output '[.[] | select(.password != null) | .username] | sort | join(" ")')"

# What the backend reports of itself. The stand-in for the agent writes down what it is sent.
reported() {
  docker compose logs --no-log-prefix agent 2>&1
}
logged() {
  docker compose logs --no-log-prefix backend 2>&1
}
# The line the backend wrote for the request above, which is one object.
trace="$(logged | jq --raw-input --raw-output \
  'fromjson? | select(.message | startswith("GET /api/me 401")) | .traceId' | tail -n 1)"
expect "a request leaves a line in the log, with its trace id" yes \
  "$(whether grep -qE '^[0-9a-f]{32}$' <<<"$trace")"
# Spans are sent every few seconds, and here metrics are too. The stand-in writes a span down with
# its trace id at the start of a line.
span="^ *Trace ID +: ${trace:-none}\$"
for _ in $(seq 1 30); do
  said="$(reported)"
  grep -qE "$span" <<<"$said" && grep -q -- '-> Name: http.server.requests' <<<"$said" && break
  sleep 1
done
expect "the agent is sent that request's trace" yes "$(whether grep -qE "$span" <<<"$said")"
expect "the agent is sent five metrics and no other" \
  "catalog.copy catalog.edit health http.server.requests jvm.heap.used" \
  "$(sed -n 's/^ *-> Name: //p' <<<"$said" | sort -u | paste -sd ' ')"
expect "the log holds none of the secrets" 0 \
  "$(logged | grep -cF -e "$secret" -e a-password-for-this-check -e a-client-secret-for-this-check || true)"

releases "the image that runs is released again" 0 "$registry/backend:real"
expect "and the one before it is still the one before it" "yes yes" "$(kept real) $(kept echo)"

in_the_database() {
  docker compose exec -T db psql --username catalog --dbname catalog --tuples-only --no-align \
    --command "$1" 2>&1
}
quietly in_the_database 'CREATE TABLE kept_by_the_check AS SELECT 7 AS it'
backend="$(running)"
for image in stops-at-once never-gets-ready; do
  releases "an image that $(tr - ' ' <<<"$image") fails its release" 1 "$registry/backend:$image"
  expect "the image that ran before it runs again" "$backend" "$(running)"
  expect "and the site answers, healthy" yes "$(healthy)"
  expect "and the image that failed is not kept" no "$(kept "$image")"
done

# A backup, and what is in it put back into a database of its own.
export BACKUPS="$work/backups"
mkdir "$BACKUPS"
backs_up() { # what, how it should end, the bucket
  local ended=0
  ./backup.sh "$3" >"$work/backup.log" 2>&1 || ended=$?
  expect "$1" "$2" "$ended"
}
backs_up "the database is backed up" 0 a-bucket
expect "as one file, named by when it was made" 1 \
  "$(find "$BACKUPS" -name 'catalog-20??-??-??T????Z.dump' | wc -l)"
quietly docker compose exec -T db createdb --username catalog put-back
quietly docker compose exec -T db pg_restore --username catalog --dbname put-back --no-owner \
  <"$(find "$BACKUPS" -name '*.dump')"
expect "a backup put back holds what the database held" 7 \
  "$(docker compose exec -T db psql --username catalog --dbname put-back --tuples-only --no-align \
    --command 'SELECT it FROM kept_by_the_check' 2>&1)"
backs_up "a backup that cannot be sent ends in failure" 1 a-bucket-that-refuses
expect "and says that it failed" yes \
  "$(whether grep -qx 'The database was not backed up.' "$work/backup.log")"

issued="$(certificate -serial)"
releases "another image is released after those" 0 "$registry/backend:another"
expect "a release keeps what is in the database" 7 "$(in_the_database 'SELECT it FROM kept_by_the_check')"
expect "the host keeps the image that runs and the one before it, and no older one" "yes yes no" \
  "$(kept another) $(kept real) $(kept echo)"
# What a restart of the host comes to: every container is gone and is started again.
quietly without_secrets docker compose down
releases "the stack starts again with the image released last" 0
await_the_stack
expect "which answers" 200 \
  "$(ask --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/health)"
expect "a restart keeps what is in the database" 7 "$(in_the_database 'SELECT it FROM kept_by_the_check')"
expect "and the certificate" "${issued:-none was issued}" "$(certificate -serial)"

# The ports of this check are its own, so the host's are read from its file alone.
hosts="$(without_secrets docker compose --file compose.yaml config --format json)"
expect "the host listens on ports 80 and 443, for Caddy" "80:80 443:443" \
  "$(jq --raw-output '[.services[].ports // [] | .[] | "\(.published):\(.target)"] | join(" ")' <<<"$hosts")"
expect "the database listens on no port of the host" null \
  "$(jq --compact-output .services.db.ports <<<"$hosts")"
expect "everything comes back when the host restarts" "unless-stopped unless-stopped unless-stopped" \
  "$(jq --raw-output '[.services[].restart] | join(" ")' <<<"$hosts")"

# Without a secret, Caddy does not start at all.
expect "no secret, no start" no \
  "$(ORIGIN_SECRET=' ' DATABASE_PASSWORD=unused OIDC_CLIENT_SECRET=unused whether docker compose run --rm --no-deps caddy \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile)"

exit "$failed"
