#!/usr/bin/env bash
# Applies the patches in <patch-dir> to the checkout in <checkout-dir>, in file name order.
# A patch that upstream already contains is skipped with a warning; a patch that neither applies
# nor is already upstream fails the run. See README.md in this directory.
# Usage: apply-patches.sh <patch-dir> <checkout-dir>
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <patch-dir> <checkout-dir>" >&2
  exit 2
fi
checkout_dir=$2
if [ ! -d "$1" ]; then
  echo "No patches for $checkout_dir: $1 does not exist"
  exit 0
fi
patch_dir=$(cd "$1" && pwd)

shopt -s nullglob
for patch in "$patch_dir"/*.patch; do
  # GitHub annotations need a path relative to the repository root.
  name=${patch#"$PWD"/}
  if git -C "$checkout_dir" apply --check "$patch" 2>/dev/null; then
    git -C "$checkout_dir" apply "$patch"
    echo "Applied $name"
  elif git -C "$checkout_dir" apply --check --reverse "$patch" 2>/dev/null; then
    echo "::warning file=$name::Upstream already contains this patch; delete it"
  else
    echo "::error file=$name::The patch neither applies nor is already upstream; regenerate it against the pinned commit, or delete it if upstream merged a different fix"
    git -C "$checkout_dir" apply --check "$patch" || true
    exit 1
  fi
done
