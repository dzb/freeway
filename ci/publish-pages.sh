#!/usr/bin/env bash
# Publish new immutable module/version triples and merge their indexes into gh-pages.
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "usage: ci/publish-pages.sh <version>" >&2
    exit 2
fi
release_version=$1
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

# Publish one immutable version directory at a time. Never replace bytes that
# an existing release already served; only add a new version or accept an
# exact byte-for-byte repeat.
for module_dir in "$root"/vendor/*; do
    [ -d "$module_dir" ] || continue
    module=$(basename "$module_dir")
    source_index="$module_dir/index.jeno.toml"
    if [ ! -f "$source_index" ]; then
        echo "missing $source_index; jeno 0.3.3 must publish an index" >&2
        exit 1
    fi
    if [ ! -d "$module_dir/$release_version" ]; then
        echo "missing $module_dir/$release_version; refusing to publish a partial release" >&2
        exit 1
    fi
    if ! grep -Fq "\"$release_version\"" "$source_index"; then
        echo "$source_index does not list $release_version" >&2
        exit 1
    fi

    for version_dir in "$module_dir"/*; do
        [ -d "$version_dir" ] || continue
        version=$(basename "$version_dir")
        destination="$worktree/$module/$version"
        if [ -e "$destination" ]; then
            if ! diff -qr "$version_dir" "$destination" >/dev/null; then
                echo "refusing to replace published $module:$version" >&2
                exit 1
            fi
        else
            mkdir -p "$worktree/$module"
            cp -R "$version_dir" "$worktree/$module/"
        fi
    done

    destination_index="$worktree/$module/index.jeno.toml"
    if [ -f "$destination_index" ]; then
        python3 "$root/ci/merge-index.py" "$destination_index" "$source_index"
    else
        mkdir -p "$worktree/$module"
        cp "$source_index" "$destination_index"
    fi
done

touch "$worktree/.nojekyll"
(
    cd "$worktree"
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    git add -A
    if git diff --cached --quiet; then
        echo "gh-pages already contains $release_version — nothing to commit"
        exit 0
    fi
    git commit -m "publish freeway repository $release_version"
    git push origin HEAD:gh-pages
)
