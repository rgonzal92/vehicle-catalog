#!/usr/bin/env bash
# Starts the host's stack, with a new backend image if one is named:
#
#   release.sh            starts the stack with the image released last
#   release.sh <image>    releases that image
#
# A released image that does not come up healthy is taken back: the image that ran before it is
# started again, and this fails. The database is left as the new image made it.
set -euo pipefail
cd "$(dirname "$0")"

current=vehicle-catalog-backend:current
previous=vehicle-catalog-backend:previous

# Says which image a name stands for, or nothing if there is none of that name.
image_named() {
  docker image inspect --format '{{.Id}}' "$1" 2>/dev/null || true
}
# Removes an image that no name is left for.
forget() {
  if [ -z "$(docker image inspect --format '{{join .RepoTags ""}}' "$1")" ]; then
    docker rmi "$1" >/dev/null
  fi
}
parameter() {
  aws ssm get-parameter --name "/vehicle-catalog/$1" --with-decryption \
    --query Parameter.Value --output text
}

# The secrets are read at every start, into a file that only root can read, which Compose reads
# beside its own.
origin_secret="$(parameter origin-secret)"
database_password="$(parameter database-password)"
(
  umask 077
  printf 'ORIGIN_SECRET=%s\nDATABASE_PASSWORD=%s\n' "$origin_secret" "$database_password" > .env.new
)
mv .env.new .env

replaced="$(image_named "$current")"
dropped="$(image_named "$previous")"
if [ $# -eq 1 ]; then
  docker pull --quiet "$1" >/dev/null
  if [ -n "$replaced" ]; then
    docker tag "$replaced" "$previous"
  fi
  docker tag "$1" "$current"
  docker rmi "$1" >/dev/null
fi

if [ -z "$(image_named "$current")" ]; then
  echo "No backend has been released to this host. Caddy and the database run without one."
  docker compose up --detach --wait --quiet-pull caddy db
  exit 0
fi

if docker compose up --detach --wait --wait-timeout 300 --quiet-pull --remove-orphans; then
  # Two images are kept: the one that runs and the one before it.
  if [ -n "$dropped" ]; then
    forget "$dropped"
  fi
  echo "The stack is up."
  exit 0
fi

if [ $# -eq 0 ]; then
  echo "The stack did not come up healthy." >&2
  exit 1
fi
if [ -z "$replaced" ]; then
  echo "The image did not come up healthy, and none ran before it. The backend is stopped." >&2
  docker compose rm --stop --force backend
  docker rmi "$current" >/dev/null
  exit 1
fi
echo "The image did not come up healthy. The one that ran before it is started again." >&2
refused="$(image_named "$current")"
docker tag "$replaced" "$current"
if [ -n "$dropped" ]; then
  docker tag "$dropped" "$previous"
else
  docker rmi "$previous" >/dev/null
fi
docker compose up --detach --wait --wait-timeout 300 backend
forget "$refused"
exit 1
