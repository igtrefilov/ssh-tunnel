#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
readonly REPOSITORY_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
readonly SOURCE_DIR="$SCRIPT_DIR/third_party/hev-socks5-server"
readonly CONFIG_DIR=/etc/ssh-tunnel
readonly LIBEXEC_DIR=/usr/local/libexec/ssh-tunnel
readonly STATE_DIR=/var/lib/ssh-tunnel
readonly SYSTEMD_DIR=/etc/systemd/system
readonly SOCKS_UNIT=ssh-tunnel-socks.service
readonly SSHD_UNIT=ssh-tunnel-sshd.service

ssh_port=2222
socks_port=1080
tunnel_user=ssh-tun
install_dependencies=1
start_services=1
dry_run=0
public_key_files=()

# shellcheck source=upstream.env
source "$SCRIPT_DIR/upstream.env"

usage() {
    cat <<'EOF'
Usage: sudo ./server/gateway/deploy.sh [OPTIONS]

Options:
  --ssh-port PORT          Dedicated SSH port (default: 2222)
  --socks-port PORT        Loopback SOCKS5 port (default: 1080)
  --tunnel-user USER       Dedicated account (default: ssh-tun)
  --public-key-file FILE   Add a client public key; may be repeated
  --no-install-deps        Do not install missing OS packages
  --no-start               Install and enable services without starting them
  --dry-run                Validate and print the intended installation
  -h, --help               Show this help
EOF
}

die() {
    printf 'gateway deploy: %s\n' "$*" >&2
    exit 1
}

validate_port() {
    [[ $1 =~ ^[0-9]+$ ]] && (( 1 <= 10#$1 && 10#$1 <= 65535 ))
}

while (( $# )); do
    case $1 in
        --ssh-port)
            (( $# >= 2 )) || die "--ssh-port requires a value"
            ssh_port=$2
            shift 2
            ;;
        --socks-port)
            (( $# >= 2 )) || die "--socks-port requires a value"
            socks_port=$2
            shift 2
            ;;
        --tunnel-user)
            (( $# >= 2 )) || die "--tunnel-user requires a value"
            tunnel_user=$2
            shift 2
            ;;
        --public-key-file)
            (( $# >= 2 )) || die "--public-key-file requires a value"
            public_key_files+=("$2")
            shift 2
            ;;
        --no-install-deps)
            install_dependencies=0
            shift
            ;;
        --no-start)
            start_services=0
            shift
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

validate_port "$ssh_port" || die "invalid SSH port: $ssh_port"
validate_port "$socks_port" || die "invalid SOCKS port: $socks_port"
(( ssh_port != socks_port )) || die "SSH and SOCKS ports must differ"
[[ $tunnel_user =~ ^[a-z_][a-z0-9_-]*$ ]] || die "invalid tunnel user"
for public_key_file in "${public_key_files[@]}"; do
    [[ -r $public_key_file ]] || die "cannot read public key: $public_key_file"
done

if (( dry_run )); then
    cat <<EOF
Dry run only; no files will be changed.
  SSH endpoint:  $tunnel_user@SERVER:$ssh_port
  SOCKS backend: 127.0.0.1:$socks_port
  Configuration: $CONFIG_DIR
  Client keys:   ${#public_key_files[@]}
EOF
    exit 0
fi

(( EUID == 0 )) || die "run as root (or use --dry-run)"

install_packages() {
    if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends \
            build-essential ca-certificates git openssh-server python3
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y gcc make git openssh-server python3 ca-certificates
    elif command -v yum >/dev/null 2>&1; then
        yum install -y gcc make git openssh-server python3 ca-certificates
    else
        die "automatic dependency installation supports apt, dnf and yum"
    fi
}

required_commands=(git make sshd ssh-keygen python3)
missing_command=0
for required_command in "${required_commands[@]}"; do
    command -v "$required_command" >/dev/null 2>&1 || missing_command=1
done
if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
    missing_command=1
fi
if (( missing_command )); then
    (( install_dependencies )) || die "Git, make, a C compiler, OpenSSH and Python are required"
    install_packages
fi
command -v cc >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1 ||
    die "a C compiler is required"
command -v systemctl >/dev/null 2>&1 || die "systemd is required"
for public_key_file in "${public_key_files[@]}"; do
    ssh-keygen -l -f "$public_key_file" >/dev/null 2>&1 ||
        die "invalid public key: $public_key_file"
done

sshd_bin=$(command -v sshd)
nologin_bin=$(command -v nologin || true)
[[ -n $nologin_bin ]] || die "nologin executable was not found"

git -c safe.directory="$REPOSITORY_ROOT" -C "$REPOSITORY_ROOT" \
    submodule update --init --recursive server/gateway/third_party/hev-socks5-server
[[ -f $SOURCE_DIR/Makefile ]] || die "hev-socks5-server submodule is not initialized"
actual_commit=$(git -c safe.directory="$SOURCE_DIR" -C "$SOURCE_DIR" rev-parse HEAD)
[[ $actual_commit == "$HEV_SOCKS5_SERVER_COMMIT" ]] ||
    die "unexpected hev-socks5-server commit: $actual_commit"

build_jobs=$(nproc 2>/dev/null || printf '1\n')
make -C "$SOURCE_DIR" clean
make -C "$SOURCE_DIR" -j"$build_jobs" \
    REV_ID="${HEV_SOCKS5_SERVER_COMMIT:0:7}" \
    CFLAGS=-Wno-error=unused-result
server_binary="$SOURCE_DIR/bin/hev-socks5-server"
[[ -x $server_binary ]] || die "backend build did not produce $server_binary"
version_output=$("$server_binary" --version 2>&1 || true)
grep -F "Version: $HEV_SOCKS5_SERVER_VERSION" <<<"$version_output" >/dev/null ||
    die "backend version does not match $HEV_SOCKS5_SERVER_VERSION"

user_created=0
if [[ -r $CONFIG_DIR/install.conf ]]; then
    previous_user=$(sed -n 's/^TUNNEL_USER=//p' "$CONFIG_DIR/install.conf" | tail -1)
    previous_user_created=$(sed -n 's/^USER_CREATED=//p' "$CONFIG_DIR/install.conf" | tail -1)
    [[ -z $previous_user || $previous_user == "$tunnel_user" ]] ||
        die "existing installation uses tunnel user $previous_user"
    [[ $previous_user_created == 1 ]] && user_created=1
fi
if ! id "$tunnel_user" >/dev/null 2>&1; then
    useradd --system --create-home --home-dir "$STATE_DIR" --shell "$nologin_bin" "$tunnel_user"
    user_created=1
fi
tunnel_group=$(id -gn "$tunnel_user")

install -d -o root -g root -m 0755 "$CONFIG_DIR" "$LIBEXEC_DIR"
install -d -o "$tunnel_user" -g "$tunnel_group" -m 0750 "$STATE_DIR"
install -o root -g root -m 0755 "$server_binary" "$LIBEXEC_DIR/hev-socks5-server"

temporary_directory=$(mktemp -d)
trap 'rm -rf -- "$temporary_directory"' EXIT
sed "s|@SOCKS_PORT@|$socks_port|g" "$SCRIPT_DIR/config/socks.yml.template" \
    >"$temporary_directory/socks.yml"
install -o root -g root -m 0644 "$temporary_directory/socks.yml" "$CONFIG_DIR/socks.yml"

host_key_file=$CONFIG_DIR/ssh_host_ed25519_key
if [[ ! -f $host_key_file ]]; then
    ssh-keygen -q -t ed25519 -N '' -C 'ssh-tunnel gateway host key' -f "$host_key_file"
fi
[[ -f $host_key_file.pub ]] || ssh-keygen -y -f "$host_key_file" >"$host_key_file.pub"
chown root:root "$host_key_file" "$host_key_file.pub"
chmod 0600 "$host_key_file"
chmod 0644 "$host_key_file.pub"

authorized_keys_file=$CONFIG_DIR/authorized_keys
touch "$authorized_keys_file"
chown root:"$tunnel_group" "$authorized_keys_file"
chmod 0640 "$authorized_keys_file"

sed \
    -e "s|@SSH_PORT@|$ssh_port|g" \
    -e "s|@SOCKS_PORT@|$socks_port|g" \
    -e "s|@TUNNEL_USER@|$tunnel_user|g" \
    -e "s|@HOST_KEY_FILE@|$host_key_file|g" \
    -e "s|@AUTHORIZED_KEYS_FILE@|$authorized_keys_file|g" \
    -e 's|@PID_FILE@|/run/ssh-tunnel/sshd.pid|g' \
    -e "s|@NOLOGIN_BIN@|$nologin_bin|g" \
    "$SCRIPT_DIR/config/sshd_config.template" >"$temporary_directory/sshd_config"
install -o root -g root -m 0600 "$temporary_directory/sshd_config" "$CONFIG_DIR/sshd_config"

install -o root -g root -m 0644 "$SCRIPT_DIR/systemd/$SOCKS_UNIT" "$SYSTEMD_DIR/$SOCKS_UNIT"
sed "s|@SSHD_BIN@|$sshd_bin|g" "$SCRIPT_DIR/systemd/$SSHD_UNIT.in" \
    >"$temporary_directory/$SSHD_UNIT"
install -o root -g root -m 0644 "$temporary_directory/$SSHD_UNIT" "$SYSTEMD_DIR/$SSHD_UNIT"
install -o root -g root -m 0755 "$SCRIPT_DIR/scripts/add-client-key.sh" \
    /usr/local/sbin/ssh-tunnel-add-client-key
install -o root -g root -m 0755 "$SCRIPT_DIR/scripts/remove-client-key.sh" \
    /usr/local/sbin/ssh-tunnel-remove-client-key

cat >"$temporary_directory/install.conf" <<EOF
TUNNEL_USER=$tunnel_user
USER_CREATED=$user_created
AUTHORIZED_KEYS_FILE=$authorized_keys_file
SSH_PORT=$ssh_port
SOCKS_PORT=$socks_port
EOF
install -o root -g root -m 0600 "$temporary_directory/install.conf" "$CONFIG_DIR/install.conf"

"$sshd_bin" -t -f "$CONFIG_DIR/sshd_config"
for public_key_file in "${public_key_files[@]}"; do
    /usr/local/sbin/ssh-tunnel-add-client-key "$public_key_file"
done

systemctl daemon-reload
systemctl enable "$SOCKS_UNIT" "$SSHD_UNIT"
if (( start_services )); then
    systemctl restart "$SOCKS_UNIT"
    systemctl restart "$SSHD_UNIT"
    "$SCRIPT_DIR/verify.sh" --ssh-port "$ssh_port" --socks-port "$socks_port"
fi

printf '\nGateway installation completed.\n'
printf 'SSH endpoint:  %s@SERVER:%s\n' "$tunnel_user" "$ssh_port"
printf 'SOCKS backend: 127.0.0.1:%s\n' "$socks_port"
printf 'Host key:      '
ssh-keygen -l -E sha256 -f "$host_key_file.pub"
if (( ${#public_key_files[@]} == 0 )); then
    printf 'Add a client:  sudo ssh-tunnel-add-client-key --label DEVICE /path/to/key.pub\n'
fi
