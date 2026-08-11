#!/usr/bin/env bash
set -Eeuo pipefail

profile=
purge_key=0
remove_tools=0
confirmed=0

usage() {
    cat <<'EOF'
Usage: ./clients/linux/uninstall.sh --profile NAME --yes [OPTIONS]

  --purge-key     Also remove the profile private/public key
  --remove-tools  Remove shared unit and ssh-tunnel-exec
  --yes           Required confirmation
EOF
}

while (( $# )); do
    case $1 in
        --profile) profile=${2:-}; shift 2 ;;
        --purge-key) purge_key=1; shift ;;
        --remove-tools) remove_tools=1; shift ;;
        --yes) confirmed=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done

[[ "$profile" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || {
    printf 'A valid --profile is required.\n' >&2; exit 2;
}
(( confirmed )) || { printf 'Refusing without --yes.\n' >&2; exit 1; }

config_root=${XDG_CONFIG_HOME:-$HOME/.config}
profile_file=$config_root/ssh-tunnel/client/$profile.env
identity_file=
jump_identity_file=
server_known_hosts_file=$config_root/ssh-tunnel/known_hosts/$profile.server
jump_known_hosts_file=$config_root/ssh-tunnel/known_hosts/$profile.jump
if [[ -r "$profile_file" ]]; then
    # shellcheck disable=SC1090
    source "$profile_file"
    identity_file=${IDENTITY_FILE:-}
    jump_identity_file=${JUMP_IDENTITY_FILE:-}
    server_known_hosts_file=${SERVER_KNOWN_HOSTS_FILE:-$server_known_hosts_file}
    jump_known_hosts_file=${JUMP_KNOWN_HOSTS_FILE:-$jump_known_hosts_file}
fi

unit=ssh-tunnel-client@$profile.service
systemctl --user disable --now "$unit" 2>/dev/null || true
rm -f -- "$profile_file" "$server_known_hosts_file" "$jump_known_hosts_file"

if (( purge_key )) && [[ -n "$identity_file" ]]; then
    rm -f -- "$identity_file" "$identity_file.pub"
    if [[ -n "$jump_identity_file" && "$jump_identity_file" != "$identity_file" ]]; then
        rm -f -- "$jump_identity_file" "$jump_identity_file.pub"
    fi
    printf 'Removed profile and its identity key. The key cannot be recovered locally.\n'
else
    printf 'Removed profile. Identity key was preserved.\n'
fi

if (( remove_tools )); then
    rm -f -- \
        "$config_root/systemd/user/ssh-tun-client@.service" \
        "$config_root/systemd/user/ssh-tunnel-client@.service" \
        "$HOME/.local/libexec/ssh-tunnel/ssh-tunnel-client-run" \
        "$HOME/.local/bin/ssh-tunnel-exec" \
        "$HOME/.local/bin/ssh-tun-exec"
    systemctl --user daemon-reload
    printf 'Removed shared client tools.\n'
fi
