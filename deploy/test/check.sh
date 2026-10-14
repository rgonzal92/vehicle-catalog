#!/usr/bin/env bash
# Runs the host's stack on this machine as the host runs it, against stand-ins for the certificate
# authority, the image registry, Parameter Store, the CloudWatch agent, and the queue of the jobs,
# and checks what it does: who is answered, how an image is released, what happens to one that
# does not come up healthy, what the backend reports of itself, that the worker does a job, that
# the key for the language model reaches the stack when there is one, and that the cache asks for
# its password and is not waited for.
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
  ORIGIN_SECRET=unused DATABASE_PASSWORD=unused OIDC_CLIENT_SECRET=unused CACHE_PASSWORD=unused "$@"
}
trap 'without_secrets docker compose down --volumes >/dev/null 2>&1
  docker rmi --force vehicle-catalog-backend:current vehicle-catalog-backend:previous \
    vehicle-catalog-backend:as-built >/dev/null 2>&1
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

# How long Caddy goes on asking for an API that is not there, in seconds, as its file says.
held="$(sed -n 's/^\t*lb_try_duration \([0-9]*\)s$/\1/p' Caddyfile)"
# How long CloudFront waits for the host's answer, in seconds, as the distribution is told.
waited="$(sed -n 's/^ *origin_read_timeout *= *\([0-9]*\)$/\1/p' "$here/../../infra/frontend.tf")"
# Asks the stack as CloudFront would, and prints what it answers. It waits a little longer than
# Caddy does, so that an answer that comes late is seen to be late.
answer() {
  curl --silent --insecure --max-time "$((held + 5))" --resolve origin.test:18443:127.0.0.1 "$@"
}
# The same, for the status alone.
ask() {
  answer --output /dev/null --write-out '%{http_code}\n' "$@"
}
with_secret=(--header "X-Origin-Secret: $secret")
# What a stack without an API answers, and when: no sooner than Caddy gives up asking for one, and
# before CloudFront has stopped waiting for an answer.
without_an_api() {
  answer --output /dev/null --write-out '%{http_code} %{time_total}\n' "${with_secret[@]}" \
    https://origin.test:18443/api/health |
    awk -v held="$held" -v waited="$waited" \
      '{ print $1, (held > 0 && waited > 0 && $2 >= held && $2 < waited ? "in time" : "after " $2 " seconds") }'
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
running() { # the service, which is the API unless the worker is named
  docker inspect --format '{{.Image}}' "$(docker compose ps --quiet "${1:-backend}")"
}
in_the_database() {
  docker compose exec -T db psql --username catalog --dbname catalog --tuples-only --no-align \
    --command "$1" 2>&1
}

# The address the stand-in signs its own certificate with, which Caddy has to trust.
quietly without_secrets docker compose create --quiet-pull authority
quietly without_secrets docker compose cp authority:/test/certs/pebble.minica.pem "$AUTHORITY_ROOT"
# The stand-ins are started here: a release starts the host's own services and no other.
quietly without_secrets docker compose up --detach --quiet-pull authority registry agent queue

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
# The backend's own image, with one thing laid over it for this check: the minute it has to get
# ready, in which it is asked every second. Docker gives a container the start period its image
# names when the stack's file names none, and test/compose.yaml names none. The health check
# itself stays the one of the host's file.
quietly docker build --quiet --tag vehicle-catalog-backend:as-built "$here/../../backend"
offer real - <<EOF
FROM vehicle-catalog-backend:as-built
HEALTHCHECK --start-period=1m --start-interval=1s CMD ["true"]
EOF
# Says what reached it, and that it is ready whenever it is asked. It writes down every request
# it answers.
offer echo - <<EOF
FROM $caddy_image
CMD ["caddy", "respond", "--listen", ":8080", "--access-log", "--body", "{http.request.method} {http.request.uri}\nhost: {http.request.host}\ncookie: {http.request.header.Cookie}\nx-probe: {http.request.header.X-Probe}\nsecret: {http.request.header.X-Origin-Secret}\n"]
EOF
offer stops-at-once - <<EOF
FROM $caddy_image
CMD ["false"]
EOF
offer never-gets-ready - <<EOF
FROM $caddy_image
CMD ["sleep", "infinity"]
EOF
# Ready as the API and never as the worker, which is told what it is as the backend's image is.
offer has-a-worker-that-never-gets-ready - <<EOF
FROM $caddy_image
CMD ["sh", "-c", "if [ \"\$SPRING_PROFILES_ACTIVE\" = worker ]; then sleep infinity; else caddy respond --listen :8080; fi"]
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
asks_the_cache() { # what to ask, which is asked with the password
  docker compose exec -T --env REDISCLI_AUTH=a-cache-password-for-this-check cache \
    redis-cli "$@" 2>&1 | paste -sd ' '
}
expect "the cache answers a request that gives its password" PONG "$(asks_the_cache ping)"
expect "and refuses one that gives none" yes \
  "$(whether grep -q NOAUTH <<<"$(docker compose exec -T cache redis-cli ping 2>&1)")"
expect "the cache holds 64 MB at most, and then drops what was used longest ago" \
  "maxmemory 67108864 maxmemory-policy allkeys-lru" \
  "$(asks_the_cache config get maxmemory) $(asks_the_cache config get maxmemory-policy)"
# What it is set to comes as a name and a value, and the times it saves at are none.
expect "and writes nothing to disk" "save  appendonly no" \
  "$(asks_the_cache config get save) $(asks_the_cache config get appendonly)"
expect "no secret is refused" 403 "$(ask --output /dev/null https://origin.test:18443/api/health)"
expect "a wrong secret is refused" 403 \
  "$(ask --output /dev/null --header 'X-Origin-Secret: not-it' https://origin.test:18443/api/health)"
expect "the API has no one to answer for it, which Caddy says once it has asked for $held seconds, before the $waited are over that CloudFront waits" \
  "502 in time" "$(without_an_api)"
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
expect "and the stack runs on without a backend" "502 in time" "$(without_an_api)"
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

# What Caddy and the API wrote down from a moment on, shown when a check of the two below fails.
wrote_down_since() { # the moment
  docker compose logs --no-color --timestamps --since "$1" caddy backend 2>&1 || true
}
# The API stopped and started again, as Docker starts it again when its process has ended. What is
# asked meanwhile reaches no API, so Caddy keeps it and asks again until there is one to answer.
# Each request leaves its status in one file and what curl said of an error in another.
stopped="$(date --utc +%Y-%m-%dT%H:%M:%S.%NZ)"
quietly docker compose stop backend
ask --show-error --output /dev/null "${with_secret[@]}" https://origin.test:18443/api/things \
  >"$work/read" 2>"$work/read.error" &
ask --show-error --output /dev/null "${with_secret[@]}" --data 'a=write' \
  https://origin.test:18443/api/things >"$work/write" 2>"$work/write.error" &
sleep 3
expect "a read and a write asked while the API is stopped are not answered at once" "" \
  "$(cat "$work/read" "$work/write")"
quietly docker compose start backend
wait
answered="$(cat "$work/read" "$work/write" | paste -sd ' ')"
expect "and both are answered once the API is started" "200 200" "$answered"
if [ "$answered" != "200 200" ]; then
  for request in read write; do
    echo "curl said of the $request: $(grep . "$work/$request.error" || echo nothing)"
  done
  wrote_down_since "$stopped"
fi

# Writes, forty at a time. The API here answers a write before Caddy has passed all of it on, and
# Caddy 2.11.6 ended at that, now and then, with every request it was answering or keeping: the
# write was answered and the read beside it was not. Docker starts Caddy again and counts it.
began="$(date --utc +%Y-%m-%dT%H:%M:%S.%NZ)"
for _ in $(seq 1 10); do
  for _ in $(seq 1 40); do
    ask --output /dev/null "${with_secret[@]}" --data 'a=write' https://origin.test:18443/api/things &
  done
  wait
done >"$work/writes"
ended="$(docker inspect --format '{{.RestartCount}}' "$(docker compose ps --all --quiet caddy)")"
expect "400 writes at the API, forty at a time, are all answered" "400 200" \
  "$(sort "$work/writes" | uniq --count | xargs)"
expect "and Caddy has not ended since the stack was started" 0 "$ended"
if [ "$ended" != 0 ]; then
  wrote_down_since "$began" | grep --max-count=1 --after-context=12 'panic: ' || true
  await_the_stack
fi

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
expect "the log holds none of the secrets" 0 \
  "$(logged | grep -cF -e "$secret" -e a-password-for-this-check -e a-client-secret-for-this-check \
    -e a-cache-password-for-this-check || true)"

# The cache stopped, and the API and the worker started anew as a release starts them. Both get
# ready without it, and health says of the cache alone that it is down.
expect "health says the cache is up" UP \
  "$(answer "${with_secret[@]}" https://origin.test:18443/api/health | jq --raw-output .components.cache.status)"
quietly docker compose stop cache
quietly docker compose up --detach --wait --force-recreate --no-deps backend worker
expect "with the cache stopped, the API is ready, and the worker" "200 yes" \
  "$(ask "${with_secret[@]}" https://origin.test:18443/api/health/readiness) $(whether \
    docker compose exec -T worker wget --quiet --spider http://127.0.0.1:8080/api/health/readiness)"
expect "and health says that the cache is down and the database up" "DOWN UP" \
  "$(answer "${with_secret[@]}" https://origin.test:18443/api/health |
    jq --raw-output '"\(.components.cache.status) \(.components.db.status)"')"
expect "and the cache is still stopped" "" "$(docker compose ps --quiet --status running cache)"
quietly docker compose start cache
for _ in $(seq 1 15); do
  [ "$(healthy)" = yes ] && break
  sleep 1
done
expect "with the cache started again, health says the backend is up" yes "$(healthy)"

# The worker, which is the backend's image run beside the API. A job is put into the database as a
# change puts it there: with its message, unsent. The catalog it names does not exist, so there is
# no one to tell, and the job is done once the worker has sent its message and received it.
expect "the worker runs the image the API runs" "$(running)" "$(running worker)"
quietly in_the_database "WITH queued AS (
    INSERT INTO job (type, dedupe_key, subject)
    VALUES ('AFTER_APPROVAL', 'put-there-by-the-check', '{\"catalogId\": -1}')
    RETURNING id
  )
  INSERT INTO outbox (job_id, payload) SELECT id, jsonb_build_object('jobId', id) FROM queued"
the_job() {
  in_the_database "SELECT status FROM job WHERE dedupe_key = 'put-there-by-the-check'"
}
for _ in $(seq 1 30); do
  [ "$(the_job)" = SUCCEEDED ] && break
  sleep 1
done
expect "a job put into the database is sent, run, and done by the worker" SUCCEEDED "$(the_job)"
expect "the worker's log is one object to a line, and a job's line carries a trace id" yes \
  "$(whether grep -qE '^[0-9a-f]{32}$' <<<"$(docker compose logs --no-log-prefix worker 2>&1 |
    jq --raw-input --raw-output \
      'fromjson? | select(.message | startswith("AFTER_APPROVAL job")) | .traceId' | tail -n 1)")"
# The worker traces a job while it does it, and sends the trace where the API sends its own.
job_span='^ *Name +: AFTER_APPROVAL job$'
for _ in $(seq 1 30); do
  grep -qE "$job_span" <<<"$(reported)" && break
  sleep 1
done
expect "the agent is sent the trace of the job" yes "$(whether grep -qE "$job_span" <<<"$(reported)")"
# Each span the stand-in was sent, as one line: its trace, its parent, and its name.
spans() {
  reported | awk '
    /^ *Trace ID *: / { trace = $NF }
    /^ *Parent ID *: / { parent = ($NF == ":") ? "" : $NF }
    /^ *Name *: / { sub(/^ *Name *: /, ""); print trace "\t" parent "\t" $0 }'
}
# The steps the stand-in was sent in the trace of a job, each under a span of that trace. The
# jobs that validate the seeded Approved versions read each one through the same steps as a
# request that opens it.
steps_of_a_job() {
  spans | awk -F '\t' '
    $3 ~ / job$/ { job[$1] = 1 }
    $2 != "" && $3 ~ /^catalog\./ { step[NR] = $3; trace[NR] = $1 }
    END { for (n in step) if (trace[n] in job) seen[step[n]] = 1; for (s in seen) print s }' |
    sort | paste -sd ' '
}
for _ in $(seq 1 30); do
  [ "$(steps_of_a_job)" = "catalog.issues catalog.library catalog.read" ] && break
  sleep 1
done
expect "the agent is sent the steps of a job's read of a catalog, each in the job's trace and under a span of it" \
  "catalog.issues catalog.library catalog.read" "$(steps_of_a_job)"
# The worker times a job, and sends how long it took where the API sends its metrics. That is the
# one metric the worker sends, so with this job's time sent the agent has been sent all there are.
its_time='-> type: Str(AFTER_APPROVAL)'
for _ in $(seq 1 30); do
  said="$(reported)"
  grep -qF -- "$its_time" <<<"$said" && break
  sleep 1
done
expect "the agent is sent how long the job took, told apart by its type and by how it ended" \
  "outcome: Str(SUCCESS) type: Str(AFTER_APPROVAL)" \
  "$(grep -B 1 -F -- "$its_time" <<<"$said" | sed -n 's/^ *-> //p' | sort -u | paste -sd ' ')"
expect "the agent is sent twelve metrics and no other: the API's eleven, and how long a job took" \
  "cache.read catalog.approved catalog.copy catalog.edit catalog.merged catalog.rejected catalog.submit.refused catalog.validate health http.server.requests job.run jvm.heap.used" \
  "$(sed -n 's/^ *-> Name: //p' <<<"$said" | sort -u | paste -sd ' ')"
asks_the_worker() { # the path
  docker compose exec -T worker wget --quiet --spider "http://127.0.0.1:8080$1"
}
expect "the worker answers its health check and has nothing at an address the API answers" \
  "yes no" "$(whether asks_the_worker /api/health/readiness) $(whether asks_the_worker /api/demo-accounts)"

# The key for the language model, which Parameter Store has had none of so far.
has_the_key() { # the service
  [ "$(docker compose exec -T "$1" printenv OPENAI_API_KEY)" = a-key-for-this-check ]
}
expect "without a key for the language model, the API and the worker run without one" "no no" \
  "$(whether has_the_key backend) $(whether has_the_key worker)"

MODEL_KEY=a-key-for-this-check \
  releases "the image that runs is released again" 0 "$registry/backend:real"
expect "and the one before it is still the one before it" "yes yes" "$(kept real) $(kept echo)"
expect "with a key in Parameter Store, the API and the worker are given it" "yes yes" \
  "$(whether has_the_key backend) $(whether has_the_key worker)"
expect "and the site answers, healthy" yes "$(healthy)"
expect "and neither log holds the key" 0 \
  "$(docker compose logs --no-log-prefix backend worker 2>&1 | grep -cF a-key-for-this-check || true)"
# An API that has a key says what asking the model has cost today. Nothing has asked the model
# here, so that is the one metric of the model's there is.
for _ in $(seq 1 30); do
  grep -qF -- '-> Name: ai.spent' <<<"$(reported)" && break
  sleep 1
done
expect "with a key, the agent is also sent what the model has cost today, and nothing else of the model's" \
  "ai.spent" "$(sed -n 's/^ *-> Name: \(ai\..*\)$/\1/p' <<<"$(reported)" | sort -u | paste -sd ' ')"

expect "the database can keep what a text means: it has pgvector to create" vector \
  "$(in_the_database "SELECT name FROM pg_available_extensions WHERE name = 'vector'")"
quietly in_the_database 'CREATE TABLE kept_by_the_check AS SELECT 7 AS it'
backend="$(running)"
for image in stops-at-once never-gets-ready has-a-worker-that-never-gets-ready; do
  releases "an image that $(tr - ' ' <<<"$image") fails its release" 1 "$registry/backend:$image"
  expect "the image that ran before it runs again, as the API and as the worker" \
    "$backend $backend" "$(running) $(running worker)"
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
# Every span the stand-in was sent so far, by name, with how many of each, to be read in the log
# of any run: a name here that is no request's and no job's is a span that says nothing.
echo "the agent was sent these spans, by name:"
spans | cut -f 3 | sort | uniq -c | sort -rn
expect "the question the API and the worker put to the cache every ten seconds leaves no span" 0 \
  "$(spans | cut -f 3 | grep -c asksAgain || true)"

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
expect "the database listens on no port of the host, and neither does the cache" "null null" \
  "$(jq --compact-output .services.db.ports <<<"$hosts") $(jq --compact-output .services.cache.ports <<<"$hosts")"
expect "everything comes back when the host restarts" 5 \
  "$(jq '[.services[] | select(.restart == "unless-stopped")] | length' <<<"$hosts")"
expect "and those five are all that runs there" "backend cache caddy db worker" \
  "$(jq --raw-output '.services | keys | join(" ")' <<<"$hosts")"

# Without a secret, Caddy does not start at all.
expect "no secret, no start" no \
  "$(ORIGIN_SECRET=' ' DATABASE_PASSWORD=unused OIDC_CLIENT_SECRET=unused CACHE_PASSWORD=unused \
    whether docker compose run --rm --no-deps caddy \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile)"

exit "$failed"
