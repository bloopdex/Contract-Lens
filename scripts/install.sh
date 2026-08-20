#!/usr/bin/env sh
# ContractLens installer (Linux/macOS) — installs the fat JAR and a
# `contractlens` shim into ~/.local/lib/contractlens and
# ~/.local/bin, adding the latter to PATH guidance.
#
# Usage (from the release bundle):
#   sh install.sh
#
# Requires a JRE 17+ (java on PATH or JAVA_HOME).

set -eu

BUNDLE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VERSION="$(basename "$BUNDLE" | sed 's/^contractlens-//')"
LIB_DIR="$HOME/.local/lib/contractlens"
BIN_DIR="$HOME/.local/bin"

JAR="$(find "$BUNDLE" -maxdepth 1 -name 'contractlens-*-all.jar' | head -n 1)"
if [ -z "$JAR" ]; then
    echo "error: release jar not found next to the installer" >&2
    exit 1
fi

# Verify the bundled checksum before installing anything.
if [ -f "$BUNDLE/SHA256SUMS" ]; then
    EXPECTED="$(awk '/contractlens.*all\.jar$/ {print $1}' "$BUNDLE/SHA256SUMS")"
    ACTUAL="$(sha256sum "$JAR" | cut -d' ' -f1)"
    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "error: checksum verification FAILED for $(basename "$JAR") - do not install" >&2
        exit 1
    fi
    echo "checksum verified: $ACTUAL"
else
    echo "warning: no SHA256SUMS next to the installer - skipping checksum verification" >&2
fi

mkdir -p "$LIB_DIR" "$BIN_DIR"
cp "$JAR" "$LIB_DIR/contractlens.jar"

cat > "$BIN_DIR/contractlens" <<EOF
#!/usr/bin/env sh
# contractlens $VERSION shim (installed by install.sh)
JAVA=\${JAVA_HOME:+\$JAVA_HOME/bin/java}
JAVA=\${JAVA:-java}
exec "\$JAVA" -jar "$LIB_DIR/contractlens.jar" "\$@"
EOF
chmod +x "$BIN_DIR/contractlens"

echo ""
echo "installed: $LIB_DIR/contractlens.jar"
echo "shim:      $BIN_DIR/contractlens"
case ":$PATH:" in
    *":$BIN_DIR:"*) echo "PATH already includes $BIN_DIR" ;;
    *) echo "add to PATH:  export PATH=\"$BIN_DIR:\$PATH\"  (or your shell profile)" ;;
esac
echo "verify:    contractlens --version"
echo "uninstall: rm -rf $LIB_DIR $BIN_DIR/contractlens"
