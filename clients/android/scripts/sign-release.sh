#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
client_dir=$(cd -- "$script_dir/.." && pwd)
keystore="$client_dir/keys/ssh-tunnel-release.p12"
password_file="$client_dir/keys/ssh-tunnel-release.pass"
key_alias="${SIGNING_KEY_ALIAS:-xray-ssh-tunnel}"
expected_certificate_file="$client_dir/signing/release-certificate.sha256"

[[ -s "$keystore" ]] || {
    echo "Release keystore not found: $keystore" >&2
    exit 1
}
[[ -s "$password_file" ]] || {
    echo "Release password file not found: $password_file" >&2
    exit 1
}
[[ -s "$expected_certificate_file" ]] || {
    echo "Expected release certificate fingerprint not found: $expected_certificate_file" >&2
    exit 1
}

sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_dir" && -f "$client_dir/local.properties" ]]; then
    sdk_dir=$(sed -n 's/^sdk\.dir=//p' "$client_dir/local.properties" | head -1)
fi
[[ -d "$sdk_dir/build-tools" ]] || {
    echo "Android SDK build-tools not found. Set ANDROID_SDK_ROOT." >&2
    exit 1
}

apksigner=$(find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f \
    -name apksigner -print | sort -V | tail -1)
[[ -x "$apksigner" ]] || {
    echo "apksigner not found under $sdk_dir/build-tools" >&2
    exit 1
}

"$client_dir/gradlew" -p "$client_dir" assembleRelease --console=plain
release_dir="$client_dir/app/build/outputs/apk/release"
unsigned_apk="$release_dir/app-release-unsigned.apk"
metadata="$release_dir/output-metadata.json"
version=$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$metadata" | head -1)
[[ -n "$version" && -f "$unsigned_apk" ]] || {
    echo "Could not resolve release APK metadata" >&2
    exit 1
}

signed_apk="$release_dir/ssh-tunnel-$version-release-signed.apk"
"$apksigner" sign \
    --ks "$keystore" \
    --ks-type PKCS12 \
    --ks-key-alias "$key_alias" \
    --ks-pass "file:$password_file" \
    --out "$signed_apk" \
    "$unsigned_apk"
"$apksigner" verify --verbose "$signed_apk"
expected_certificate=$(tr -d '[:space:]' <"$expected_certificate_file" | tr '[:upper:]' '[:lower:]')
actual_certificate=$("$apksigner" verify --print-certs "$signed_apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    tr '[:upper:]' '[:lower:]')
if [[ -z "$actual_certificate" || "$actual_certificate" != "$expected_certificate" ]]; then
    rm -f -- "$signed_apk" "${signed_apk}.idsig"
    echo "Release certificate fingerprint mismatch" >&2
    echo "Expected: $expected_certificate" >&2
    echo "Actual:   ${actual_certificate:-missing}" >&2
    exit 1
fi
echo "Signed release APK: $signed_apk"
