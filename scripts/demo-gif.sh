#!/usr/bin/env bash
# 공유 링크 하나로 NONE을 돌리고, 같은 시드로 CONDITIONAL_UPDATE + UNIQUE_CONSTRAINT를 돌려 비교 표까지 녹화해 docs/demo.gif를 만든다.
# Compose 스택이 떠 있어야 한다. Node, ffmpeg 필요. Playwright는 임시 디렉터리에 설치한다.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
npm init -y >/dev/null
npm install --silent --no-audit --no-fund playwright
npx playwright install chromium
# node의 ESM 리졸버는 cwd가 아니라 실행 파일 위치부터 node_modules를 찾으므로,
# $WORK에 설치한 playwright를 찾도록 스크립트를 $WORK로 복사한 뒤 실행한다.
cp "$HERE/demo-gif.mjs" "$WORK/demo-gif.mjs"
BASE="$BASE" VIDEO_DIR="$WORK/video" node "$WORK/demo-gif.mjs"
ffmpeg -y -loglevel error -i "$WORK"/video/*.webm \
  -vf "fps=10,scale=720:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" "$HERE/../docs/demo.gif"
ls -l "$HERE/../docs/demo.gif"
