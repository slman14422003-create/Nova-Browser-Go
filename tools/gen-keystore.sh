#!/usr/bin/env bash
# يولّد مفتاح توقيع صالحاً 30 سنة + keystore.properties + SECRETS.txt (لا ترفعها إلى git)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p signing
PASS=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 28)
keytool -genkeypair -keystore signing/nova-release.jks -storetype PKCS12 -alias nova -keyalg RSA -keysize 4096 \
  -validity 10950 -storepass "$PASS" -keypass "$PASS" -dname "CN=Nova Browser, OU=Mobile, O=Nova, C=SA"
printf 'storeFile=signing/nova-release.jks\nstorePassword=%s\nkeyAlias=nova\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore.properties
{ echo "KEYSTORE_PASSWORD=$PASS"; echo "KEY_PASSWORD=$PASS"; echo "KEY_ALIAS=nova"; echo "KEYSTORE_BASE64=$(base64 -w0 signing/nova-release.jks)"; } > SECRETS.txt
echo "تم. أضف محتويات SECRETS.txt كأسرار في GitHub، واحفظ signing/nova-release.jks نسخة احتياطية آمنة."
