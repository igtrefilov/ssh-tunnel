#!/usr/bin/env python3
"""End-to-end checks for the common SOCKS5 engine.

The test covers standard TCP CONNECT used by Linux and HEV UDP-in-TCP command
0x05 used by Android. It starts an unprivileged backend process on loopback and
does not modify system services.
"""

from __future__ import annotations

import argparse
import ipaddress
import socket
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def recv_exact(connection: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    while size:
        chunk = connection.recv(size)
        if not chunk:
            raise RuntimeError("unexpected end of stream")
        chunks.append(chunk)
        size -= len(chunk)
    return b"".join(chunks)


def read_address(connection: socket.socket, address_type: int) -> tuple[str, int]:
    if address_type == 1:
        host = str(ipaddress.IPv4Address(recv_exact(connection, 4)))
    elif address_type == 4:
        host = str(ipaddress.IPv6Address(recv_exact(connection, 16)))
    elif address_type == 3:
        host = recv_exact(connection, recv_exact(connection, 1)[0]).decode("idna")
    else:
        raise RuntimeError(f"unsupported address type {address_type}")
    return host, struct.unpack("!H", recv_exact(connection, 2))[0]


def negotiate(host: str, port: int) -> socket.socket:
    connection = socket.create_connection((host, port), timeout=3)
    connection.settimeout(3)
    connection.sendall(b"\x05\x01\x00")
    if recv_exact(connection, 2) != b"\x05\x00":
        connection.close()
        raise RuntimeError("SOCKS5 negotiation failed")
    return connection


def read_reply(connection: socket.socket) -> int:
    version, reply, reserved, address_type = recv_exact(connection, 4)
    if version != 5 or reserved != 0:
        raise RuntimeError("malformed SOCKS5 response")
    read_address(connection, address_type)
    return reply


def free_tcp_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_until_ready(port: int, process: subprocess.Popen[bytes]) -> None:
    last_error: Exception | None = None
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError(f"backend exited with status {process.returncode}")
        try:
            with negotiate("127.0.0.1", port):
                return
        except (OSError, RuntimeError) as error:
            last_error = error
            time.sleep(0.1)
    raise RuntimeError(f"backend did not become ready: {last_error}")


def test_tcp_connect(socks_port: int) -> None:
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    echo_port = int(listener.getsockname()[1])
    errors: list[BaseException] = []

    def echo_once() -> None:
        try:
            connection, _ = listener.accept()
            with connection:
                data = connection.recv(65536)
                connection.sendall(data)
        except BaseException as error:
            errors.append(error)

    thread = threading.Thread(target=echo_once, daemon=True)
    thread.start()
    payload = b"linux-standard-socks-connect"
    with negotiate("127.0.0.1", socks_port) as socks:
        socks.sendall(
            b"\x05\x01\x00\x01"
            + ipaddress.IPv4Address("127.0.0.1").packed
            + struct.pack("!H", echo_port)
        )
        if read_reply(socks) != 0:
            raise RuntimeError("SOCKS5 CONNECT was rejected")
        socks.sendall(payload)
        if recv_exact(socks, len(payload)) != payload:
            raise RuntimeError("SOCKS5 TCP relay changed the payload")
    listener.close()
    thread.join(timeout=2)
    if errors:
        raise errors[0]


def test_udp_in_tcp(socks_port: int) -> None:
    echo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    echo.bind(("127.0.0.1", 0))
    echo_port = int(echo.getsockname()[1])
    errors: list[BaseException] = []

    def echo_once() -> None:
        try:
            data, address = echo.recvfrom(65535)
            echo.sendto(data, address)
        except BaseException as error:
            errors.append(error)

    thread = threading.Thread(target=echo_once, daemon=True)
    thread.start()
    payload = b"android-hev-udp-in-tcp"
    destination = (
        b"\x01"
        + ipaddress.IPv4Address("127.0.0.1").packed
        + struct.pack("!H", echo_port)
    )
    with negotiate("127.0.0.1", socks_port) as socks:
        socks.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
        if read_reply(socks) != 0:
            raise RuntimeError("HEV UDP-in-TCP command was rejected")

        header_length = 3 + len(destination)
        socks.sendall(struct.pack("!HB", len(payload), header_length) + destination + payload)
        data_length, response_header_length = struct.unpack("!HB", recv_exact(socks, 3))
        if response_header_length < 4:
            raise RuntimeError("invalid UDP-in-TCP frame header")
        address_type = recv_exact(socks, 1)[0]
        source_host, source_port = read_address(socks, address_type)
        response = recv_exact(socks, data_length)
        if response != payload:
            raise RuntimeError("UDP-in-TCP relay changed the payload")
        if source_host != "127.0.0.1" or source_port != echo_port:
            raise RuntimeError("UDP-in-TCP response has the wrong source")
    echo.close()
    thread.join(timeout=2)
    if errors:
        raise errors[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("backend", type=Path)
    args = parser.parse_args()
    if not args.backend.is_file():
        parser.error(f"backend binary does not exist: {args.backend}")

    socks_port = free_tcp_port()
    with tempfile.TemporaryDirectory(prefix="ssh-tunnel-test-") as directory:
        config = Path(directory) / "socks.yml"
        config.write_text(
            "main:\n"
            "  workers: 2\n"
            f"  port: {socks_port}\n"
            "  listen-address: '127.0.0.1'\n"
            "  listen-ipv6-only: false\n"
            "  domain-address-type: unspec\n"
            "misc:\n"
            "  log-level: warn\n"
            "  limit-nofile: 4096\n",
            encoding="utf-8",
        )
        process = subprocess.Popen(
            [str(args.backend), str(config)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            wait_until_ready(socks_port, process)
            test_tcp_connect(socks_port)
            test_udp_in_tcp(socks_port)
        finally:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        if process.returncode not in (0, -15):
            stderr = process.stderr.read().decode("utf-8", "replace")
            raise RuntimeError(f"backend exited with {process.returncode}: {stderr}")
    print("SOCKS5 TCP CONNECT and HEV UDP-in-TCP checks passed")


if __name__ == "__main__":
    main()
