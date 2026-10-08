#!/usr/bin/env bash
# Writes a copy of the database to the bucket that keeps the backups:
#
#   backup.sh <bucket>
#
# The copy is made on the host first and sent only once it is whole, so the bucket never holds
# half of one. A backup that fails says so and ends in failure, which the host's journal keeps.
set -euo pipefail
cd "$(dirname "$0")"

bucket="$1"
name="catalog-$(date --utc +%Y-%m-%dT%H%MZ).dump"
copy="$(mktemp)"
trap 'rm -f "$copy"' EXIT
trap 'echo "The database was not backed up." >&2' ERR

docker compose exec -T db pg_dump --username catalog --dbname catalog --format custom > "$copy"
aws s3 cp "$copy" "s3://$bucket/$name" --only-show-errors
echo "The database is backed up as $name."
