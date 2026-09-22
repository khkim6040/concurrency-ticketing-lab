#!/usr/bin/env bash
# 전략 조합 38가지를 하나씩 돌려 결과 구간만 docs/cases/*.png로 남긴다.
# Compose 스택이 떠 있어야 한다. Node 필요. Playwright는 임시 디렉터리에 설치한다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(cd "$HERE/.." && pwd)/docs/cases
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
npm init -y >/dev/null
npm install --silent --no-audit --no-fund playwright
npx playwright install chromium
# node의 ESM 리졸버는 cwd가 아니라 실행 파일 위치부터 node_modules를 찾으므로,
# $WORK에 설치한 playwright를 찾도록 스크립트를 $WORK로 복사한 뒤 실행한다.
cp "$HERE/case-shots.mjs" "$WORK/case-shots.mjs"
mkdir -p "$OUT"
BASE="$BASE" OUT_DIR="$OUT" node "$WORK/case-shots.mjs"
du -ch "$OUT"/*.png | tail -1
