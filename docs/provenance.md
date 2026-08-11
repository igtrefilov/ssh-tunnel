# Import provenance

This monorepository was assembled without modifying either source worktree.

- Android client and original HEV deployment: commit
  `71294c7aeeb76533ba451ecd167d5ffc5bba372d` from
  `/home/ilya/android/ssh-tunnel-apk`, branch
  `feature/standalone-ssh-vpn-split`.
- Linux client: commit `eff3d0e` from `/home/ilya/ssh-tun-linux` plus its local
  uncommitted ProxyJump, proxy-environment and installer changes as of import.

The local Android signing PKCS#12 and password were copied into the new ignored
`clients/android/keys/` directory with mode 0600. They are intentionally absent
from Git. Its public certificate SHA-256 is committed under
`clients/android/signing/` so every signed release can verify continuity.
