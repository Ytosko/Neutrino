# Releasing Neutrino

Releases are APKs on [GitHub Releases](https://github.com/Ytosko/Neutrino/releases), built and signed
by GitHub Actions ([`.github/workflows/release.yml`](../.github/workflows/release.yml)) when a version
tag is pushed. The signing key is stored as encrypted repository secrets that only the maintainer
can set; it is never committed and never printed in logs.

## One-time setup: add the signing secrets

In the repository: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Value |
|---|---|
| `NEUTRINO_KEYSTORE_BASE64` | The release keystore file, base64-encoded (see below) |
| `NEUTRINO_KEYSTORE_PASSWORD` | The keystore password |
| `NEUTRINO_KEY_ALIAS` | The key alias (`neutrino` if you followed [SIGNING.md](SIGNING.md)) |
| `NEUTRINO_KEY_PASSWORD` | The key password |

Encode the keystore on your own computer:

```bash
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\neutrino-release.jks")) | Set-Clipboard

# macOS / Linux
base64 -i neutrino-release.jks | pbcopy        # or: base64 -w0 neutrino-release.jks
```

Paste the result as the value of `NEUTRINO_KEYSTORE_BASE64`.

## Publishing a release

1. Make sure `main` is green in **Actions → CI**.
2. Add a section for the version to [`CHANGELOG.md`](../CHANGELOG.md), e.g.
   `## [1.0.2] - 2026-10-01 · Release name`, describing what's new. Write each paragraph and bullet on
   one line (GitHub shows line breaks as-is). This becomes the release notes and title; the release
   fails without it. Commit and push.
3. Tag and push:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

4. The **Release** workflow tests, builds with `versionName` 1.0.0 (`versionCode` 10000), checks the
   APK is signed with the registered key (SHA-1 `A5:52:B9:…:5C:72`, see [SIGNING.md](SIGNING.md)),
   and publishes `neutrino-<release name>.apk` (e.g. `neutrino-v1.4-NeutrinoBuddy.apk`) with its SHA-256 checksum, using the CHANGELOG section as the notes.

Version codes come from the tag (`major × 10000 + minor × 100 + patch`), so each release installs
over the previous one.

## Installing

Neutrino isn't on Google Play. Users download the APK from Releases and allow installing from their
browser or file manager when Android asks. Updates install the same way, over the existing app,
keeping all data.
