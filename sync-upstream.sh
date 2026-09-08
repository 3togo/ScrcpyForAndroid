#!/usr/bin/env bash
# Review upstream changes on a temporary branch; never merge or push main.
set -euo pipefail
sync_lock_dir=''

usage() {
    cat <<'EOF'
Usage: ./sync-upstream.sh [--yes] [--publish] [--skip-gui]
       ./sync-upstream.sh --continue [--yes] [--publish] [--skip-gui]

Start on main with your work saved. The script fetches your fork and Miuzarte,
fast-forwards main from your fork, creates a new sync branch, merges upstream,
prepares Miuix, and runs Android and Linux checks. Main is never pushed.

  --continue  Resume on the sync branch after fixing and committing conflicts
              or build/patch fixes. Checks run again before publishing.
  --yes       Approve local sync steps without prompts; does NOT publish.
  --publish   Explicitly push the tested branch to origin and open a GitHub PR.
  --skip-gui  Explicitly skip the GUI smoke test (recorded in the PR).
  -h, --help  Show this help.

By default, publishing is a separate choice after checks pass. Use a merge
commit for the resulting PR, not squash or rebase. No automatic stashing,
resets, force-pushes, branch deletion, SDK setup, or PR merging are performed.
EOF
}

say() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nsync-upstream: %s\n' "$*" >&2; exit 1; }
resume_hint() {
    printf '\nAfter fixing the problem and committing any code changes, run:\n' >&2
    if [[ "$(git branch --show-current)" == codex/sync-upstream-* ]]; then
        printf '  ./sync-upstream.sh --continue\n' >&2
    else
        printf '  ./sync-upstream.sh\n' >&2
    fi
}
confirm() {
    local reply
    read -r -p "$1 [Y/n]: " reply || return 1
    case "${reply,,}" in ''|y|yes) return 0 ;; *) return 1 ;; esac
}

github_slug() {
    local url=${1%.git}
    case "$url" in
        https://github.com/*) url=${url#https://github.com/} ;;
        git@github.com:*) url=${url#git@github.com:} ;;
        ssh://git@github.com/*) url=${url#ssh://git@github.com/} ;;
        *) return 1 ;;
    esac
    [[ "$url" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || return 1
    printf '%s' "$url"
}

assert_no_operation() {
    local operation
    for operation in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply; do
        if [[ -e "$(git rev-parse --git-path "$operation")" ]]; then
            fail "A Git operation is unfinished ($operation). Resolve and finish it first. For a conflicted sync merge, use git merge --continue, then ./sync-upstream.sh --continue; or cancel that merge with git merge --abort."
        fi
    done
}

assert_parent_clean() {
    local changes
    changes=$(git status --porcelain --untracked-files=all --ignore-submodules=dirty)
    if [[ -n "$changes" ]]; then
        printf '%s\n' "$changes" >&2
        fail "Save/commit these changes first. Nothing will be stashed or overwritten."
    fi
}

clean_for_checkout() {
    assert_parent_clean
    local changes had_patch=false
    if [[ -f submodule/miuix/settings.gradle.kts ]] &&
        git -C submodule/miuix apply --reverse --check "$project_dir/patches/miuix/build-compatibility.patch" 2>/dev/null; then
        had_patch=true
    fi
    bash scripts/prepare-miuix.sh --reverse || fail "Could not remove the recorded Miuix patch. Reconcile it without discarding local edits."
    changes=$(git status --porcelain --untracked-files=all --ignore-submodules=none)
    if [[ -n "$changes" ]]; then
        # Restore the known patch when unrelated submodule edits prevent a sync.
        if "$had_patch"; then bash scripts/prepare-miuix.sh || true; fi
        printf '%s\n' "$changes" >&2
        fail "Submodule changes remain. Save them separately first; parent git stash does not save submodule working files."
    fi
}

prepare_dependencies() {
    say 'Preparing the pinned submodules and Miuix compatibility patch'
    if ! git submodule update --init --recursive; then
        resume_hint
        fail "Submodule update failed. Resolve it before continuing."
    fi
    if ! bash scripts/prepare-miuix.sh; then
        resume_hint
        fail "The Miuix patch needs attention. Preserve any edits, refresh the recorded patch if needed, and commit the fix."
    fi
}

run_check() {
    local label=$1 log_name=$2
    shift 2
    say "$label (output: $log_dir/$log_name.log)"
    if "$@" >"$log_dir/$log_name.log" 2>&1; then
        printf 'PASS: %s\n' "$label"
    else
        tail -n 35 "$log_dir/$log_name.log" >&2
        resume_hint
        fail "$label failed. Full output: $log_dir/$log_name.log"
    fi
}

publish_branch() {
    assert_parent_clean
    [[ "$(git branch --show-current)" == "$sync_branch" && "$(git rev-parse HEAD)" == "$tested_head" ]] ||
        fail "The branch changed after testing. Run --continue to test its current contents."
    say "Pushing $sync_branch to origin (not main)"
    git push --set-upstream origin "$sync_branch" || fail "Push failed; no force-push was attempted. You can retry with --continue --publish."

    local slug existing_pr body_file="$log_dir/pr-body.md"
    slug=$(github_slug "$(git config --get remote.origin.url)" || true)
    if [[ -z "$slug" ]]; then
        say "Branch pushed. Open a PR in your fork: $sync_branch -> main, using a merge commit."
        return
    fi
    if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
        say "Branch pushed. Open the PR here (GitHub CLI is unavailable or not signed in):"
        printf 'https://github.com/%s/compare/main...%s?expand=1\n' "$slug" "$sync_branch"
        return
    fi
    existing_pr=$(gh pr list --repo "$slug" --head "$sync_branch" --base main --state open --json url --jq '.[0].url // empty') ||
        fail "Branch pushed, but PR lookup failed. Retry with --continue --publish or open the PR manually."
    if [[ -n "$existing_pr" ]]; then
        printf 'Existing PR: %s\n' "$existing_pr"
        return
    fi
    cat >"$body_file" <<EOF
Merge Miuzarte/ScrcpyForAndroid main through $upstream_oid into ScrCaster while preserving upstream ancestry and fork features.

Validation:
- Android debug APKs and unit tests passed.
- Linux backend checks and installDist passed.
- GUI smoke test: $gui_result.

Use **Create a merge commit** after review and CI pass. Do not squash or rebase this sync PR, so future upstream merges retain their common history.
EOF
    gh pr create --repo "$slug" --base main --head "$sync_branch" \
        --title "Sync upstream through ${upstream_oid:0:12}" --body-file "$body_file" ||
        fail "Branch pushed, but PR creation failed. Retry with --continue --publish or open the PR manually."
}

main() {
    local assume_yes=false publish=false continuing=false skip_gui=false interactive=false
    local project_dir sync_branch upstream_oid base_oid tested_head log_dir gui_result
    local current_branch origin_url upstream_url existing_slug git_dir candidate suffix answer argument
    for argument in "$@"; do
        case "$argument" in
            -h|--help) usage; return ;;
            --yes) assume_yes=true ;;
            --publish) publish=true ;;
            --continue) continuing=true ;;
            --skip-gui) skip_gui=true ;;
            *) fail "Unknown option: $argument. Use --help." ;;
        esac
    done
    [[ -t 0 && -t 1 ]] && interactive=true
    "$interactive" || "$assume_yes" || fail "No interactive terminal. Use --yes for local work only; add --publish separately if you want a push and PR."
    project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
    cd "$project_dir"
    [[ "$(git rev-parse --show-toplevel)" == "$project_dir" ]] || fail "Run this script from the ScrCaster checkout."
    current_branch=$(git branch --show-current)
    assert_no_operation
    assert_parent_clean
    origin_url=$(git config --get remote.origin.url) || fail "The origin remote is missing. Configure it to your fork first."
    existing_slug=$(github_slug "$origin_url" || true)
    [[ "${existing_slug,,}" != miuzarte/scrcpyforandroid ]] || fail "origin points to upstream. Set origin to your own fork first."
    existing_slug=$(github_slug "$(git config --get remote.origin.pushurl || printf '%s' "$origin_url")" || true)
    [[ "${existing_slug,,}" != miuzarte/scrcpyforandroid ]] || fail "origin's push URL points to upstream. Set it to your own fork first."
    upstream_url=$(git config --get remote.upstream.url || true)
    if [[ -n "$upstream_url" ]]; then
        existing_slug=$(github_slug "$upstream_url" || true)
        [[ "${existing_slug,,}" == miuzarte/scrcpyforandroid ]] || fail "upstream does not point to Miuzarte/ScrcpyForAndroid: $upstream_url"
    fi

    git_dir=$(git rev-parse --absolute-git-dir)
    sync_lock_dir="$git_dir/scrcaster-upstream-sync.lock"
    mkdir "$sync_lock_dir" 2>/dev/null || fail "Another sync may be running. If it has stopped, remove the empty lock directory: $sync_lock_dir"
    trap 'rmdir -- "$sync_lock_dir" 2>/dev/null || true' EXIT

    if "$continuing"; then
        sync_branch=$current_branch
        [[ "$sync_branch" == codex/sync-upstream-* ]] || fail "--continue must run on the sync branch, not ${current_branch:-detached HEAD}."
        upstream_oid=$(git config --get "branch.$sync_branch.scrcasterUpstream") || fail "This branch has no saved sync information."
        base_oid=$(git config --get "branch.$sync_branch.scrcasterBase") || fail "This branch has no saved base commit."
        if ! git merge-base --is-ancestor "$upstream_oid" HEAD || ! git merge-base --is-ancestor "$base_oid" HEAD; then
            fail "The saved upstream merge is not complete. Finish it first; if you aborted, return to main and start a new sync."
        fi
        say "Resuming $sync_branch at upstream ${upstream_oid:0:12}"
        if ! "$assume_yes"; then confirm 'Prepare dependencies and rerun all checks?' || return 0; fi
        clean_for_checkout
    else
        [[ "$current_branch" == main ]] || fail "Start on main after saving/merging your current work. To resume a sync branch, use --continue."
        say "Sync source: Miuzarte/ScrcpyForAndroid; your fork: $origin_url"
        if ! "$assume_yes"; then confirm 'Fetch updates and prepare a separate sync branch?' || return 0; fi
        if [[ -z "$upstream_url" ]]; then
            git remote add -t main upstream https://github.com/Miuzarte/ScrcpyForAndroid.git
            git remote set-url --push upstream DISABLED
            git config remote.upstream.tagOpt --no-tags
        fi
        git fetch --no-tags origin || fail "Could not fetch origin. No merge was started."
        git fetch --no-tags upstream || fail "Could not fetch upstream. No merge was started."
        if ! git merge-base --is-ancestor main origin/main && ! git merge-base --is-ancestor origin/main main; then
            fail "Local main and origin/main have diverged. Reconcile them first; this script will not reset either branch."
        fi
        clean_for_checkout
        git merge --ff-only origin/main || fail "Could not fast-forward main from origin."
        upstream_oid=$(git rev-parse upstream/main)
        if git merge-base --is-ancestor "$upstream_oid" HEAD; then
            prepare_dependencies
            say 'Already up to date with upstream. No sync branch or PR is needed.'
            return
        fi
        say 'Incoming upstream commits (up to 12 shown)'
        git --no-pager log --oneline -12 HEAD.."$upstream_oid"
        base_oid=$(git rev-parse HEAD)
        candidate="codex/sync-upstream-$(date +%Y-%m-%d)"
        sync_branch=$candidate
        suffix=2
        while git show-ref --verify --quiet "refs/heads/$sync_branch" || git show-ref --verify --quiet "refs/remotes/origin/$sync_branch"; do
            sync_branch="$candidate-$suffix"
            suffix=$((suffix + 1))
        done
        git switch -c "$sync_branch"
        git config "branch.$sync_branch.scrcasterBase" "$base_oid"
        git config "branch.$sync_branch.scrcasterUpstream" "$upstream_oid"
        if ! git merge --no-ff --no-edit -m "Merge upstream main through ${upstream_oid:0:12}" "$upstream_oid"; then
            printf '\nYour main branch is safe. Resolve conflicts on %s, stage the resolved files, and run git merge --continue.\n' "$sync_branch" >&2
            resume_hint
            fail "Upstream merge stopped. To cancel the unfinished merge, use git merge --abort."
        fi
    fi

    prepare_dependencies
    tested_head=$(git rev-parse HEAD)
    log_dir="$project_dir/build/upstream-sync/${sync_branch##*/}-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$log_dir"
    run_check 'Android APKs and unit tests' android bash build.sh assembleDebug testDebugUnitTest --console plain
    run_check 'Linux backend checks and distribution' linux bash gradlew -p desktop check installDist --console plain
    if "$skip_gui"; then
        gui_result='SKIPPED by explicit --skip-gui request; CI must verify it'
        say "$gui_result"
    elif command -v xvfb-run >/dev/null 2>&1; then
        run_check 'Linux GUI smoke test' gui xvfb-run -a bash gradlew -p desktop guiSmoke --console plain
        gui_result=passed
    elif [[ -n "${DISPLAY:-}" ]]; then
        run_check 'Linux GUI smoke test' gui bash gradlew -p desktop guiSmoke --console plain
        gui_result=passed
    else
        resume_hint
        fail "GUI testing needs DISPLAY or xvfb-run. Provide one, or explicitly resume with --continue --skip-gui and let CI verify the GUI."
    fi
    assert_parent_clean
    say "Checks finished. Review branch: $sync_branch"
    printf 'Logs: %s\n' "$log_dir"
    if ! "$publish" && "$interactive" && ! "$assume_yes"; then
        printf '\n  1) Keep the branch local (default)\n  2) Push the branch and open a PR to main\n'
        read -r -p 'Choose [1]: ' answer || answer=1
        case "$answer" in
            2) publish=true ;;
            ''|1) ;;
            *) printf 'Unrecognized choice; keeping the branch local.\n' ;;
        esac
    fi
    if "$publish"; then
        publish_branch
        say 'After review and CI pass, use Create a merge commit. Do not squash or rebase the sync PR.'
    else
        say 'Kept locally. Nothing was pushed and main was not merged with upstream.'
        printf 'To publish later: ./sync-upstream.sh --continue --publish\n'
    fi
}

main "$@"
