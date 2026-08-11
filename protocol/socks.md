# SOCKS5 contract

The gateway SOCKS listener binds only to `127.0.0.1`. SSH authentication and
forwarding policy provide access control, so the loopback service uses SOCKS5
no-authentication negotiation.

Required capabilities:

- standard command `0x01` (`CONNECT`) with IPv4, IPv6 and domain destinations;
- HEV command `0x05` (UDP-in-TCP) used by the Android TUN engine;
- bounded packet sizes, idle timeouts and bidirectional relay;
- no public listen address.

Linux applications normally use standard TCP CONNECT. `socks5h` sends domain
resolution to the gateway. Android captures raw TCP and UDP packets. Since SSH
local forwarding carries only TCP, its native client frames UDP datagrams
inside the SOCKS TCP stream with command `0x05`.

The implementation is currently pinned `hev-socks5-server 2.13.0`. The systemd
service name and this protocol contract intentionally do not expose that
implementation detail, allowing a compatible engine to replace it later.
