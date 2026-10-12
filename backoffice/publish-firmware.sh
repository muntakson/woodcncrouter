#!/usr/bin/env bash
# Publish an Arduino firmware .hex for the app's "Update firmware" menu.
# Usage: ./publish-firmware.sh <file.hex|file.bin> "<name>" "<version>" ["notes"] [promini|bluepill]
#   promini : Intel HEX for the Arduino Pro Mini bootloader (STK500v1)
#   bluepill: raw .bin for the STM32duino/Maple DFU bootloader, loaded at 0x08002000
#   fluidnc : .zip of 0x<offset>.bin parts for the ESP32 ROM bootloader (Macrobase 6-axis card, 4 MB)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$1"; NAME="$2"; VERSION="$3"; NOTES="${4:-}"; TARGET="${5:-promini}"
mkdir -p "$HERE/firmware"
FILE="$(basename "$SRC")"
cp "$SRC" "$HERE/firmware/$FILE"
if [[ "$SRC" == *.bin || "$SRC" == *.zip ]]; then
  SIZE="$(stat -c %s "$SRC")"
else
# flash bytes = highest data address + 1 (type-00 records)
SIZE="$(python3 - "$SRC" <<'PY'
import sys
top = base = 0
for l in open(sys.argv[1]):
    l = l.strip()
    if not l.startswith(':'): continue
    n, a, t = int(l[1:3], 16), int(l[3:7], 16), int(l[7:9], 16)
    if t == 0: top = max(top, base + a + n)
    elif t == 2: base = int(l[9:13], 16) << 4
    elif t == 4: base = int(l[9:13], 16) << 16
print(top)
PY
)"
fi
SHA="$(sha256sum "$SRC" | cut -d' ' -f1)"
FILE="$FILE" NAME="$NAME" VERSION="$VERSION" NOTES="$NOTES" SIZE="$SIZE" SHA="$SHA" TARGET="$TARGET" node -e '
const fs = require("fs"), f = process.argv[1], e = process.env;
const list = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : [];
const blue = e.TARGET === "bluepill", esp = e.TARGET === "fluidnc";
const rec = { file: e.FILE, name: e.NAME, version: e.VERSION, notes: e.NOTES, size: +e.SIZE, sha256: e.SHA,
              target: e.TARGET, format: esp ? "zip" : blue ? "bin" : "hex",
              mcu: esp ? "esp32" : blue ? "stm32f103c8" : "atmega328p", clockHz: esp ? 240000000 : blue ? 72000000 : 8000000,
              baud: esp ? 115200 : blue ? 0 : 38400, address: esp ? "0x1000" : blue ? "0x08002000" : "0x0000", published: Date.now() };
fs.writeFileSync(f, JSON.stringify(list.filter((r) => r.file !== rec.file).concat(rec), null, 2));
' "$HERE/firmware/firmware.json"
echo "Published $FILE ($SIZE bytes)"
