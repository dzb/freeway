#!/usr/bin/env bash
# Consume the published jeno repository as a real user would: lock, build and
# run a tiny project against the Pages URL. A failed smoke test blocks release.
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "usage: ci/smoke-consume.sh <version>" >&2
    exit 2
fi
version=$1
owner=${GITHUB_REPOSITORY_OWNER:-dzb}
repo=${GITHUB_REPOSITORY#*/}
repo=${repo:-freeway}
base=${PAGES_BASE_URL:-https://${owner}.github.io/${repo}}
base=${base%/}
descriptor="$base/freeway-ioc/$version/freeway-ioc-$version.jeno.toml"
jeno=${JENO:-$HOME/.jeno/bin/jeno}

echo "waiting for $descriptor"
ok=0
for attempt in $(seq 1 30); do
    if curl -fsSI "$descriptor" >/dev/null 2>&1; then
        ok=1
        break
    fi
    echo "  attempt $attempt/30: not visible yet"
    sleep 10
done
if [ "$ok" -ne 1 ]; then
    echo "Pages did not publish $descriptor" >&2
    exit 1
fi

project=$(mktemp -d "${RUNNER_TEMP:-/tmp}/freeway-smoke.XXXXXX")
cleanup() {
    rm -rf "$project"
}
trap cleanup EXIT
mkdir -p "$project/src/main/java/smoke"
cat > "$project/jeno.toml" <<TOML
[project]
name = "freeway-smoke"
version = "0.0.0"
main = "smoke.Main"

[java]
version = 25

[repos.freeway]
type = "jeno"
url = "$base/"

[deps]
"freeway-ioc" = "freeway:$version"
TOML
cat > "$project/src/main/java/smoke/Main.java" <<'JAVA'
package smoke;

import com.jujin.freeway.ioc.Scope;

public final class Main {
    public static void main(String[] args) {
        System.out.println("scope=" + Scope.SINGLETON);
    }
}
JAVA

(
    cd "$project"
    "$jeno" lock
    "$jeno" build
    output=$("$jeno" run)
    echo "$output"
    case "$output" in
        *scope=SINGLETON*) ;;
        *) echo "smoke run produced unexpected output: $output" >&2; exit 1 ;;
    esac
)
