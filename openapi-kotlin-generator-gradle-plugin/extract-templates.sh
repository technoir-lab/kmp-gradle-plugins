#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 0 ]; then
  echo "Usage: $(basename "$0")" >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
extract_dir="$script_dir/build/tmp/openapi-generator-templates"
target_dir="$script_dir/src/main/resources/openapi-generator-templates"

rm -rf "$extract_dir"
openapi-generator author template -g kotlin --library multiplatform -o "$extract_dir"

for template in "$target_dir"/*; do
  [ -f "$template" ] || continue
  filename="$(basename "$template")"
  [ "$filename" = "api.mustache" ] || [ "$filename" = "licenseInfo.mustache" ] && continue

  if [ -f "$extract_dir/libraries/multiplatform/$filename" ]; then
    cp "$extract_dir/libraries/multiplatform/$filename" "$template"
  elif [ -f "$extract_dir/$filename" ]; then
    cp "$extract_dir/$filename" "$template"
  fi
done
