#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

profile=main
server_host=
server_port=2222
server_user=ssh-tun
server_fingerprint=
local_socks_address=127.0.0.1
local_socks_port=30808
remote_socks_address=127.0.0.1
remote_socks_port=1080
identity_file=
jump_host=
jump_port=22
jump_user=
jump_identity_file=
jump_fingerprint=
start_service=1
prepare_only=0
enable_linger=0
dry_run=0

usage() {
    cat <<'EOF'
Usage: ./clients/linux/install.sh --server HOST [OPTIONS]

Gateway:
  --profile NAME                  Profile name (default: main)
  --server HOST                   Gateway address (required)
  --server-port PORT              Dedicated gateway SSH port (default: 2222)
  --server-user USER              Gateway SSH user (default: ssh-tun)
  --identity-file FILE            Gateway identity (generated when absent)
  --host-key-fingerprint SHA256   Expected gateway host-key fingerprint
  --local-port PORT               Local SOCKS port (default: 30808)
  --remote-port PORT              Gateway SOCKS port (default: 1080)

Optional jump route:
  --jump-host HOST                Jump-host address
  --jump-port PORT                Jump-host SSH port (default: 22)
  --jump-user USER                Jump-host SSH user
  --jump-identity-file FILE       Jump identity (defaults to gateway identity)
  --jump-host-key-fingerprint FP  Expected jump-host fingerprint
  --proxy-jump USER@HOST          Compatibility shorthand for host and user

Installation:
  --prepare-only                  Generate profile and identities only
  --no-start                      Install and enable without starting
  --enable-linger                 Keep the user manager running before login
  --dry-run                       Validate and print the intended layout
  -h, --help                      Show this help
EOF
}

die() {
    printf 'linux client install: %s\n' "$*" >&2
    exit 1
}

validate_port() {
    [[ $1 =~ ^[0-9]+$ ]] && (( 1 <= 10#$1 && 10#$1 <= 65535 ))
}

while (( $# )); do
    case $1 in
        --profile)
            (( $# >= 2 )) || die "--profile requires a value"
            profile=$2; shift 2
            ;;
        --server)
            (( $# >= 2 )) || die "--server requires a value"
            server_host=$2; shift 2
            ;;
        --server-port)
            (( $# >= 2 )) || die "--server-port requires a value"
            server_port=$2; shift 2
            ;;
        --server-user)
            (( $# >= 2 )) || die "--server-user requires a value"
            server_user=$2; shift 2
            ;;
        --identity-file)
            (( $# >= 2 )) || die "--identity-file requires a value"
            identity_file=$2; shift 2
            ;;
        --host-key-fingerprint)
            (( $# >= 2 )) || die "--host-key-fingerprint requires a value"
            server_fingerprint=$2; shift 2
            ;;
        --local-port)
            (( $# >= 2 )) || die "--local-port requires a value"
            local_socks_port=$2; shift 2
            ;;
        --remote-port)
            (( $# >= 2 )) || die "--remote-port requires a value"
            remote_socks_port=$2; shift 2
            ;;
        --jump-host)
            (( $# >= 2 )) || die "--jump-host requires a value"
            jump_host=$2; shift 2
            ;;
        --jump-port)
            (( $# >= 2 )) || die "--jump-port requires a value"
            jump_port=$2; shift 2
            ;;
        --jump-user)
            (( $# >= 2 )) || die "--jump-user requires a value"
            jump_user=$2; shift 2
            ;;
        --jump-identity-file)
            (( $# >= 2 )) || die "--jump-identity-file requires a value"
            jump_identity_file=$2; shift 2
            ;;
        --jump-host-key-fingerprint)
            (( $# >= 2 )) || die "--jump-host-key-fingerprint requires a value"
            jump_fingerprint=$2; shift 2
            ;;
        --proxy-jump)
            (( $# >= 2 )) || die "--proxy-jump requires USER@HOST"
            [[ $2 == *@* ]] || die "--proxy-jump requires USER@HOST"
            jump_user=${2%%@*}
            jump_host=${2#*@}
            shift 2
            ;;
        --prepare-only)
            prepare_only=1; start_service=0; shift
            ;;
        --no-start)
            start_service=0; shift
            ;;
        --enable-linger)
            enable_linger=1; shift
            ;;
        --dry-run)
            dry_run=1; shift
            ;;
        -h|--help)
            usage; exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

[[ -n $server_host ]] || die "--server is required"
[[ $profile =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "invalid profile name"
[[ $server_user =~ ^[a-z_][a-z0-9_-]*$ ]] || die "invalid server user"
[[ $server_host != -* && $server_host != *[[:space:],]* ]] || die "invalid server host"
validate_port "$server_port" || die "invalid server port: $server_port"
validate_port "$local_socks_port" || die "invalid local port: $local_socks_port"
validate_port "$remote_socks_port" || die "invalid remote port: $remote_socks_port"

route_mode=direct
if [[ -n $jump_host || -n $jump_user || -n $jump_identity_file || -n $jump_fingerprint ]]; then
    route_mode=jump
    [[ -n $jump_host && -n $jump_user ]] || die "jump mode requires --jump-host and --jump-user"
    [[ $jump_user =~ ^[a-z_][a-z0-9_-]*$ ]] || die "invalid jump user"
    [[ $jump_host != -* && $jump_host != *[[:space:],]* ]] || die "invalid jump host"
    validate_port "$jump_port" || die "invalid jump port: $jump_port"
fi

config_root=${XDG_CONFIG_HOME:-$HOME/.config}
profile_dir=$config_root/ssh-tunnel/client
key_dir=$config_root/ssh-tunnel/keys
known_hosts_dir=$config_root/ssh-tunnel/known_hosts
profile_file=$profile_dir/$profile.env
server_known_hosts_file=$known_hosts_dir/$profile.server
jump_known_hosts_file=$known_hosts_dir/$profile.jump
identity_file=${identity_file:-$key_dir/${profile}_ed25519}
jump_identity_file=${jump_identity_file:-$identity_file}
user_systemd_dir=$config_root/systemd/user
libexec_dir=$HOME/.local/libexec/ssh-tunnel
user_bin_dir=$HOME/.local/bin
unit=ssh-tunnel-client@$profile.service

if (( dry_run )); then
    cat <<EOF
Dry run only; no files will be changed.
  Profile:       $profile
  Route:         $route_mode
  Gateway:       $server_user@$server_host:$server_port
  Jump host:     ${jump_user:+$jump_user@}${jump_host:-none}${jump_host:+:$jump_port}
  Local SOCKS:   $local_socks_address:$local_socks_port
  Remote SOCKS:  $remote_socks_address:$remote_socks_port
  Identity:      $identity_file
  Jump identity: ${jump_identity_file:-none}
  Profile file:  $profile_file
  Service:       $unit
EOF
    exit 0
fi

(( EUID != 0 )) || die "run as the regular desktop user, not root"
[[ $identity_file == /* ]] || die "--identity-file must be absolute"
[[ $jump_identity_file == /* ]] || die "--jump-identity-file must be absolute"
for command_name in ssh ssh-keygen ssh-keyscan ss systemctl; do
    command -v "$command_name" >/dev/null 2>&1 || die "required command is missing: $command_name"
done

local_port_is_listening() {
    ss -H -ltn "sport = :$local_socks_port" 2>/dev/null | grep -q .
}

service_owns_local_port() {
    local main_pid
    main_pid=$(systemctl --user show --property=MainPID --value "$unit" 2>/dev/null || true)
    [[ $main_pid =~ ^[1-9][0-9]*$ ]] || return 1
    ss -H -ltnp "sport = :$local_socks_port" 2>/dev/null |
        grep -Fq "pid=$main_pid,"
}

if (( start_service )) && local_port_is_listening && ! service_owns_local_port; then
    die "local port $local_socks_address:$local_socks_port is already used by another process; choose --local-port"
fi

install -d -m 0700 "$profile_dir" "$key_dir" "$known_hosts_dir"
install -d -m 0755 "$user_systemd_dir" "$libexec_dir" "$user_bin_dir"

ensure_identity() {
    local identity=$1
    local comment=$2
    if [[ ! -f $identity ]]; then
        install -d -m 0700 "$(dirname "$identity")"
        ssh-keygen -q -t ed25519 -N '' -C "$comment" -f "$identity"
    fi
    [[ -f $identity.pub ]] || ssh-keygen -y -f "$identity" >"$identity.pub"
    chmod 0600 "$identity"
    chmod 0644 "$identity.pub"
}

ensure_identity "$identity_file" "ssh-tunnel:$profile@$server_host"
if [[ $jump_identity_file != "$identity_file" ]]; then
    ensure_identity "$jump_identity_file" "ssh-tunnel-jump:$profile@$jump_host"
fi

temporary_directory=$(mktemp -d)
trap 'rm -rf -- "$temporary_directory"' EXIT
server_scan_file=$temporary_directory/server-known-hosts
jump_scan_file=$temporary_directory/jump-known-hosts

cat >"$temporary_directory/profile.env" <<EOF
ROUTE_MODE=$(printf '%q' "$route_mode")
SERVER_HOST=$(printf '%q' "$server_host")
SERVER_PORT=$(printf '%q' "$server_port")
SERVER_USER=$(printf '%q' "$server_user")
LOCAL_SOCKS_ADDRESS=$(printf '%q' "$local_socks_address")
LOCAL_SOCKS_PORT=$(printf '%q' "$local_socks_port")
REMOTE_SOCKS_ADDRESS=$(printf '%q' "$remote_socks_address")
REMOTE_SOCKS_PORT=$(printf '%q' "$remote_socks_port")
IDENTITY_FILE=$(printf '%q' "$identity_file")
SERVER_KNOWN_HOSTS_FILE=$(printf '%q' "$server_known_hosts_file")
JUMP_HOST=$(printf '%q' "$jump_host")
JUMP_PORT=$(printf '%q' "$jump_port")
JUMP_USER=$(printf '%q' "$jump_user")
JUMP_IDENTITY_FILE=$(printf '%q' "$jump_identity_file")
JUMP_KNOWN_HOSTS_FILE=$(printf '%q' "$jump_known_hosts_file")
EOF
install -m 0600 "$temporary_directory/profile.env" "$profile_file"

install -m 0755 "$SCRIPT_DIR/bin/ssh-tunnel-client-run" \
    "$libexec_dir/ssh-tunnel-client-run"
install -m 0755 "$SCRIPT_DIR/bin/ssh-tunnel-exec" "$user_bin_dir/ssh-tunnel-exec"
ln -sfn ssh-tunnel-exec "$user_bin_dir/ssh-tun-exec"
install -m 0644 "$SCRIPT_DIR/systemd/ssh-tunnel-client@.service" \
    "$user_systemd_dir/ssh-tunnel-client@.service"

if (( prepare_only )); then
    printf '\nProfile prepared; install these public keys before completing setup.\n'
    printf 'Gateway key: %s.pub\n' "$identity_file"
    ssh-keygen -l -E sha256 -f "$identity_file.pub"
    if [[ $route_mode == jump ]]; then
        printf 'Jump key:   %s.pub\n' "$jump_identity_file"
        ssh-keygen -l -E sha256 -f "$jump_identity_file.pub"
    fi
    exit 0
fi

trust_scanned_host() {
    local label=$1
    local scan_file=$2
    local expected=$3
    local fingerprints=()
    mapfile -t fingerprints < <(ssh-keygen -l -E sha256 -f "$scan_file" | awk '{print $2}')
    (( ${#fingerprints[@]} > 0 )) || die "$label returned no usable host key"
    if [[ -n $expected ]]; then
        local matched=0
        local fingerprint
        for fingerprint in "${fingerprints[@]}"; do
            [[ $fingerprint == "$expected" ]] && matched=1
        done
        (( matched )) || {
            printf 'Expected %s fingerprint: %s\nReceived:\n' "$label" "$expected" >&2
            printf '  %s\n' "${fingerprints[@]}" >&2
            die "$label host-key fingerprint mismatch"
        }
    else
        printf '%s host-key fingerprint(s):\n' "$label"
        printf '  %s\n' "${fingerprints[@]}"
        [[ -t 0 ]] || die "non-interactive install requires fingerprints for every SSH hop"
        read -r -p "Trust $label? [y/N] " answer
        [[ $answer == y || $answer == Y || $answer == yes || $answer == YES ]] ||
            die "$label host key was not trusted"
    fi
}

if [[ $route_mode == jump ]]; then
    ssh-keyscan -T 10 -H -p "$jump_port" "$jump_host" >"$jump_scan_file" 2>/dev/null ||
        die "could not read jump-host key from $jump_host:$jump_port"
    [[ -s $jump_scan_file ]] || die "jump host returned no key"
    trust_scanned_host "jump host $jump_host:$jump_port" "$jump_scan_file" "$jump_fingerprint"
    install -m 0600 "$jump_scan_file" "$jump_known_hosts_file"

    proxy_command_args=(
        /usr/bin/ssh -F /dev/null -W '%h:%p'
        -i "$jump_identity_file" -p "$jump_port"
        -o BatchMode=yes -o ConnectTimeout=10 -o IdentitiesOnly=yes
        -o PreferredAuthentications=publickey -o PasswordAuthentication=no
        -o KbdInteractiveAuthentication=no -o StrictHostKeyChecking=yes
        -o GlobalKnownHostsFile=/dev/null
        -o UserKnownHostsFile="$jump_known_hosts_file"
        -o ForwardAgent=no "$jump_user@$jump_host"
    )
    printf -v proxy_command '%q ' "${proxy_command_args[@]}"
    proxy_command=${proxy_command% }
    ssh -F /dev/null -T -i "$identity_file" -p "$server_port" \
        -o "ProxyCommand=$proxy_command" \
        -o BatchMode=yes -o ConnectTimeout=10 -o IdentitiesOnly=yes \
        -o PreferredAuthentications=publickey -o PasswordAuthentication=no \
        -o KbdInteractiveAuthentication=no -o StrictHostKeyChecking=accept-new \
        -o GlobalKnownHostsFile=/dev/null -o UserKnownHostsFile="$server_scan_file" \
        -o ForwardAgent=no "$server_user@$server_host" true >/dev/null 2>&1 || true
    [[ -s $server_scan_file ]] ||
        die "could not read gateway host key through $jump_user@$jump_host"
else
    ssh-keyscan -T 10 -H -p "$server_port" "$server_host" >"$server_scan_file" 2>/dev/null ||
        die "could not read gateway host key from $server_host:$server_port"
    [[ -s $server_scan_file ]] || die "gateway returned no host key"
fi

trust_scanned_host "gateway $server_host:$server_port" "$server_scan_file" "$server_fingerprint"
install -m 0600 "$server_scan_file" "$server_known_hosts_file"

systemctl --user daemon-reload
systemctl --user enable "$unit"
if (( enable_linger )); then
    command -v loginctl >/dev/null 2>&1 || die "loginctl is required for --enable-linger"
    command -v sudo >/dev/null 2>&1 || die "sudo is required for --enable-linger"
    sudo loginctl enable-linger "$USER"
fi
if (( start_service )); then
    systemctl --user restart "$unit" ||
        die "service failed to start; verify that both public keys are authorized"
    for _attempt in {1..50}; do
        service_owns_local_port && break
        sleep 0.2
    done
    service_owns_local_port || {
        systemctl --user --no-pager --full status "$unit" >&2 || true
        die "service did not acquire $local_socks_address:$local_socks_port"
    }
    "$user_bin_dir/ssh-tunnel-exec" "$profile" -- true ||
        die "the local SOCKS endpoint failed validation"
fi

printf '\nLinux client installed successfully.\n'
printf 'Route:      %s\n' "$route_mode"
printf 'Service:    %s\n' "$unit"
printf 'Public key: %s.pub\n' "$identity_file"
ssh-keygen -l -E sha256 -f "$identity_file.pub"
printf 'Run:        ssh-tunnel-exec %s -- COMMAND [ARGUMENT...]\n' "$profile"
