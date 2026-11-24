#!/usr/bin/env bash
# Add this run's vendor/ tree to gh-pages without deleting older releases.
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "usage: ci/publish-pages.sh <version>" >&2
    exit 2
fi
version=$1
root=$(git rev-parse --show-toplevel)
worktree=$(mktemp -d "${RUNNER_TEMP:-/tmp}/freeway-pages.XXXXXX")

cleanup() {
    git -C "$root" worktree remove --force "$worktree" >/dev/null 2>&1 || rm -rf "$worktree"
}
trap cleanup EXIT

if ! git -C "$root" fetch origin gh-pages:refs/remotes/origin/gh-pages 2>/dev/null; then
    :
fi
if git -C "$root" rev-parse --verify --quiet refs/remotes/origin/gh-pages >/dev/null; then
    git -C "$root" worktree add --detach "$worktree" refs/remotes/origin/gh-pages
else
    git -C "$root" worktree add --detach "$worktree" HEAD
    (
        cd "$worktree"
        git checkout --orphan gh-pages
        git rm -rf . >/dev/null 2>&1 || true
    )
fi

mkdir -p "$worktree"
cp -R "$root"/vendor/. "$worktree"/
touch "$worktree/.nojekyll"
(
    cd "$worktree"
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    git add -A
    if git diff --cached --quiet; then
        echo "gh-pages already contains $version — nothing to commit"
        exit 0
    fi
    git commit -m "publish freeway repository $version"
    git push origin HEAD:gh-pages
)
