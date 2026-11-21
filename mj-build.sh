#!/bin/bash
# mj-build.sh — builds the 7 freeway modules with mj, in cascade order.
#
# The modules' mj.toml/mj.lock are committed. Externals (slf4j, h2) resolve
# straight from Maven Central; only the inter-module cascade rides through
# vendor-mj/: this script vendors each module's built jar (with a bare pom —
# every module declares its full freeway closure directly) so the next one
# resolves it.
#
# MJ overrides the mj binary; default is the installed one (the CI installs
# it from the mj-cargo releases via the one-liner).
set -e

FREEWAY=$(cd "$(dirname "$0")" && pwd)
MJ="${MJ:-$HOME/.mj/bin/mj}"
VENDOR="$FREEWAY/vendor-mj"
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
    (cd "$FREEWAY/$module" && "$MJ" lock)
    (cd "$FREEWAY/$module" && "$MJ" build)
    (cd "$FREEWAY/$module" && "$MJ" test) > "$LOGS/mj-$module.log" 2>&1
    grep -E "tests:" "$LOGS/mj-$module.log" | tail -1 || echo "  (no tests)"
    vendor_module "$module"
    echo -e "${GREEN}==== $module done ====${NC}"
}

echo "========================================="
echo " mj building freeway — 7 modules"
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
echo -e " mj build complete: ${GREEN}7/7 modules green${NC}"
echo "========================================="
