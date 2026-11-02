#!/usr/bin/env bash
# 构建网站的静态发布包（dist/site）与 zip
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$ROOT/dist"
SITE="$DIST/site"

rm -rf "$SITE"
mkdir -p "$SITE"
cp -r "$ROOT/www/." "$SITE/"
cp "$ROOT/serve.js" "$SITE/serve.js"
mkdir -p "$SITE/download" "$SITE/img"
cp "$ROOT"/dist/jianjian-*.apk "$SITE/download/" 2>/dev/null || true
cp "$ROOT"/dist/screenshots/03-preview-light.png "$SITE/img/preview-light.png" 2>/dev/null || true
cat > "$SITE/nginx.conf.example" <<'EOF'
# nginx 站点示例（放到 /etc/nginx/conf.d/jianjian.conf 后 reload）
server {
    listen 80;
    server_name notes.example.com;
    root /var/www/jianjian;          # 指向本目录
    index index.html;

    # PWA：manifest / service worker 不缓存
    location = /sw.js        { add_header Cache-Control "no-cache"; }
    location = /manifest.webmanifest { types { } default_type application/manifest+json; }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache";
    }
}
EOF
# 版本清单（APK 的「检查更新」从这里读取）
VER="$(grep -oP 'VERSION_NAME = "\K[^"]+' "$ROOT/app/src/com/heyu/notes/BuildConfigVars.java")"
CODE="$(grep -oP 'VERSION_CODE = \K[0-9]+' "$ROOT/app/src/com/heyu/notes/BuildConfigVars.java")"
APK="$(ls -t "$SITE"/download/*.apk 2>/dev/null | head -1 | xargs -n1 basename)"

# 下载页的 APK 链接指向 jsDelivr（GitHub Pages 在国内较慢）
sed -i "s#__APK_URL__#https://cdn.jsdelivr.net/gh/ming-a1/jianjian-notes@main/apk/jianjian-${VER}.apk#g" "$SITE/download.html"
cat > "$SITE/version.json" <<EOF
{"version":"$VER","code":$CODE,"url":"download/$APK","notes":"简笺 $VER · 原生 Kotlin 版"}
EOF

(cd "$DIST" && rm -f jianjian-site.zip && zip -qr jianjian-site.zip site)
echo "网站打包完成: $SITE"
echo "压缩包: $DIST/jianjian-site.zip"
