#!/usr/bin/env bash
# Keeps the container images a check of the pipeline uses from one run to the next, so that the
# check does not wait on Docker Hub. The workflow restores what was kept before the check and
# saves it after; this says under what name, loads it, and gathers it up again.
#
# NAMED_IN lists the files that name the check's images: Compose files, Dockerfiles, and the
# backend's tests. Nothing else says which images there are, so a new image or a new version of
# one is kept without anyone adding it anywhere.
#
#   kept-images.sh named   prints, as a step's output, a key that changes when an image named changes
#   kept-images.sh load    loads what was kept, and pulls what a Dockerfile builds on if it is not there
#   kept-images.sh keep    says what this run pulled, and gathers up the images to keep
#
# Each image is kept as a file of its own, so that they are loaded side by side: one after another
# took half a minute.
set -euo pipefail
# The key is made from sorted lines, which sort alike on every machine this way.
export LC_ALL=C
cd "$(dirname "$0")/../.."
kept="${RUNNER_TEMP:?}/kept-images"
mkdir -p "$kept/images"

# The lines that name an image: `image:` of a Compose file, `FROM` of a Dockerfile, and a
# container of the backend's tests.
naming_lines() {
  # shellcheck disable=SC2086 # the list of files is split on purpose
  grep -hE '^[[:space:]]*image:|^FROM |Container(<>)?\("' $NAMED_IN | sed -E 's/^[[:space:]]+//' | sort -u
}

# The images the Dockerfiles build on. A stage of the same Dockerfile is not one.
bases() {
  local stages
  stages="$(naming_lines | sed -nE 's/^FROM .* [Aa][Ss] ([^ ]+)$/\1/p')"
  naming_lines | sed -nE 's/^FROM (--platform=[^ ]+ )?([^ ]+).*/\2/p' | sort -u |
    grep -vxF -e "${stages:-no stage has this name}" || true
}

# Whether the image came from the registry it is named after, in this run. One that was built
# here did not, and neither did one that was pulled under another name and given this one.
pulled() { # image
  docker image inspect --format '{{join .RepoDigests "\n"}}' "$1" | grep -q "^${1%:*}@"
}

case "${1:-}" in
named)
  echo "key=$(naming_lines | sha256sum | cut -c1-16)"
  ;;
load)
  find "$kept/images" -name '*.tar' -print0 | xargs -0 -r -P 4 -n 1 docker load --input |
    sed -n 's/^Loaded image: //p' >"$kept/loaded"
  echo "Kept from an earlier run: $(paste -sd ' ' "$kept/loaded" | grep . || echo nothing)"
  # A build takes what it builds on from this machine when it is here, and what is here can be
  # kept. So it is pulled now, if it was not kept.
  for image in $(bases); do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull --quiet "$image"
  done
  ;;
keep)
  images=()
  now=()
  while read -r image; do
    naming_lines | grep -qF -- "$image" || continue
    if grep -qxF -- "$image" "$kept/loaded"; then
      images+=("$image")
    elif pulled "$image"; then
      images+=("$image")
      now+=("$image")
    fi
  done < <(docker image ls --format '{{.Repository}}:{{.Tag}}' | sort -u)
  echo "Pulled in this run: ${now[*]:-nothing}"
  # What was restored is kept as it is when it was restored under its own key: nothing changed.
  if [ "${RESTORED_AS_NAMED:-}" != true ]; then
    echo "Kept for the next run: ${images[*]}"
    rm -f "$kept/images"/*.tar
    printf '%s\n' "${images[@]}" |
      xargs -P 4 -I {} sh -c 'docker save --output "$0/$(echo "$1" | tr "/:" "__").tar" "$1"' "$kept/images" {}
  fi
  ;;
*)
  echo "usage: $0 named|load|keep" >&2
  exit 2
  ;;
esac
