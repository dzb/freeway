#!/usr/bin/env bash
# Fail unless the POM, every module manifest and the release notes agree on one
# non-snapshot version. Prints every mismatch instead of only the first.
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "usage: ci/release-gate.sh <version>" >&2
    exit 2
fi
version=$1
root=$(cd "$(dirname "$0")/.." && pwd)
status=0

pom_version=$(grep -m1 '<version>' "$root/pom.xml" \
    | sed -E 's|.*<version>([^<]+)</version>.*|\1|')
if [ "$pom_version" != "$version" ] || [[ "$pom_version" == *-SNAPSHOT ]]; then
    echo "pom.xml: expected $version, found $pom_version" >&2
    status=1
fi

for manifest in "$root"/*/jeno.toml; do
    module=$(basename "$(dirname "$manifest")")
    module_version=$(grep -m1 '^version[[:space:]]*=' "$manifest" \
        | sed -E 's|.*"([^"]+)".*|\1|')
    if [ "$module_version" != "$version" ]; then
        echo "$module/jeno.toml: expected $version, found $module_version" >&2
        status=1
    fi
done

notes="$root/docs/releases/release-notes-$version.md"
if [ ! -f "$notes" ]; then
    echo "docs/releases/release-notes-$version.md: missing" >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    exit 1
fi
echo "release gate passed for $version"
