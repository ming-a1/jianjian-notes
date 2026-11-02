#!/usr/bin/env bash
# 启动公网隧道（Cloudflare 快速隧道，无需注册），并记录当前地址
# 用法: ./tunnel.sh [端口，默认 8090]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${1:-8090}"
CF="$ROOT/tools/cloudflared"
LOG="/tmp/cf.log"

if [ ! -x "$CF" ]; then
  echo "缺少 tools/cloudflared，请先执行：" >&2
  echo "  curl -L -o tools/cloudflared https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64" >&2
  echo "  chmod +x tools/cloudflared" >&2
  exit 1
fi

if ! curl -sf -m 5 "http://127.0.0.1:$PORT/index.html" >/dev/null 2>&1; then
  echo "本地 $PORT 端口没有服务，先启动： node serve.js dist/site $PORT" >&2
  exit 1
fi

pkill -f "cloudflared tunnel --url http://127.0.0.1:$PORT" 2>/dev/null || true
sleep 1
: > "$LOG"
nohup "$CF" tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate --protocol http2 > "$LOG" 2>&1 &

URL=""
for _ in $(seq 1 40); do
  URL="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" 2>/dev/null | head -1 || true)"
  [ -n "$URL" ] && break
  sleep 1
done

if [ -z "$URL" ]; then
  echo "隧道启动失败，日志见 $LOG" >&2
  tail -20 "$LOG" >&2
  exit 1
fi

echo "$URL" > "$ROOT/dist/public-url.txt"
APK="$(ls -t "$ROOT"/dist/jianjian-*.apk 2>/dev/null | head -1 | xargs -n1 basename)"
echo "网页版：  $URL/"
echo "下载页：  $URL/download.html"
echo "APK 直链：$URL/download/${APK:-jianjian.apk}"
