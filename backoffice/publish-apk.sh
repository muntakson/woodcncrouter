#!/usr/bin/env bash
# Build Grbl Sender and publish it as a release in the backoffice.
# Usage: ./publish-apk.sh ["release notes"]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../GrblSender"
AAPT="$(ls -d "$HOME"/Android/Sdk/build-tools/*/aapt | sort -V | tail -1)"

(cd "$APP" && ./gradlew assembleDebug -q)
SRC="$APP/app/build/outputs/apk/debug/app-debug.apk"
BADGE="$("$AAPT" dump badging "$SRC" 2>/dev/null | grep "^package:" || true)"
CODE="$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$BADGE")"
NAME="$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$BADGE")"
FILE="GrblSender-v${NAME}-${CODE}.apk"
cp "$SRC" "$HERE/apk/$FILE"

NOTES="${1:-}" CODE="$CODE" NAME="$NAME" FILE="$FILE" SIZE="$(stat -c %s "$SRC")" node -e '
const fs = require("fs"), f = process.argv[1];
const list = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : [];
const e = process.env;
const rel = { versionCode: +e.CODE, versionName: e.NAME, file: e.FILE, size: +e.SIZE,
              published: Date.now(), notes: e.NOTES };
const out = list.filter((r) => r.versionCode !== rel.versionCode).concat(rel);
fs.writeFileSync(f, JSON.stringify(out, null, 2));
' "$HERE/apk/releases.json"
echo "Published $FILE (versionCode $CODE)"
