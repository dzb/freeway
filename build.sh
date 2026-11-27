#!/bin/bash
# build.sh — the cold-start build for the 7 freeway modules.
#
# Why a script at all: the modules depend on each other through Maven
# coordinates, and jeno resolves those coordinates before it ever looks at a
# workspace. So a fresh clone needs each module's jar to exist in vendor/
# before the next module can lock. That is what this script builds up,
# one module at a time, in dependency order.
#
# For day-to-day work you do not need it: `jeno.ws.toml` (a local, uncommitted
# file created by `jeno init --ws`) lets `jeno build` at the repo root compile
# each module against its sibling's target/classes. Re-running this script is
# only required when a module's *published* jar bytes must change — which is
# not something the workspace cares about.
#
# JENO overrides the binary; default is the installed one.
set -e

FREEWAY=$(cd "$(dirname "$0")" && pwd)
JENO="${JENO:-$HOME/.jeno/bin/jeno}"
VENDOR="$FREEWAY/vendor"
LOGS="$FREEWAY/build-logs"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'

mkdir -p "$VENDOR" "$LOGS"

# Publishing into a local jeno repository is what "vendoring" means here: the
# triple (jar, descriptor, sha256) is exactly what a consumer needs to resolve
# the coordinate, and jeno writes it — no hand-rolled jar, no hand-written pom.
vendor_module() {
    local module=$1
    (cd "$FREEWAY/$module" && "$JENO" package)
    (cd "$FREEWAY/$module" && "$JENO" publish "jeno:local:$VENDOR")
    if [ ! -f "$VENDOR/$module/index.jeno.toml" ]; then
        echo "jeno publish did not create $VENDOR/$module/index.jeno.toml" >&2
        exit 1
    fi
}

build_module() {
    local module=$1
    echo -e "\n${YELLOW}==== $module ====${NC}"
    (cd "$FREEWAY/$module" && "$JENO" lock)
    (cd "$FREEWAY/$module" && "$JENO" build)
    (cd "$FREEWAY/$module" && "$JENO" test) > "$LOGS/$module.log" 2>&1
    grep -aE "tests:" "$LOGS/$module.log" | tail -1 || echo "  (no tests)"
    vendor_module "$module"
    echo -e "${GREEN}==== $module done ====${NC}"
}

echo "========================================="
echo " jeno building freeway — 7 modules"
echo "========================================="

build_module freeway-commons
build_module freeway-ioc
build_module freeway-boot
build_module freeway-http
build_module freeway-db
build_module freeway-flow
build_module freeway-cloud

echo ""
echo "========================================="
echo -e " jeno build complete: ${GREEN}7/7 modules green${NC}"
echo "========================================="
