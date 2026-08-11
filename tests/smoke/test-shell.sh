#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPOSITORY_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)

temporary_directory=$(mktemp -d)
trap 'rm -rf -- "$temporary_directory"' EXIT

while IFS= read -r -d '' script; do
    bash -n "$script"
done < <(
    find \
        "$REPOSITORY_ROOT/server" \
        "$REPOSITORY_ROOT/clients/linux" \
        "$REPOSITORY_ROOT/clients/android/scripts" \
        "$REPOSITORY_ROOT/tools" \
        "$REPOSITORY_ROOT/tests" \
        -type f -name '*.sh' -print0
    find "$REPOSITORY_ROOT/clients/linux/bin" -type f -print0
)

ssh-keygen -q -t ed25519 -N '' -f "$temporary_directory/client"

"$REPOSITORY_ROOT/server/gateway/deploy.sh" --dry-run \
    --public-key-file "$temporary_directory/client.pub" >/dev/null
"$REPOSITORY_ROOT/server/jump-host/deploy.sh" --dry-run \
    --ssh-user test-user \
    --gateway-host 192.0.2.10 \
    --public-key-file "$temporary_directory/client.pub" >/dev/null
"$REPOSITORY_ROOT/clients/linux/install.sh" --dry-run \
    --server 192.0.2.10 >/dev/null
"$REPOSITORY_ROOT/clients/linux/install.sh" --dry-run \
    --server 10.0.0.10 \
    --jump-host 192.0.2.20 \
    --jump-user jump-user >/dev/null
"$REPOSITORY_ROOT/clients/linux/install-standalone.sh" --dry-run \
    --server 192.0.2.10 >/dev/null

server_commit=$(git -C \
    "$REPOSITORY_ROOT/server/gateway/third_party/hev-socks5-server" rev-parse HEAD)
android_commit=$(git -C \
    "$REPOSITORY_ROOT/clients/android/app/src/main/jni/hev-socks5-tunnel" rev-parse HEAD)
[[ $server_commit == 8b9664df29593b52763f1a588932a2f739ae3dc5 ]]
[[ $android_commit == d1178b52fccd8659201e1e3f83030f298c998865 ]]

if git -C "$REPOSITORY_ROOT" ls-files | \
    grep -E '\.(p12|pfx|jks|keystore|pass)$' >/dev/null; then
    printf 'Private signing material is tracked by Git.\n' >&2
    exit 1
fi

python3 -m py_compile "$REPOSITORY_ROOT"/tests/integration/*.py
printf 'Shell and deployment smoke tests passed.\n'
