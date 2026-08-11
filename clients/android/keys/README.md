# Local client keys

This directory is reserved for private client SSH keys and release-signing
material. Everything except this README is ignored by Git.

Generate a fresh SSH deployment key with:

```bash
./scripts/generate-ssh-key.sh
```

The script creates:

```text
keys/ssh_tunnel_key                         private source copy
keys/ssh_tunnel_key.pub                     public server input
app/src/main/assets-bundled/ssh_tunnel_key  private APK asset
```

Only the public `.pub` file may be transferred to the VPS. Install it through
`server/gateway/deploy.sh --public-key-file FILE`, which applies the gateway's
forwarding-only restrictions. Install the same public key with
`server/jump-host/deploy.sh` when the Android route uses a jump host.

Local release signing files conventionally use:

```text
keys/ssh-tunnel-release.p12
keys/ssh-tunnel-release.pass
```

The signing certificate must match previously installed release APKs.
