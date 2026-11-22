#!/bin/bash
# build.sh — the cold-start build for the 7 freeway modules.
#
# Why a script at all: the modules depend on each other through Maven
# coordinates, and mjm resolves those coordinates before it ever looks at a
# workspace. So a fresh clone needs each module's jar to exist in vendor/
# before the next module can lock. That is what this script builds up,
# one module at a time, in dependency order.
#
# For day-to-day work you do not need it: `mjm.ws.toml` (a local, uncommitted
# file created by `mjm init --ws`) lets `mjm build` at the repo root compile
# each module against its sibling's target/classes. Re-running this script is
# only required when a module's *published* jar bytes must change — which is
# not something the workspace cares about.
#
# MJM overrides the binary; default is the installed one.
set -e

FREEWAY=$(cd "$(dirname "$0")" && pwd)
MJM="${MJM:-$HOME/.mjm/bin/mjm}"
VENDOR="$FREEWAY/vendor"
GROUP_PATH="com/jujin8/freeway"
FW_VER='2.0.2'
LOGS="$FREEWAY/build-logs"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'

mkdir -p "$VENDOR" "$LOGS"

vendor_module() {
    local module=$1
    local classes="$FREEWAY/$module/target/classes"
    local dir="$VENDOR/$GROUP_PATH/$module/$FW_VER"
    mkdir -p "$dir"
    jar --create --file "$dir/$module-$FW_VER.jar" --no-manifest -C "$classes" .
    cat > "$dir/$module-$FW_VER.pom" << POM_EOF
<project><modelVersion>4.0.0</modelVersion><groupId>com.jujin8.freeway</groupId><artifactId>$module</artifactId><version>$FW_VER</version><packaging>jar</packaging></project>
POM_EOF
}

build_module() {
    local module=$1
    echo -e "\n${YELLOW}==== $module ====${NC}"
    (cd "$FREEWAY/$module" && "$MJM" lock)
    (cd "$FREEWAY/$module" && "$MJM" build)
    (cd "$FREEWAY/$module" && "$MJM" test) > "$LOGS/$module.log" 2>&1
    grep -E "tests:" "$LOGS/$module.log" | tail -1 || echo "  (no tests)"
    vendor_module "$module"
    echo -e "${GREEN}==== $module done ====${NC}"
}

echo "========================================="
echo " mjm building freeway — 7 modules"
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
echo -e " mjm build complete: ${GREEN}7/7 modules green${NC}"
echo "========================================="
