#!/usr/bin/env bash
# Writes a copy of the database to the bucket that keeps the backups:
#
#   backup.sh <bucket>
#
# The copy is made on the host first and sent only once it is whole, so the bucket never holds
# half of one. A backup that fails says so and ends in failure, which the host's journal keeps.
# Once the bucket has the copy, its size in bytes is reported to CloudWatch as backup.size, under
# the namespace deploy/agent.json names, which is the one the host may publish under. A size that
# cannot be reported leaves the backup kept: the script says so and ends without failure.
set -euo pipefail
cd "$(dirname "$0")"

bucket="$1"
name="catalog-$(date --utc +%Y-%m-%dT%H%MZ).dump"
copy="$(mktemp)"
trap 'rm -f "$copy"' EXIT
trap 'echo "The database was not backed up." >&2' ERR

docker compose exec -T db pg_dump --username catalog --dbname catalog --format custom > "$copy"
size="$(stat --format %s "$copy")"
aws s3 cp "$copy" "s3://$bucket/$name" --only-show-errors
if aws cloudwatch put-metric-data --namespace vehicle-catalog --metric-name backup.size --value "$size"; then
  echo "The database is backed up as $name, of $size bytes."
else
  echo "The database is backed up as $name. Its size, $size bytes, was not reported." >&2
fi
