#!/usr/bin/env python3
"""Exercise direct and jump-host SSH routes against the common gateway.

The test uses only unprivileged loopback ports and temporary keys/configuration.
It sends both standard SOCKS5 TCP and HEV UDP-in-TCP traffic through each SSH
local forward.
"""

from __future__ import annotations

import argparse
import contextlib
import os
import pwd
import shlex
import shutil
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Iterator

import test_socks_gateway as socks_contract


def run(*arguments: str) -> None:
    subprocess.run(arguments, check=True, stdout=subprocess.DEVNULL)


def public_key_fields(path: Path) -> tuple[str, str]:
    fields = path.read_text(encoding="utf-8").split()
    if len(fields) < 2:
        raise RuntimeError(f"invalid public key: {path}")
    return fields[0], fields[1]


def known_hosts_line(port: int, host_key: Path) -> str:
    key_type, key_data = public_key_fields(host_key)
    return f"[127.0.0.1]:{port} {key_type} {key_data}\n"


def wait_for_port(port: int, process: subprocess.Popen[bytes], label: str) -> None:
    last_error: Exception | None = None
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError(f"{label} exited with status {process.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError as error:
            last_error = error
            time.sleep(0.1)
    raise RuntimeError(f"{label} did not listen on port {port}: {last_error}")


@contextlib.contextmanager
def temporary_sshd(
        directory: Path,
        name: str,
        port: int,
        authorized_keys: Path,
        permit_open: str) -> Iterator[Path]:
    sshd = shutil.which("sshd")
    nologin = shutil.which("nologin")
    if sshd is None or nologin is None:
        raise RuntimeError("OpenSSH server and nologin are required")

    host_key = directory / f"{name}-host-key"
    config = directory / f"{name}-sshd_config"
    log = directory / f"{name}-sshd.log"
    run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(host_key))
    user = pwd.getpwuid(os.getuid()).pw_name
    config.write_text(
        f"""Port {port}
AddressFamily inet
ListenAddress 127.0.0.1
HostKey {host_key}
PidFile {directory / f'{name}.pid'}
AuthorizedKeysFile {authorized_keys}
StrictModes no
PubkeyAuthentication yes
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PermitRootLogin prohibit-password
AllowUsers {user}
AllowTcpForwarding local
GatewayPorts no
PermitOpen {permit_open}
AllowStreamLocalForwarding no
PermitTunnel no
AllowAgentForwarding no
X11Forwarding no
PermitTTY no
PermitUserRC no
PermitUserEnvironment no
ForceCommand {nologin}
MaxSessions 1
UseDNS no
LogLevel VERBOSE
""",
        encoding="utf-8",
    )
    run(sshd, "-t", "-f", str(config))
    process = subprocess.Popen(
        [sshd, "-D", "-f", str(config), "-E", str(log)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        wait_for_port(port, process, name)
        yield host_key.with_suffix(".pub")
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        if process.returncode not in (0, -15):
            details = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
            raise RuntimeError(f"{name} exited with {process.returncode}: {details}")


@contextlib.contextmanager
def ssh_tunnel(command: list[str], local_port: int, label: str) -> Iterator[None]:
    process = subprocess.Popen(
        command,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    try:
        last_error: Exception | None = None
        for _ in range(80):
            if process.poll() is not None:
                details = process.stderr.read().decode("utf-8", "replace")
                raise RuntimeError(f"{label} exited with {process.returncode}: {details}")
            try:
                with socks_contract.negotiate("127.0.0.1", local_port):
                    break
            except (OSError, RuntimeError) as error:
                last_error = error
                time.sleep(0.1)
        else:
            raise RuntimeError(f"{label} did not expose SOCKS5: {last_error}")
        yield
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def client_command(
        identity: Path,
        gateway_port: int,
        gateway_known_hosts: Path,
        local_port: int,
        socks_port: int,
        proxy_command: str | None = None) -> list[str]:
    user = pwd.getpwuid(os.getuid()).pw_name
    command = [
        "ssh", "-F", "/dev/null", "-N", "-T",
        "-i", str(identity), "-p", str(gateway_port),
        "-o", "BatchMode=yes",
        "-o", "IdentitiesOnly=yes",
        "-o", "PreferredAuthentications=publickey",
        "-o", "PasswordAuthentication=no",
        "-o", "KbdInteractiveAuthentication=no",
        "-o", "ExitOnForwardFailure=yes",
        "-o", "StrictHostKeyChecking=yes",
        "-o", "GlobalKnownHostsFile=/dev/null",
        "-o", f"UserKnownHostsFile={gateway_known_hosts}",
        "-o", "ForwardAgent=no",
        "-o", "LogLevel=ERROR",
    ]
    if proxy_command is not None:
        command.extend(["-o", f"ProxyCommand={proxy_command}"])
    command.extend([
        "-L", f"127.0.0.1:{local_port}:127.0.0.1:{socks_port}",
        f"{user}@127.0.0.1",
    ])
    return command


def exercise_route(command: list[str], local_port: int, label: str) -> None:
    with ssh_tunnel(command, local_port, label):
        socks_contract.test_tcp_connect(local_port)
        socks_contract.test_udp_in_tcp(local_port)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("backend", type=Path)
    args = parser.parse_args()
    backend = args.backend.resolve()
    if not backend.is_file():
        parser.error(f"backend binary does not exist: {backend}")

    with tempfile.TemporaryDirectory(prefix="ssh-tunnel-routes-") as temporary:
        directory = Path(temporary)
        os.chmod(directory, 0o700)
        identity = directory / "client-key"
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(identity))
        key_type, key_data = public_key_fields(identity.with_suffix(".pub"))
        gateway_authorized = directory / "gateway-authorized_keys"
        gateway_authorized.write_text(f"{key_type} {key_data} route-test\n", encoding="utf-8")
        os.chmod(gateway_authorized, 0o600)

        socks_port = socks_contract.free_tcp_port()
        backend_config = directory / "socks.yml"
        backend_config.write_text(
            "main:\n"
            "  workers: 2\n"
            f"  port: {socks_port}\n"
            "  listen-address: '127.0.0.1'\n"
            "misc:\n"
            "  log-level: warn\n"
            "  limit-nofile: 4096\n",
            encoding="utf-8",
        )
        backend_process = subprocess.Popen(
            [str(backend), str(backend_config)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        try:
            socks_contract.wait_until_ready(socks_port, backend_process)
            gateway_port = socks_contract.free_tcp_port()
            with temporary_sshd(
                    directory,
                    "gateway",
                    gateway_port,
                    gateway_authorized,
                    f"127.0.0.1:{socks_port}") as gateway_host_key:
                gateway_known_hosts = directory / "gateway-known_hosts"
                gateway_known_hosts.write_text(
                    known_hosts_line(gateway_port, gateway_host_key),
                    encoding="utf-8",
                )

                direct_port = socks_contract.free_tcp_port()
                exercise_route(
                    client_command(
                        identity,
                        gateway_port,
                        gateway_known_hosts,
                        direct_port,
                        socks_port,
                    ),
                    direct_port,
                    "direct SSH route",
                )

                jump_port = socks_contract.free_tcp_port()
                jump_authorized = directory / "jump-authorized_keys"
                jump_authorized.write_text(
                    "restrict,port-forwarding,"
                    f'permitopen="127.0.0.1:{gateway_port}",'
                    f'command="/bin/false" {key_type} {key_data} route-test\n',
                    encoding="utf-8",
                )
                os.chmod(jump_authorized, 0o600)
                with temporary_sshd(
                        directory,
                        "jump",
                        jump_port,
                        jump_authorized,
                        f"127.0.0.1:{gateway_port}") as jump_host_key:
                    jump_known_hosts = directory / "jump-known-hosts"
                    jump_known_hosts.write_text(
                        known_hosts_line(jump_port, jump_host_key),
                        encoding="utf-8",
                    )
                    user = pwd.getpwuid(os.getuid()).pw_name
                    proxy_command = shlex.join([
                        "ssh", "-F", "/dev/null", "-W", "%h:%p",
                        "-i", str(identity), "-p", str(jump_port),
                        "-o", "BatchMode=yes",
                        "-o", "IdentitiesOnly=yes",
                        "-o", "StrictHostKeyChecking=yes",
                        "-o", "GlobalKnownHostsFile=/dev/null",
                        "-o", f"UserKnownHostsFile={jump_known_hosts}",
                        "-o", "ForwardAgent=no",
                        "-o", "LogLevel=ERROR",
                        f"{user}@127.0.0.1",
                    ])
                    jump_local_port = socks_contract.free_tcp_port()
                    exercise_route(
                        client_command(
                            identity,
                            gateway_port,
                            gateway_known_hosts,
                            jump_local_port,
                            socks_port,
                            proxy_command,
                        ),
                        jump_local_port,
                        "jump-host SSH route",
                    )
        finally:
            backend_process.terminate()
            try:
                backend_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                backend_process.kill()
                backend_process.wait(timeout=3)

    print("Direct and restricted jump-host SSH routes passed TCP and UDP-in-TCP checks")


if __name__ == "__main__":
    main()
