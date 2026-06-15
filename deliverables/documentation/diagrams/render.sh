#!/usr/bin/env bash
#
# Renders every Mermaid (.mmd) source in src/ to both SVG and PNG in img/.
#
# Requirements:
#   npm install -g @mermaid-js/mermaid-cli   # provides `mmdc`
#
# Usage:
#   ./render.sh
#
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$DIR/src"
OUT="$DIR/img"
mkdir -p "$OUT"

# Puppeteer needs --no-sandbox when running as root (e.g. in CI/containers).
PCFG="$(mktemp)"
echo '{"args":["--no-sandbox","--disable-setuid-sandbox"]}' > "$PCFG"
trap 'rm -f "$PCFG"' EXIT

for f in "$SRC"/*.mmd; do
  name="$(basename "$f" .mmd)"
  echo "Rendering $name ..."
  mmdc -p "$PCFG" -i "$f" -o "$OUT/$name.svg" -b transparent
  mmdc -p "$PCFG" -i "$f" -o "$OUT/$name.png" -b white -s 2
done

echo "Done. Output in $OUT"
