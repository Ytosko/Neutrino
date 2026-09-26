# App signing

Neutrino uses two signing keys. Neither is ever committed to the repository.

| Key | Where it lives | Used for |
|---|---|---|
| **Debug** | `~/.android/debug.keystore`, created automatically by the first build | Local development builds |
| **Release (upload key)** | Created once by the maintainer, stored outside the repo | Signing releases for GitHub and Google Play |

Each key has a **SHA-1 fingerprint**. Google Drive backup (Google sign-in) only works for builds whose
SHA-1 is registered as an **Android OAuth client** in the Google Cloud project, with package name
`dev.ytosko.neutrino`.

## 1. Create the release key (once)

Run this **outside** the repository folder, for example in a private `neutrino-keys` folder, and choose
a strong password when asked:

```bash
keytool -genkeypair -v -keystore neutrino-release.jks -alias neutrino -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Neutrino, O=ytosko"
```

**Back up `neutrino-release.jks` and its password** somewhere safe (a password manager plus an
offline copy). If this key is lost you cannot publish updates to the same Google Play listing.

## 2. Point the build at it

Create `keystore.properties` in the repository root (it is git-ignored):

```properties
storeFile=C:/path/to/neutrino-keys/neutrino-release.jks
storePassword=your-store-password
keyAlias=neutrino
keyPassword=your-key-password
```

Then build a signed release:

```bash
./gradlew assembleFullRelease
```

## 3. Get fingerprints

```bash
keytool -list -v -keystore neutrino-release.jks -alias neutrino
```

Debug key (default password is `android`):

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

## 4. Register in Google Cloud

Google Cloud Console → **Google Auth Platform → Clients → Create client → Android**:

- Package name: `dev.ytosko.neutrino`
- SHA-1: the fingerprint from step 3

Create one client per key (debug, release, and later Google Play's app-signing key, which is shown in
Play Console → Setup → App signing).

### Registered fingerprints (official builds)

Fingerprints are public and safe to publish.

| Key | SHA-1 |
|---|---|
| Release (upload key, alias `neutrino`) | `A5:52:B9:63:4F:F9:66:58:5A:64:28:04:84:CB:90:40:38:56:5C:72` |
| Maintainer debug key | `9E:C8:D0:72:7B:FD:EE:77:47:8B:89:6D:2E:A3:1A:88:F9:F2:66:CC` |
| Google Play app signing | _added after the first Play upload_ |

Debug keys are per machine. Contributors who need Drive backup while developing must register their own
debug SHA-1 in their own Cloud project (see "Building your own fork").

## Building your own fork

Forks must use their own package name and register their own OAuth client. Everything except Google
Drive backup works without one.
