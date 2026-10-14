#!/usr/bin/env bash
# Watches what visitors get from the site while a release replaces the API. Once a second it asks
# for /api/health/readiness, which an API that is ready answers 200, and for /api/me, which the API
# answers 401 to a visitor without a session and writes to its log with a trace id. Each request
# is given 70 seconds, which is longer than CloudFront waits for the host.
#
#   release-watch.sh <the site's address> [<file>]      release-watch.sh https://catalog.rgonz.dev
#
# Each request is one line of the file as soon as it is answered: when it was asked, in UTC to the
# millisecond, which of the two, the status, the seconds it took, and curl's exit code. The file is
# named after the moment the watch began unless one is named.
#
# Stopped with Ctrl-C, or with kill when it was started in the background, the watch waits for the
# requests under way and prints the counts by status and every stretch in which the API did not
# answer. A request answered within half a second with the status its address has from an API
# that is up found the API: the slowest such answer of the releases watched so far took 0.34
# seconds, and a request that found no API waits at least the quarter second after which Caddy
# tries again. Any other request begins a stretch or goes on with one, and a stretch ends once both
# addresses have found the API again. For each stretch: its first and last request, the last
# request that found the API before it, and for each address how many requests were kept and
# answered, how many were answered 502 or 504 or not at all, and the longest wait.
set -euo pipefail
site="${1:?usage: $0 <the address of the site> [<file>]}"
file="${2:-release-watch-$(date --utc +%Y%m%dT%H%M%SZ).log}"

ask() { # the path
  local asked answered code=0
  asked="$(date --utc +%Y-%m-%dT%H:%M:%S.%3NZ)"
  answered="$(curl --silent --output /dev/null --max-time 70 \
    --write-out '%{http_code} %{time_total}' "$site$1")" || code=$?
  printf '%s %s %s %s\n' "$asked" "$1" "$answered" "$code" >>"$file"
}

summary() {
  echo "$(wc -l <"$file") requests in $file"
  sort "$file" | awk '
    BEGIN { PROCINFO["sorted_in"] = "@ind_str_asc" }
    function found_the_api(path, status, seconds) {
      return seconds < 0.5 && ((path == "/api/health/readiness" && status == 200) ||
        (path == "/api/me" && status == 401))
    }
    function end_the_stretch(  path) {
      stretches = stretches sprintf("Without an API from %s %s to %s %s. " \
        "The last request to find it before was asked at %s.\n", \
        first_asked, first_path, last_asked, last_path, before_asked)
      for (path in asked_in_it) {
        stretches = stretches sprintf("  %s: %d kept and answered, %d answered 502, " \
          "%d answered 504, %d not answered, the longest wait %.2f seconds\n", \
          path, kept[path], gone[path, 502], gone[path, 504], gone[path, "000"], waited[path])
      }
      in_a_stretch = 0
      delete asked_in_it; delete kept; delete gone; delete waited
    }
    {
      asked = $1; path = $2; status = $3; seconds = $4
      paths[path] = 1
      count[path, status]++
      if (found_the_api(path, status, seconds)) {
        found[path] = 1
        if (in_a_stretch && found["/api/health/readiness"] && found["/api/me"]) end_the_stretch()
        if (!in_a_stretch) before_asked = asked
        next
      }
      if (!in_a_stretch) {
        in_a_stretch = 1; first_asked = asked; first_path = path
        if (before_asked == "") before_asked = "no time: it is the first request"
      }
      delete found
      last_asked = asked; last_path = path
      asked_in_it[path] = 1
      if (status == 502 || status == 504 || status == "000") gone[path, status]++; else kept[path]++
      if (seconds > waited[path]) waited[path] = seconds
    }
    END {
      for (path in paths) {
        line = path ":"
        for (key in count) {
          split(key, by, SUBSEP)
          if (by[1] == path)
            line = line sprintf(" %d %s,", count[key], \
              by[2] == "000" ? "not answered" : "answered " by[2])
        }
        print substr(line, 1, length(line) - 1)
      }
      if (in_a_stretch) end_the_stretch()
      printf "%s", (stretches == "" ? "No stretch without an API.\n" : stretches)
    }'
}

stopped=
trap 'stopped=yes' INT TERM
echo "Watching $site: one line a request in $file. Ctrl-C stops it and prints the summary."
while [ -z "$stopped" ]; do
  ask /api/health/readiness &
  ask /api/me &
  sleep 1 || true
done
wait
summary
