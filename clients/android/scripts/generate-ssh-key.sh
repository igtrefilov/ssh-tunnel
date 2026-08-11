#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
client_dir=$(cd -- "$script_dir/.." && pwd)
private_key="$client_dir/keys/ssh_tunnel_key"
public_key="$private_key.pub"
asset_dir="$client_dir/app/src/main/assets-bundled"
asset_key="$asset_dir/ssh_tunnel_key"

if [[ -e "$private_key" || -e "$public_key" || -e "$asset_key" ]]; then
    echo "Refusing to overwrite an existing client SSH key." >&2
    echo "Existing key or asset: $private_key / $asset_key" >&2
    exit 1
fi

mkdir -p "$client_dir/keys" "$asset_dir"
ssh-keygen -t rsa -b 4096 -m PEM -N '' \
    -C 'ssh-split-tunnel-android' \
    -f "$private_key"
install -m 0600 "$private_key" "$asset_key"

echo "Client SSH key generated."
ssh-keygen -l -f "$public_key"
echo "Copy the public .pub file to the VPS, then deploy it with:"
echo "  sudo ./server/gateway/deploy.sh --public-key-file /path/to/ssh_tunnel_key.pub"
echo "Use the same public key with server/jump-host/deploy.sh when jump mode is enabled."
