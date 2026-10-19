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
2. **Repository secret `KEYTHANHTAXIXANH`**: password for the PKCS#12 store and its key alias `cartaxi`.
3. **Repository variable `CARTAXI_SIGNING_CERT_SHA256`**: SHA-256 **certificate fingerprint of the chosen PKCS#12 `cartaxi` signing key**, NOT build-71 (which used an incompatible Android Debug certificate). Retrieve it by inspecting the public certificate of the exact keystore you will place in `CARTAXI_KEYSTORE_BASE64`. Do not use the APK file's checksum or guess this value.

To encode the PKCS#12 file using iPhone only: save the keystore in the iOS Files app, make a Shortcuts action chain **Select File → Base64 Encode (line breaks: none) → Copy to Clipboard**, then paste into the GitHub secret creation form. Clear the clipboard afterward. Use the exact previously used password; do not invent a new password for an existing store.

**Confirmed one-time migration (option B):** The repository owner installed build-71, and CI verified that `KEYTHANHTAXIXANH` **does unlock** the historical `cartaxi-release.p12`, but that key's signing certificate **does NOT match** build-71. Both build-1 and build-71 were signed with Android Debug certificates, and those two fingerprints differ. An APK signed with the `cartaxi` PKCS#12 key cannot normally update build-71 in place. This migration deliberately switches to the private PKCS#12 key as the stable release signer. Before installing the first newly signed APK, back up any application data you need, then uninstall build-71 and install the new release. Uninstallation can erase local application data, preferences and app-specific files. Do **not** uninstall until the new signed release is built and downloadable. Android Auto settings/permissions may need to be restored. Subsequent APKs must keep the same private key and a higher `versionCode` for in-place updates.

The GitHub Secret `KEYTHANHTAXIXANH` is the **password**, not the private key itself. The PKCS#12 keystore must separately be supplied in `CARTAXI_KEYSTORE_BASE64`; the CI maps your existing secret name to the internal Gradle environment variable `CARTAXI_KEYSTORE_PASSWORD`.

The previous file `signing/cartaxi-release.p12` was tracked in a public repository. This change deletes it from the current Git tree and ignores keystores, **but does not remove copies from prior commits, forks or caches**. Treat it as potentially exposed. Avoid rotating the key without understanding update compatibility. Store a secure backup before any permanent history rewrite.

### Local builds (optional; no computer needed)

A developer may set `CARTAXI_KEYSTORE_FILE` and `CARTAXI_KEYSTORE_PASSWORD` to build a signed release locally. CI verification uses ordinary Android debug signing with no secrets. `assembleRelease` without signing configuration is not a distributable signed release.

### Security boundaries

- The GitHub verification job has only `contents: read`. Write access exists only in the manually gated publishing job, for creating a Release.
- The publishing job restores the keystore only under the ephemeral GitHub runner temporary directory, checks the alias and approved certificate SHA-256, builds a signed APK, verifies its signature, then removes temporary signing material.
- The application requests Android location permission and checks it before GPS updates. API requests shown in `RoadSigns.kt` do not include `Authorization` headers or tokens.
- The release workflow depends on GitHub Actions secret administration, which must be done by the repository owner. Do not send private keys or passwords in this chat.

### Audit limitations

Deleting the old private key from the active branch does not purge public Git history. The migration is complete only after the repository owner provisions the keystore secret and approves its certificate fingerprint, the signed APK passes CI signature verification, and the owner intentionally performs the one-time reinstall. Publishing stays disabled unless the owner explicitly selects **publish_release** in the manual workflow. Do not confuse this key certificate with the incompatible build-71 certificate.

### iPhone-only migration checklist

1. Keep build-71 installed and working. **Do not uninstall it yet.**
2. Open the historical `signing/cartaxi-release.p12` file from the old `main` commit in GitHub Safari (or your own known-good backup), choose Download and save it privately to the iOS Files app. GitHub source history has already exposed this encrypted PKCS#12 container, so assess key-compromise risk before using it long-term.
3. In the iOS Shortcuts app make **Select File → Base64 Encode (no line breaks) → Copy to Clipboard**. Select that exact PKCS#12 file, and paste the encoded result into the GitHub Actions **repository secret** `CARTAXI_KEYSTORE_BASE64`. Never post the encoding in a GitHub comment or chat. The separate secret `KEYTHANHTAXIXANH` is already verified as its password.
4. Determine the SHA-256 *certificate fingerprint* for the `cartaxi` alias in the intended PKCS#12 key by trusted inspection and enter it in GitHub Actions **repository variable** `CARTAXI_SIGNING_CERT_SHA256`. It will intentionally differ from build-71. Do not enter build-71's fingerprint.
5. Check PR compilation, merge the security PR after review, then run **Actions → Build and verify → Run workflow → publish_release = true** on `main`. The pipeline checks the keystore against the configured fingerprint and checks the APK signer before uploading a Release. If signing configuration is missing, it exits without publishing.
6. Only **after** the new signed APK is available, back up local app data as appropriate. On the **Android phone used for Android Auto** (not the iPhone), uninstall build-71 and install the new APK. Approve Android Auto and GPS permissions again. Future builds using this key and increasing version codes can update in place.

**Security trade-off:** Because the original PKCS#12 file exists in public Git history, adopting it long-term does not fully remediate signing-key exposure. A fresh private key stored entirely outside Git history is safer, but requires another intentional signer migration before installation. The chosen workflow does not pretend the historical key has become private simply because it is no longer in the current branch.
