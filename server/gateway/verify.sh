#!/usr/bin/env bash
set -Eeuo pipefail

host=127.0.0.1
ssh_port=2222
socks_port=1080
check_systemd=1

usage() {
    cat <<'EOF' >&2
Usage: verify.sh [--host HOST] [--ssh-port PORT] [--socks-port PORT] [--no-systemd]
EOF
    exit 2
}

validate_port() {
    [[ $1 =~ ^[0-9]+$ ]] && (( 1 <= 10#$1 && 10#$1 <= 65535 ))
}

while (( $# )); do
    case $1 in
        --host)
            (( $# >= 2 )) || usage
            host=$2
            shift 2
            ;;
        --ssh-port)
            (( $# >= 2 )) || usage
            ssh_port=$2
            shift 2
            ;;
        --socks-port)
            (( $# >= 2 )) || usage
            socks_port=$2
            shift 2
            ;;
        --no-systemd)
            check_systemd=0
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            usage
            ;;
    esac
done

validate_port "$ssh_port" || usage
validate_port "$socks_port" || usage

if (( check_systemd )); then
    sshd_bin=$(command -v sshd) || {
        printf 'verify: sshd is required\n' >&2
        exit 1
    }
    systemctl is-enabled --quiet ssh-tunnel-socks.service
    systemctl is-enabled --quiet ssh-tunnel-sshd.service
    systemctl is-active --quiet ssh-tunnel-socks.service
    systemctl is-active --quiet ssh-tunnel-sshd.service
    "$sshd_bin" -t -f /etc/ssh-tunnel/sshd_config
fi

python3 - "$host" "$ssh_port" "$socks_port" <<'PY'
import ipaddress
import socket
import struct
import sys
import threading
import time

host = sys.argv[1]
ssh_port = int(sys.argv[2])
socks_port = int(sys.argv[3])


def recv_exact(connection: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining:
        chunk = connection.recv(remaining)
        if not chunk:
            raise RuntimeError("unexpected end of stream")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_socks_reply(connection: socket.socket) -> int:
    version, reply, reserved, address_type = recv_exact(connection, 4)
    if version != 5 or reserved != 0:
        raise RuntimeError("malformed SOCKS5 reply")
    if address_type == 1:
        recv_exact(connection, 4)
    elif address_type == 4:
        recv_exact(connection, 16)
    elif address_type == 3:
        recv_exact(connection, recv_exact(connection, 1)[0])
    else:
        raise RuntimeError(f"unsupported SOCKS5 reply address type {address_type}")
    recv_exact(connection, 2)
    return reply


def open_socks() -> socket.socket:
    last_error = None
    for _ in range(30):
        try:
            connection = socket.create_connection((host, socks_port), timeout=2)
            connection.sendall(b"\x05\x01\x00")
            if recv_exact(connection, 2) != b"\x05\x00":
                raise RuntimeError("SOCKS5 no-auth negotiation failed")
            return connection
        except (OSError, RuntimeError) as error:
            last_error = error
            time.sleep(0.1)
    raise RuntimeError(f"SOCKS5 endpoint is unavailable: {last_error}")


with socket.create_connection((host, ssh_port), timeout=3) as ssh:
    banner = ssh.recv(255)
    if not banner.startswith(b"SSH-"):
        raise RuntimeError(f"unexpected SSH banner: {banner!r}")

echo = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
echo.bind(("127.0.0.1", 0))
echo.listen(1)
echo_port = echo.getsockname()[1]
echo_error = []


def echo_once() -> None:
    try:
        connection, _ = echo.accept()
        with connection:
            payload = connection.recv(4096)
            connection.sendall(payload)
    except BaseException as error:  # surfaced in the main thread below
        echo_error.append(error)


echo_thread = threading.Thread(target=echo_once, daemon=True)
echo_thread.start()
payload = b"ssh-tunnel-gateway-connect-check"
with open_socks() as socks:
    socks.sendall(
        b"\x05\x01\x00\x01"
        + ipaddress.IPv4Address("127.0.0.1").packed
        + struct.pack("!H", echo_port)
    )
    reply = read_socks_reply(socks)
    if reply != 0:
        raise RuntimeError(f"SOCKS5 CONNECT failed with reply {reply}")
    socks.sendall(payload)
    if recv_exact(socks, len(payload)) != payload:
        raise RuntimeError("SOCKS5 TCP relay corrupted data")
echo.close()
echo_thread.join(timeout=2)
if echo_error:
    raise echo_error[0]

# HEV's UDP-in-TCP extension uses command 0x05. Android depends on it because
# SSH local forwarding cannot transport a standard SOCKS5 UDP relay socket.
with open_socks() as socks:
    socks.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
    reply = read_socks_reply(socks)
    if reply != 0:
        raise RuntimeError(f"SOCKS5 UDP-in-TCP failed with reply {reply}")

print(f"Gateway checks passed: SSH {host}:{ssh_port}, SOCKS5 {host}:{socks_port}")
PY
