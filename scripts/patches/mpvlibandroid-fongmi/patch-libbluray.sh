#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
  echo "Usage: $0 <libbluray-1.4.1-source-directory>" >&2
  exit 2
fi

source_dir="$(cd "$1" && pwd)"
patch_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

expected_sha256() {
  case "$1" in
    libbluray-1.4.1-still-eof.patch)    printf '%s\n' 'cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7' ;;
    libbluray-1.4.1-checked-seek.patch) printf '%s\n' '1055d99f0940b3077e9f7171a220f242396e6bf5196415f3fc987b316d51447c' ;;
    libbluray-1.4.1-menu-info.patch)    printf '%s\n' '17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c' ;;
    libbluray-1.4.1-bdj-pointer.patch)  printf '%s\n' '232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85' ;;
    *) return 1 ;;
  esac
}

# Match the order and zero-fuzz/idempotency checks used by
# mpvlibAndroid's FongMi AAR build at commit d04217abe8e72e4a099269f045855bf69b87d53b.
for patch_name in \
  libbluray-1.4.1-still-eof.patch \
  libbluray-1.4.1-checked-seek.patch \
  libbluray-1.4.1-menu-info.patch \
  libbluray-1.4.1-bdj-pointer.patch; do
  patch_file="$patch_dir/$patch_name"
  if [[ ! -s "$patch_file" ]]; then
    echo "Missing required libbluray patch: $patch_file" >&2
    exit 1
  fi
  actual_sha256="$(sha256sum "$patch_file" | awk '{print $1}')"
  pinned_sha256="$(expected_sha256 "$patch_name")"
  if [[ "$actual_sha256" != "$pinned_sha256" ]]; then
    echo "Unexpected SHA-256 for $patch_name: $actual_sha256" >&2
    exit 1
  fi
  if patch --batch --forward --fuzz=0 --dry-run -d "$source_dir" -p1 < "$patch_file" >/dev/null 2>&1; then
    patch --batch --forward --fuzz=0 -d "$source_dir" -p1 < "$patch_file"
  elif ! patch --batch --reverse --fuzz=0 --dry-run -d "$source_dir" -p1 < "$patch_file" >/dev/null 2>&1; then
    echo "libbluray source does not match $patch_name (expected pristine 1.4.1 or already-patched source)." >&2
    exit 1
  fi
done

for symbol in bd_seek_time_checked bd_get_titles_with_menu_info; do
  if ! grep -q "$symbol" "$source_dir/src/libbluray/bluray.h" || \
     ! grep -q "$symbol" "$source_dir/src/libbluray/bluray.c"; then
    echo "Patched libbluray is missing required FongMi MPV API: $symbol" >&2
    exit 1
  fi
done

printf '%s\n' "Verified MPV-RX FongMi libbluray 1.4.1 patch set in $source_dir"
