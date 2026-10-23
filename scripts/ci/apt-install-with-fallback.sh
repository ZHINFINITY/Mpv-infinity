#!/usr/bin/env bash
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Install CI build packages with bounded network time.
#
# Only the network phases (index update and --download-only) run under a
# timeout, so an interrupted attempt can never leave dpkg half-configured. If
# the runner's default mirror is slow or failing, the sources are pointed at the
# next mirror and the download is retried. The final install uses packages that
# are already in the local archive cache, so it does not depend on the network.
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "usage: $0 <package>..." >&2
  exit 2
fi

cache_dir="${MPV_APT_ARCHIVE_DIR:-${RUNNER_TEMP:-/tmp}/mpv-native-apt-archives}"
update_timeout="${MPV_APT_UPDATE_TIMEOUT:-150}"
download_timeout="${MPV_APT_DOWNLOAD_TIMEOUT:-360}"
mirrors=(${MPV_APT_FALLBACK_MIRRORS:-http://archive.ubuntu.com/ubuntu/ http://mirrors.edge.kernel.org/ubuntu/})

sudo mkdir -p "$cache_dir/partial"
apt_opts=(
  -o "Dir::Cache::archives=$cache_dir"
  -o APT::Keep-Downloaded-Packages=true
  -o Acquire::Retries=2
  -o Acquire::http::Timeout=20
  -o Acquire::https::Timeout=20
  -o Acquire::Queue-Mode=host
)

fetch() {
  local started=$SECONDS
  timeout --kill-after=15 "$update_timeout" sudo apt-get update -q "${apt_opts[@]}" || return 1
  timeout --kill-after=15 "$download_timeout" sudo apt-get install -y -q --download-only \
    --no-install-recommends "${apt_opts[@]}" "$@" || return 1
  echo "APT index and package download finished in $((SECONDS - started))s"
}

use_mirror() {
  local mirror="$1" file
  echo "::warning::APT download exceeded its time budget or failed; switching Ubuntu mirror to $mirror"
  for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [[ -f "$file" ]] || continue
    sudo sed -i -E \
      -e "s#mirror\+file:/etc/apt/apt-mirrors\.txt#${mirror}#g" \
      -e "s#https?://([a-z0-9-]+\.)*(azure\.)?archive\.ubuntu\.com/ubuntu/?#${mirror}#g" \
      -e "s#https?://mirrors\.edge\.kernel\.org/ubuntu/?#${mirror}#g" \
      "$file"
  done
  # Security updates stay on security.ubuntu.com; they are not rewritten above.
}

if ! fetch "$@"; then
  fetched=false
  for mirror in "${mirrors[@]}"; do
    use_mirror "$mirror"
    if fetch "$@"; then
      fetched=true
      break
    fi
  done
  if [[ "$fetched" != true ]]; then
    echo "APT packages could not be downloaded from the default or fallback mirrors" >&2
    exit 1
  fi
fi

# All packages are now local; install without a network time limit so dpkg is never interrupted.
sudo apt-get install -y -q --no-install-recommends "${apt_opts[@]}" "$@"
