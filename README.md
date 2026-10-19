# CarTaxiLauncher

Kotlin Android Automotive navigation launcher. GPS requires Android runtime location permission. Road-sign requests use the public Overpass API; map styles use OpenFreeMap. This repository has **no user login, OAuth/JWT, refresh-token storage or authenticated backend**.

## Build and release (iPhone-friendly)

- Commits to main and pull requests run the **verify** job, building an Android debug APK for compile verification.
- Publishing an APK is **disabled by default**. The owner must explicitly open **Actions → Build and verify → Run workflow** on `main`, enable **publish_release**, and run it after checking the signing certificate.
- A release uses a pre-existing PKCS#12 key from Actions secrets, **never a checked-in keystore** and **never the default Android debug key**.
- If any signing input is absent/invalid or the signing certificate doesn't match the approved fingerprint, publication fails.

### Required GitHub Actions configuration

On iPhone, open the repository in Safari → **Settings → Secrets and variables → Actions** and set:

1. **Repository secret `CARTAXI_KEYSTORE_BASE64`**: Base64 of the actual, previously used `cartaxi` PKCS#12 keystore. Keep its full contents private; do not store it in repository code, screenshots, issues, or chat.
2. **Repository secret `CARTAXI_KEYSTORE_PASSWORD`**: password for the PKCS#12 store and its key alias `cartaxi`.
3. **Repository variable `CARTAXI_SIGNING_CERT_SHA256`**: SHA-256 certificate fingerprint of an **existing APK already installed by users**, obtained from a trusted APK/certificate verification step. Fingerprints are public data; do **not** guess this value or use the SHA-256 digest of the APK file.

To encode the PKCS#12 file using iPhone only: save the keystore in the iOS Files app, make a Shortcuts action chain **Select File → Base64 Encode (line breaks: none) → Copy to Clipboard**, then paste into the GitHub secret creation form. Clear the clipboard afterward. Use the exact previously used password; do not invent a new password for an existing store.

**Important compatibility note:** Previous CI ran `assembleDebug` while passing the secret under `KEYTHANHTAXIXANH`, but Gradle was looking for `CARTAXI_KEYSTORE_PASSWORD`. Therefore previously published APKs may have been signed with a machine-specific Android **debug** certificate, not the `cartaxi` PKCS#12 certificate. APK updates require compatible signatures. If the signing fingerprints differ, **stop**: do not publish an incompatible update. Choose an explicit migration strategy, potentially involving uninstall/reinstall and loss of local data.

The previous file `signing/cartaxi-release.p12` was tracked in a public repository. This change deletes it from the current Git tree and ignores keystores, **but does not remove copies from prior commits, forks or caches**. Treat it as potentially exposed. Avoid rotating the key without understanding update compatibility. Store a secure backup before any permanent history rewrite.

### Local builds (optional; no computer needed)

A developer may set `CARTAXI_KEYSTORE_FILE` and `CARTAXI_KEYSTORE_PASSWORD` to build a signed release locally. CI verification uses ordinary Android debug signing with no secrets. `assembleRelease` without signing configuration is not a distributable signed release.

### Security boundaries

- The GitHub verification job has only `contents: read`. Write access exists only in the manually gated publishing job, for creating a Release.
- The publishing job restores the keystore only under the ephemeral GitHub runner temporary directory, checks the alias and approved certificate SHA-256, builds a signed APK, verifies its signature, then removes temporary signing material.
- The application requests Android location permission and checks it before GPS updates. API requests shown in `RoadSigns.kt` do not include `Authorization` headers or tokens.
- The release workflow depends on GitHub Actions secret administration, which must be done by the repository owner. Do not send private keys or passwords in this chat.

### Audit limitations

Deleting the old private key from the active branch does not purge public Git history. The migration is complete only after the repository owner provisions the required secrets and confirms the installed APK signature. Until then **publishing stays disabled**.
