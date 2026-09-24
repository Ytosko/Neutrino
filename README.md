<p align="center">
  <img src="logos/logo.svg" width="96" height="96" alt="Neutrino logo">
</p>

<h1 align="center">Neutrino</h1>

<p align="center">
  <b>Snap your plate. Log your macros.</b><br>
  Free, open-source, privacy-first food logging for Android.
</p>

<p align="center">
  <a href="https://neutrino.ytosko.dev">Website</a> ·
  <a href="https://neutrino.ytosko.dev/privacy">Privacy</a> ·
  <a href="https://neutrino.ytosko.dev/terms">Terms</a> ·
  <a href="LICENSE">GPL-3.0</a>
</p>

---

Neutrino looks at a photo of your meal with **the AI model you choose**, using your own
Google Gemini or OpenAI API key, and estimates carbohydrates, protein, fat and calories. You review
the numbers, and Neutrino saves them to **Health Connect** as breakfast, lunch, dinner or a snack
based on your local time, so they show up in Google Health, Fitbit and any other app you allow.

> **Status:** early development. The website is live; the Android app is being built.

## Principles

- **No servers.** The app talks directly to your AI provider and to Health Connect on your phone.
- **Bring your own key.** You pay your AI provider directly. Keys are encrypted with Android Keystore.
- **No tracking.** No analytics, ads, crash reporting or accounts.
- **Your backups.** Encrypted local backups, plus optional daily/weekly/monthly backups to a
  private app folder in *your* Google Drive.
- **Estimates, not medical advice.** You always review before saving. Neutrino is not a medical device.

## Planned features

- Photo → foods, portions and macros via Gemini or OpenAI (pluggable providers)
- Model picker, which lists vision-capable models your key can access
- Automatic meal type from your time zone and configurable meal windows
- Cuisine hints for better recognition of regional food (e.g. Bangladeshi)
- Water logging
- Writes `NutritionRecord` / `HydrationRecord` to Health Connect
- WhatsApp-style backups: mandatory first local backup, optional Google Drive (`drive.appdata`)

## Repository layout

```
.
├── app/                    # Android app (Kotlin + Jetpack Compose)
├── docs/                   # Developer docs (signing, OAuth setup)
├── logos/                  # Brand assets (SVG + PNG variants)
├── web/                    # Website: landing page, privacy policy, terms
│   ├── public/             # Static files served as-is
│   ├── nginx.conf          # Clean URLs, caching, no access logs
│   ├── security-headers.conf
│   └── Dockerfile          # nginx-unprivileged on :8080
└── docker-compose.yml      # Deploys the website (Coolify-ready)
```

## Website

The site is plain HTML/CSS with no build step, no JavaScript, and self-hosted fonts.

**Preview locally**

```bash
cp docker-compose.override.example.yml docker-compose.override.yml
docker compose up --build
# open http://localhost:8080
```

Or without Docker: `python -m http.server 8080 --directory web/public` (clean URLs such as
`/privacy` only work through nginx; use `/privacy.html` here).

**Deploy with Coolify**

1. *New resource → Docker Compose* → select this repository and branch `main`.
2. Set the `web` service domain to `https://neutrino.ytosko.dev:8080`
   (the `:8080` tells Coolify which container port to route to; the public URL stays on 443).
3. Point a DNS `A` record for `neutrino.ytosko.dev` at your Coolify server, then deploy.

## Android app

Kotlin, Jetpack Compose and Material 3. Minimum Android 9 (API 28), targets Android 16 (API 36).
No DI framework, no analytics, no Google Play Services dependency for core features.

```bash
./gradlew assembleDebug          # build app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```

Open the repository root in Android Studio to run it on a device or emulator.
Release signing and Google OAuth setup: [docs/SIGNING.md](docs/SIGNING.md).

## Brand

Primary colour `#FF5757`. Font: [Plus Jakarta Sans](https://github.com/tokotype/PlusJakartaSans) (OFL).
Logo files live in [`logos/`](logos):

| File | Use |
|---|---|
| `logo.svg` / `logo.png` / `logo-1024.png` | App icon tile (rounded square) |
| `logo-square.svg` | Full-bleed square (Android adaptive / maskable icons) |
| `logo-round.svg` / `logo-round.png` | Circular avatar (GitHub, social) |
| `logo-mark.svg` | Coral mark on transparent |
| `logo-mark-white.svg` / `logo-mark-dark.svg` | Mono marks for dark / light backgrounds |

The GPL-3.0 covers the code, not the Neutrino name and logo. Forks are welcome under a different
name and icon.

## Contributing

Issues and pull requests are welcome. Contribution guidelines will land with the Android app.
For security issues, see [SECURITY.md](SECURITY.md).

## License

Neutrino is licensed under the [GNU General Public License v3.0](LICENSE).
Icons on the website are from [Lucide](https://lucide.dev) (ISC).

Health Connect, Google Health, Fitbit and Gemini are trademarks of Google LLC. OpenAI is a
trademark of OpenAI. Neutrino is not affiliated with or endorsed by either.
