#!/usr/bin/env bash
# Apply the reviewed compatibility patch without overwriting other local edits.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
miuix_dir="$project_dir/submodule/miuix"
patch_file="$project_dir/patches/miuix/build-compatibility.patch"
mode=${1:---apply}
case "$mode" in
    --apply|--reverse) ;;
    *) printf 'Usage: bash scripts/prepare-miuix.sh [--apply|--reverse]\n' >&2; exit 2 ;;
esac
if [[ ! -f "$miuix_dir/settings.gradle.kts" ]]; then
    if [[ "$mode" == --reverse ]]; then exit 0; fi
    git -C "$project_dir" submodule update --init --recursive -- submodule/miuix
fi

if [[ "$mode" == --reverse ]]; then
    if git -C "$miuix_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
        git -C "$miuix_dir" apply --reverse "$patch_file"
        printf 'Removed the recorded Miuix compatibility patch.\n'
    elif ! git -C "$miuix_dir" apply --check "$patch_file" 2>/dev/null; then
        printf 'Miuix differs from both patch states. Preserve local edits and reconcile the patch before updating the submodule.\n' >&2
        exit 1
    fi
elif git -C "$miuix_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
    : # Already applied (including changes that upstream may have incorporated).
elif git -C "$miuix_dir" apply --check "$patch_file"; then
    git -C "$miuix_dir" apply "$patch_file"
    printf 'Applied the Miuix compatibility patch.\n'
else
    printf 'Cannot apply the Miuix compatibility patch. Review local edits or refresh patches/miuix/build-compatibility.patch for the new submodule revision.\n' >&2
    exit 1
fi
