#!/usr/bin/env bash
set -Eeuo pipefail

ssh_user=
gateway_host=
gateway_port=2222
public_key_file=
label=ssh-tunnel-jump
dry_run=0

usage() {
    cat <<'EOF'
Usage: sudo ./server/jump-host/deploy.sh [OPTIONS]

Options:
  --ssh-user USER           Existing account used on the jump host (required)
  --gateway-host HOST       Gateway address as seen by the jump host (required)
  --gateway-port PORT       Gateway SSH port (default: 2222)
  --public-key-file FILE    Client public key allowed to use this route (required)
  --label LABEL             authorized_keys comment (default: ssh-tunnel-jump)
  --dry-run                 Validate and print the authorized_keys policy
  -h, --help                Show this help

The installed key may only open a TCP forwarding channel to the configured
gateway SSH endpoint. It cannot run commands or open a shell on the jump host.
EOF
}

die() {
    printf 'jump-host deploy: %s\n' "$*" >&2
    exit 1
}

while (( $# )); do
    case $1 in
        --ssh-user)
            (( $# >= 2 )) || die "--ssh-user requires a value"
            ssh_user=$2
            shift 2
            ;;
        --gateway-host)
            (( $# >= 2 )) || die "--gateway-host requires a value"
            gateway_host=$2
            shift 2
            ;;
        --gateway-port)
            (( $# >= 2 )) || die "--gateway-port requires a value"
            gateway_port=$2
            shift 2
            ;;
        --public-key-file)
            (( $# >= 2 )) || die "--public-key-file requires a value"
            public_key_file=$2
            shift 2
            ;;
        --label)
            (( $# >= 2 )) || die "--label requires a value"
            label=$2
            shift 2
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

[[ $ssh_user =~ ^[a-z_][a-z0-9_-]*[$]?$ ]] || die "invalid or missing --ssh-user"
[[ -n $gateway_host && $gateway_host != -* && $gateway_host != *[[:space:],]* ]] ||
    die "invalid or missing --gateway-host"
[[ $gateway_port =~ ^[0-9]+$ ]] && (( 1 <= 10#$gateway_port && 10#$gateway_port <= 65535 )) ||
    die "invalid gateway port"
[[ $label =~ ^[A-Za-z0-9][A-Za-z0-9_.@-]*$ ]] || die "invalid label"
[[ -r $public_key_file ]] || die "cannot read --public-key-file"
ssh-keygen -l -f "$public_key_file" >/dev/null 2>&1 || die "invalid public key"

read -r key_type key_data _ <"$public_key_file"
[[ -n ${key_type:-} && -n ${key_data:-} ]] || die "public key is empty"
restriction="restrict,port-forwarding,permitopen=\"$gateway_host:$gateway_port\",command=\"/bin/false\""
restricted_line="$restriction $key_type $key_data $label"

if (( dry_run )); then
    printf 'Jump user:       %s\n' "$ssh_user"
    printf 'Allowed target:  %s:%s\n' "$gateway_host" "$gateway_port"
    printf 'Key fingerprint: '
    ssh-keygen -l -E sha256 -f "$public_key_file"
    printf 'Policy:          forwarding only, no shell or commands\n'
    exit 0
fi

(( EUID == 0 )) || die "run as root (or use --dry-run)"
id "$ssh_user" >/dev/null 2>&1 || die "SSH account does not exist: $ssh_user"
ssh_group=$(id -gn "$ssh_user")
ssh_home=$(getent passwd "$ssh_user" | cut -d: -f6)
[[ -n $ssh_home && $ssh_home != / ]] || die "unsafe home directory for $ssh_user"

install -d -m 0700 -o "$ssh_user" -g "$ssh_group" "$ssh_home/.ssh"
authorized_keys="$ssh_home/.ssh/authorized_keys"
if [[ -L $authorized_keys || ( -e $authorized_keys && ! -f $authorized_keys ) ]]; then
    die "unsafe authorized_keys path: $authorized_keys"
fi
if [[ ! -e $authorized_keys ]]; then
    install -m 0600 -o "$ssh_user" -g "$ssh_group" /dev/null "$authorized_keys"
fi

temporary_file=$(mktemp "$ssh_home/.ssh/authorized_keys.tmp.XXXXXX")
trap 'rm -f -- "$temporary_file"' EXIT
awk -v key_data="$key_data" -v replacement="$restricted_line" '
    {
        matches = 0
        for (field = 1; field <= NF; field++) {
            if ($field == key_data) {
                matches = 1
                break
            }
        }
        if (matches) {
            if (!written) {
                print replacement
                written = 1
            }
            next
        }
        print
    }
    END {
        if (!written) print replacement
    }
' "$authorized_keys" >"$temporary_file"
install -m 0600 -o "$ssh_user" -g "$ssh_group" "$temporary_file" "$authorized_keys"

sshd_bin=$(command -v sshd || true)
[[ -n $sshd_bin ]] || die "OpenSSH server is not installed"
"$sshd_bin" -t
forwarding_mode=$("$sshd_bin" -T -C "user=$ssh_user,host=localhost,addr=127.0.0.1" |
    awk '$1 == "allowtcpforwarding" { print $2; exit }')
case $forwarding_mode in
    yes|all|local)
        ;;
    *)
        die "OpenSSH does not allow local forwarding for $ssh_user"
        ;;
esac

printf 'Jump-host route installed for %s -> %s:%s\n' "$ssh_user" "$gateway_host" "$gateway_port"
ssh-keygen -l -E sha256 -f "$public_key_file"
