#!/usr/bin/env python3
"""Run the Android lifecycle tests against disposable loopback SSH fixtures.

Requires an already booted emulator and built debug/test APKs. Refuses physical
devices. Does not install or change settings on the user's phone.
"""
import argparse
import os
from pathlib import Path
import pwd
import socketserver
import subprocess
import tempfile
import threading
import time

from test_socks_gateway import free_tcp_port, recv_exact, read_address
from test_ssh_routes import temporary_sshd


class SocksEcho(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            connection = self.request
            connection.settimeout(10)
            version, count = recv_exact(connection, 2)
            assert version == 5 and 0 in recv_exact(connection, count)
            connection.sendall(b"\x05\x00")
            version, command, _, address_type = recv_exact(connection, 4)
            host, port = read_address(connection, address_type)
            # The address is deliberately unroutable outside the emulator: a successful
            # exchange proves the selected app went through TUN -> HEV -> SSH -> SOCKS.
            if version != 5 or command != 1 or host != "198.18.10.10" or port != 12345:
                # The app's update checker can also run while its own UID is selected.
                connection.sendall(b"\x05\x02\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            connection.sendall(b"\x05\x00\x00\x01\x7f\x00\x00\x01\x30\x39")
            while data := connection.recv(65536):
                connection.sendall(data)
        except (OSError, RuntimeError):
            pass


class SocksServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly selected emulator is permitted")
    root = Path(__file__).resolve().parents[2]
    android = root / "clients/android"
    adb = [args.adb, "-s", args.serial]
    package = "net.tref.xraytunnel"  # Stable Android update identity.
    qemu = subprocess.check_output([*adb, "shell", "getprop", "ro.kernel.qemu"], text=True).strip()
    if qemu != "1":
        parser.error("Selected device is not an emulator")
    deadline = time.monotonic() + 120
    while subprocess.check_output([*adb, "shell", "getprop", "sys.boot_completed"], text=True).strip() != "1":
        if time.monotonic() >= deadline:
            raise RuntimeError("Emulator did not finish booting")
        time.sleep(1)
    with tempfile.TemporaryDirectory(prefix="ssh-tunnel-android-") as temporary, SocksServer(("127.0.0.1", 0), SocksEcho) as socks:
        directory = Path(temporary)
        authorized = directory / "authorized_keys"
        identity = android / "app/src/main/assets-bundled/ssh_tunnel_key"
        with authorized.open("wb") as output:
            subprocess.run(["ssh-keygen", "-y", "-f", str(identity)], stdout=output, check=True)
        authorized.chmod(0o600)
        threading.Thread(target=socks.serve_forever, daemon=True).start()
        socks_port = socks.server_address[1]
        gateway_port, jump_port = free_tcp_port(), free_tcp_port()
        with temporary_sshd(directory, "gateway", gateway_port, authorized, f"127.0.0.1:{socks_port}"), \
                temporary_sshd(directory, "jump", jump_port, authorized, f"127.0.0.1:{gateway_port}"):
            for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
                subprocess.run([*adb, "install", "-r", str(android / apk)], check=True)
            subprocess.run([*adb, "shell", "appops", "set", package, "ACTIVATE_VPN", "allow"], check=True)
            subprocess.run([*adb, "shell", "am", "start", "-n", f"{package}/net.tref.sshtunnel.MainActivity"], check=True)
            command = [*adb, "shell", "am", "instrument", "-w", "-r"]
            for key, value in {"gatewayPort": gateway_port, "jumpPort": jump_port,
                               "socksPort": socks_port, "echoPort": 12345,
                               "sshUser": pwd.getpwuid(os.getuid()).pw_name}.items():
                command += ["-e", key, str(value)]
            command += [f"{package}.test/androidx.test.runner.AndroidJUnitRunner"]
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
            print(result.stdout)
            if "OK (2 tests)" not in result.stdout:
                raise RuntimeError("Android instrumentation tests failed")
        socks.shutdown()


if __name__ == "__main__":
    main()
