# F-Droid

Neutrino has two build flavours:

| Flavour | Where | Google libraries | Backups |
|---|---|---|---|
| `full` | GitHub Releases (signed with the Neutrino key) | Google Play services, only for Drive sign-in | encrypted file + optional Google Drive |
| `libre` | F-Droid | none | encrypted file only |

Everything else is the same app. The `libre` build replaces `GoogleDriveAuth` with a stub
(`app/src/libre/…/GoogleDriveAuth.kt`) that reports Drive as unavailable, so the backup screens
simply don't offer Drive.

```bash
./gradlew :app:assembleLibreRelease
./gradlew :app:dependencies --configuration libreReleaseRuntimeClasspath | grep -i gms   # prints nothing
```

The F-Droid build leaves out the Wear OS app (`wear/`): it needs Google Play services' Wearable
Data Layer, and the `libre` phone app has no watch support. `settings.gradle.kts` only includes the
`:wear` module when the `wear/` folder exists, so the F-Droid recipe removes it before building
(`rm: [wear]`, see below). `wear-protocol/` stays: it's plain Kotlin with no Google libraries, and
the `libre` flavour doesn't use it.

## Store listing

F-Droid reads the listing from `fastlane/metadata/android/en-US/`: `title.txt`,
`short_description.txt` (80 characters at most), `full_description.txt`, `images/icon.png`,
`images/phoneScreenshots/`, and `changelogs/<versionCode>.txt` (500 characters at most).

**With each release**, add `changelogs/<versionCode>.txt` (e.g. `10006.txt` for 1.0.6), a short
plain-text version of that release's CHANGELOG.md section.

## Submitting

Inclusion is requested with a merge request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) (a GitLab account is needed), adding
`metadata/dev.ytosko.neutrino.yml`. A starting point:

```yaml
Categories:
  - Health Manager
  - Sports & Health
License: GPL-3.0-only
AuthorName: Ytosko
WebSite: https://neutrino.ytosko.dev
SourceCode: https://github.com/Ytosko/Neutrino
IssueTracker: https://github.com/Ytosko/Neutrino/issues
Changelog: https://github.com/Ytosko/Neutrino/blob/main/CHANGELOG.md

AutoName: Neutrino
AntiFeatures:
  NonFreeNet:
    en-US: Meal photos are sent to the AI provider you choose (Google Gemini or OpenAI), with your own API key. Medicine names you type can be looked up on MedEx.

RepoType: git
Repo: https://github.com/Ytosko/Neutrino.git

Builds:
  - versionName: 1.0.6
    versionCode: 10006
    commit: v1.0.6
    subdir: app
    rm:
      - wear
    gradle:
      - libre
    gradleprops:
      - versionName=1.0.6

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 1.0.6
CurrentVersionCode: 10006
```

Notes for the review:

- The version name comes from the `versionName` Gradle property (the release tag without the
  `v`), and the version code is calculated from it (`major × 10000 + minor × 100 + patch`), hence
  `gradleprops`. F-Droid's reviewers may prefer a different update check; follow their advice.
- The first F-Droid build must come from a tag that already has the `libre` flavour (1.0.6 or
  later), so update `versionName`, `versionCode`, `commit` and `gradleprops` to that release.
- F-Droid signs with its own key, so the F-Droid and GitHub versions can't be installed over each
  other. Moving between them means a backup file, uninstall, install, restore.
- Health Connect (`androidx.health.connect:connect-client`) is an open-source AndroidX library; the
  Health Connect app itself is optional and Neutrino works without it.
