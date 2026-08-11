#!/usr/bin/env bash
set -Eeuo pipefail

readonly INSTALL_CONFIG=/etc/ssh-tunnel/install.conf

die() {
    printf 'ssh-tunnel-remove-client-key: %s\n' "$*" >&2
    exit 1
}

if [[ ${1:-} == -h || ${1:-} == --help ]]; then
    printf 'Usage: sudo ssh-tunnel-remove-client-key SHA256:FINGERPRINT\n'
    exit 0
fi

(( EUID == 0 )) || die "run as root"
(( $# == 1 )) || die "expected one SHA256 fingerprint"
target=$1
[[ $target == SHA256:* ]] || die "fingerprint must start with SHA256:"
[[ -r $INSTALL_CONFIG ]] || die "$INSTALL_CONFIG is missing"

# shellcheck disable=SC1090
source "$INSTALL_CONFIG"
: "${AUTHORIZED_KEYS_FILE:?missing AUTHORIZED_KEYS_FILE in $INSTALL_CONFIG}"
[[ -f $AUTHORIZED_KEYS_FILE ]] || die "$AUTHORIZED_KEYS_FILE does not exist"

line_file=$(mktemp)
output_file=$(mktemp "$(dirname "$AUTHORIZED_KEYS_FILE")/authorized_keys.tmp.XXXXXX")
trap 'rm -f -- "$line_file" "$output_file"' EXIT
removed=0

while IFS= read -r line || [[ -n $line ]]; do
    [[ -z $line || $line == \#* ]] && { printf '%s\n' "$line" >>"$output_file"; continue; }
    printf '%s\n' "$line" >"$line_file"
    fingerprint=$(ssh-keygen -l -E sha256 -f "$line_file" 2>/dev/null | awk '{print $2}') || true
    if [[ $fingerprint == "$target" ]]; then
        ((removed += 1))
    else
        printf '%s\n' "$line" >>"$output_file"
    fi
done <"$AUTHORIZED_KEYS_FILE"

(( removed > 0 )) || die "fingerprint was not found"
install -o root -g "$(stat -c %G "$AUTHORIZED_KEYS_FILE")" -m 0640 \
    "$output_file" "$AUTHORIZED_KEYS_FILE"
printf 'Removed keys: %d\n' "$removed"
