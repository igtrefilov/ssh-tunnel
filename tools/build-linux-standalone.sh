#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPOSITORY_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
readonly CLIENT_DIR="$REPOSITORY_ROOT/clients/linux"

die() {
    printf 'build-linux-standalone: %s\n' "$*" >&2
    exit 1
}

for command_name in tar gzip base64; do
    command -v "$command_name" >/dev/null 2>&1 || die "$command_name is required"
done

work_directory=$(mktemp -d)
trap 'rm -rf -- "$work_directory"' EXIT
payload_directory=$work_directory/payload
archive=$work_directory/linux-client.tar.gz
output=$CLIENT_DIR/install-standalone.sh

install -d "$payload_directory/bin" "$payload_directory/systemd"
install -m 0755 "$CLIENT_DIR/install.sh" "$payload_directory/install.sh"
install -m 0755 "$CLIENT_DIR/bin/ssh-tunnel-client-run" \
    "$payload_directory/bin/ssh-tunnel-client-run"
install -m 0755 "$CLIENT_DIR/bin/ssh-tunnel-exec" \
    "$payload_directory/bin/ssh-tunnel-exec"
install -m 0644 "$CLIENT_DIR/systemd/ssh-tunnel-client@.service" \
    "$payload_directory/systemd/ssh-tunnel-client@.service"

tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
    -czf "$archive" -C "$payload_directory" .

wrapper=$work_directory/wrapper
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    '' \
    "readonly PAYLOAD_MARKER='__SSH_TUNNEL_PAYLOAD__'" \
    '' \
    'die() {' \
    "    printf 'ssh-tunnel Linux installer: %s\\n' \"\$*\" >&2" \
    '    exit 1' \
    '}' \
    '' \
    'payload_line=$(awk -v marker="$PAYLOAD_MARKER" '\''$0 == marker { print NR + 1; exit }'\'' "$0")' \
    '[[ -n "$payload_line" ]] || die "embedded payload marker is missing"' \
    'work_directory=$(mktemp -d)' \
    'trap '\''rm -rf -- "$work_directory"'\'' EXIT' \
    'tail -n +"$payload_line" "$0" | base64 -d | tar -xzf - -C "$work_directory" ||' \
    '    die "could not unpack installer payload"' \
    'exec "$work_directory/install.sh" "$@"' \
    '' \
    '__SSH_TUNNEL_PAYLOAD__' >"$wrapper"

install -m 0755 "$wrapper" "$output"
base64 -w 76 "$archive" >>"$output"
printf 'Built %s (%s bytes)\n' "$output" "$(wc -c <"$output")"
