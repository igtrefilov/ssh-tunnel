# Android release identity

`release-certificate.sha256` is the public SHA-256 digest of the certificate
used to sign existing release APKs. The release script refuses an APK signed by
another certificate.

The private PKCS#12 file and its password are local secrets and must never be
committed. Local builds expect them under `../keys/` by default. CI receives
them through protected GitHub environment secrets.
