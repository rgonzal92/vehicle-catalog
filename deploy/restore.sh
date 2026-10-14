#!/usr/bin/env bash
# Puts a backup of the database back into an empty database on this machine, counts what it
# holds, and starts the API on it if an image is named:
#
#   restore.sh <bucket> <backup>            the backup put back, and what it holds counted
#   restore.sh <bucket> <backup> <image>    the same, and the API of that image started on it
#
# The database runs from the image the host runs its own from, which DATABASE_IMAGE names, in the
# environment or in restore.env beside this script. Its password is made up here and told to no
# one. The API is started alone, with made-up settings for signing in and the demo reset off, and
# is told of no queue, bucket, cache, user pool, key, or agent; it answers on 127.0.0.1, on
# RESTORE_API_PORT or 18090. The containers and the network are named after the backup and are
# left running, for the API to be asked. This prints how long each step took, the rows of every
# table, and the migrations, and no row's contents and no name that carries the account's number.
set -euo pipefail
cd "$(dirname "$0")"

bucket="$1"
backup="$2"
image="${3:-}"
if [ -f restore.env ]; then
  set -o allexport
  . ./restore.env
  set +o allexport
fi
database_image="${DATABASE_IMAGE:?Name the image the database runs from, in DATABASE_IMAGE or in restore.env}"
port="${RESTORE_API_PORT:-18090}"

name="restore-${backup%.dump}"
db="$name-db"
api="$name-api"
copy="$(mktemp)"
trap 'rm -f "$copy"' EXIT
started="$(date +%s.%N)"
mark="$started"
# Says what was done and how long it took since the last mark, to a tenth of a second.
took() {
  local now
  now="$(date +%s.%N)"
  awk -v what="$1" -v from="$mark" -v to="$now" 'BEGIN { printf "%s in %.1f s.\n", what, to - from }'
  mark="$now"
}
sql() {
  docker exec --interactive "$db" psql --username catalog --dbname catalog \
    --tuples-only --no-align --field-separator ' ' --quiet "$@"
}

echo "Restore of $backup, started at $(date --utc +%Y-%m-%dT%H:%M:%SZ)."

# An empty database, from the host's image, with a password made up here.
password="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
docker image inspect "$database_image" >/dev/null 2>&1 || docker pull --quiet "$database_image" >/dev/null
docker network create "$name" >/dev/null
docker run --detach --name "$db" --network "$name" --user postgres \
  --env POSTGRES_DB=catalog --env POSTGRES_USER=catalog --env POSTGRES_PASSWORD="$password" \
  "$database_image" >/dev/null
for _ in $(seq 1 120); do
  docker exec "$db" pg_isready --host 127.0.0.1 --username catalog --dbname catalog --quiet && break
  sleep 1
done
took "The empty database started"

aws s3 cp "s3://$bucket/$backup" "$copy" --only-show-errors
took "The backup of $(stat --format %s "$copy") bytes was fetched"

restored=0
docker exec --interactive "$db" pg_restore --username catalog --dbname catalog --no-owner <"$copy" || restored=$?
echo "pg_restore ended with $restored."
took "The backup was put back"

echo "Rows in each table:"
sql <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I', relname, relname)
FROM pg_class JOIN pg_namespace ON pg_namespace.oid = relnamespace
WHERE relkind = 'r' AND nspname = 'public'
ORDER BY relname \gexec
SQL
echo "Migrations, by rank, version, description, and whether it succeeded:"
sql --command "SELECT installed_rank, coalesce(version, '-'), description, success FROM flyway_schema_history ORDER BY installed_rank"
echo "Extensions besides plpgsql:"
sql --command "SELECT extname, extversion FROM pg_extension WHERE extname <> 'plpgsql'"
took "What it holds was counted"

if [ -n "$image" ]; then
  # The API alone, on that database, told of nothing live.
  docker image inspect "$image" >/dev/null 2>&1 || docker pull --quiet "$image" >/dev/null
  took "The API's image is there"
  docker run --detach --name "$api" --network "$name" --publish "127.0.0.1:$port:8080" \
    --env JAVA_TOOL_OPTIONS='-XX:MaxHeapSize=256m -XX:+ExitOnOutOfMemoryError' \
    --env DATABASE_URL="jdbc:postgresql://$db:5432/catalog" \
    --env DATABASE_USER=catalog \
    --env DATABASE_PASSWORD="$password" \
    --env PUBLIC_URL=https://restore.invalid \
    --env LOGOUT_URL=https://login.invalid/logout \
    --env OIDC_CLIENT_ID=vehicle-catalog \
    --env OIDC_CLIENT_SECRET=not-a-secret \
    --env SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_COGNITO_AUTHORIZATION_URI=https://login.invalid/authorize \
    --env SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_COGNITO_TOKEN_URI=https://login.invalid/token \
    --env SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_COGNITO_JWK_SET_URI=https://login.invalid/jwks \
    --env APP_DEMORESET_CRON=- \
    "$image" >/dev/null
  echo "The API runs image $(docker inspect --format '{{.Image}}' "$api")."
  ready=no
  for _ in $(seq 1 300); do
    if curl --silent --fail --output /dev/null "http://127.0.0.1:$port/api/health/readiness"; then
      ready=yes
      break
    fi
    sleep 1
  done
  if [ "$ready" = no ]; then
    echo "The API was not ready after 300 seconds." >&2
    exit 1
  fi
  took "The API is ready"
  echo "Health: $(curl --silent "http://127.0.0.1:$port/api/health")"
fi

mark="$started"
took "The whole"
exit "$restored"
