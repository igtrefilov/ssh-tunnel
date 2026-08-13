#!/usr/bin/env python3
"""Test the Linux HTTP CONNECT to SOCKS5 compatibility bridge."""

from __future__ import annotations

import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path


BRIDGE = Path(__file__).parents[2] / "clients/linux/bin/ssh-tunnel-http-bridge"


def recv_exact(connection: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    while size:
        chunk = connection.recv(size)
        if not chunk:
            raise RuntimeError("unexpected end of stream")
        chunks.append(chunk)
        size -= len(chunk)
    return b"".join(chunks)


def relay(left: socket.socket, right: socket.socket) -> None:
    errors: list[BaseException] = []

    def copy(source: socket.socket, destination: socket.socket) -> None:
        try:
            while True:
                data = source.recv(65536)
                if not data:
                    break
                destination.sendall(data)
        except BaseException as error:
            errors.append(error)
        finally:
            try:
                destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    threads = [
        threading.Thread(target=copy, args=(left, right), daemon=True),
        threading.Thread(target=copy, args=(right, left), daemon=True),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=3)
    if errors:
        raise errors[0]


class SocksHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        connection = self.request
        connection.settimeout(3)
        if recv_exact(connection, 2) != b"\x05\x01":
            raise RuntimeError("bridge sent an invalid SOCKS5 greeting")
        if recv_exact(connection, 1) != b"\x00":
            raise RuntimeError("bridge selected an unexpected SOCKS5 method")
        connection.sendall(b"\x05\x00")

        version, command, reserved, address_type = recv_exact(connection, 4)
        if (version, command, reserved, address_type) != (5, 1, 0, 3):
            raise RuntimeError("bridge did not use remote-DNS SOCKS5 CONNECT")
        host_length = recv_exact(connection, 1)[0]
        host = recv_exact(connection, host_length).decode("idna")
        port = struct.unpack("!H", recv_exact(connection, 2))[0]
        target = self.server.targets.get((host, port))  # type: ignore[attr-defined]
        if target is None:
            connection.sendall(b"\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00")
            return

        upstream = socket.create_connection(target, timeout=3)
        try:
            connection.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00")
            relay(connection, upstream)
        finally:
            upstream.close()


class SocksServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, targets: dict[tuple[str, int], tuple[str, int]]) -> None:
        self.targets = targets
        super().__init__(("127.0.0.1", 0), SocksHandler)


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.sendall(self.request.recv(65536))


class HttpHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = self.request.recv(65536)
        if not request.startswith(b"GET /bridge-test?value=1 HTTP/1.1\r\n"):
            raise RuntimeError(f"bridge did not rewrite HTTP target: {request!r}")
        self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")


def start_server(server: socketserver.TCPServer) -> threading.Thread:
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return thread


def wait_for_port(port: int, process: subprocess.Popen[bytes]) -> None:
    last_error: OSError | None = None
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError(f"bridge exited with status {process.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError as error:
            last_error = error
            time.sleep(0.05)
    raise RuntimeError(f"bridge did not listen: {last_error}")


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def test_bridge() -> None:
    echo_server = socketserver.TCPServer(("127.0.0.1", 0), EchoHandler)
    http_server = socketserver.TCPServer(("127.0.0.1", 0), HttpHandler)
    socks_server = SocksServer(
        {
            ("echo.test", echo_server.server_address[1]): echo_server.server_address,
            ("http.test", http_server.server_address[1]): http_server.server_address,
        }
    )
    servers = (echo_server, http_server, socks_server)
    for server in servers:
        start_server(server)

    bridge_port = free_port()
    process = subprocess.Popen(
        [
            sys.executable,
            str(BRIDGE),
            str(bridge_port),
            "127.0.0.1",
            str(socks_server.server_address[1]),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        wait_for_port(bridge_port, process)

        with socket.create_connection(("127.0.0.1", bridge_port), timeout=3) as connection:
            echo_port = echo_server.server_address[1]
            connection.sendall(
                f"CONNECT echo.test:{echo_port} HTTP/1.1\r\nHost: echo.test\r\n\r\n".encode()
            )
            if not connection.recv(1024).startswith(b"HTTP/1.1 200"):
                raise RuntimeError("HTTP CONNECT was rejected")
            payload = b"bridge-connect-payload"
            connection.sendall(payload)
            if recv_exact(connection, len(payload)) != payload:
                raise RuntimeError("HTTP CONNECT relay changed the payload")

        with socket.create_connection(("127.0.0.1", bridge_port), timeout=3) as connection:
            http_port = http_server.server_address[1]
            request = (
                f"GET http://http.test:{http_port}/bridge-test?value=1 HTTP/1.1\r\n"
                "Host: http.test\r\nConnection: close\r\n\r\n"
            ).encode()
            connection.sendall(request)
            response = connection.recv(1024)
            if not response.startswith(b"HTTP/1.1 200 OK"):
                raise RuntimeError(f"absolute-form HTTP request failed: {response!r}")
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        for server in servers:
            server.shutdown()
            server.server_close()

    if process.returncode not in (0, -15):
        stderr = process.stderr.read().decode("utf-8", "replace")
        raise RuntimeError(f"bridge exited with {process.returncode}: {stderr}")


if __name__ == "__main__":
    test_bridge()
    print("HTTP CONNECT to SOCKS5 bridge checks passed")
