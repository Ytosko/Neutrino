# Changelog

Every release has a section here, headed `## [x.y.z] - YYYY-MM-DD` with an optional ` · Release name`. The release workflow publishes the section as the GitHub release notes (and the name as its title), and refuses to release a version that has none.

## [Unreleased]

Blood glucose from your Bluetooth meter, right next to your meals.

### Glucose meter
- **Pair once:** Settings → Glucose meter → Pair meter. Put the meter in pairing mode, choose it, and type the PIN it shows. Works with meters that use the standard Bluetooth Glucose Profile, such as the CONTOUR PLUS ELITE. If you use the CONTOUR DIABETES app, turn off its syncing first.
- **Syncs on its own:** after each test, Android wakes Neutrino and the new reading is saved, even if you haven't opened the app for days. Readings taken while the phone was away arrive the next time the meter connects.
- **Correct times:** if the meter's clock is wrong, Neutrino sets it to your phone's time. If the meter doesn't allow that, every reading is corrected by the difference. Readings whose time can't be trusted (for example after a battery change) are marked so you can fix them.
- **On the Days page:** each reading with its time, value in mmol/L, low / in range / high, and meal mark. Readings without a mark are saved as General. Tap one to change the meal mark or time, or delete it with Undo.
- **On the Health page:** average glucose, a chart per day, week, month or year, time in range, and averages by meal mark.
- **Target range:** 4.0–10.0 mmol/L by default. Change it in Settings → Glucose meter.
- **Health Connect:** readings are written as blood glucose, with the meal mark, so they appear in Google Health. Neutrino still never reads your health data.
- **Private:** readings stay on your phone, in your encrypted backups and in Health Connect. Notifications show only how many readings arrived, never the values. Needs the "Nearby devices" permission, which is never used for location.

## [1.0.2] - 2026-09-25 · v1.1-NeutrinoBuddy

Everything you log now always reaches Health Connect, even if it wasn't connected at the time.

### Health Connect sync
- **Catch-up sync:** meals and water saved while Health Connect wasn't connected (for example before you granted access, or after access was turned off) are sent automatically as soon as it's connected again, with their original date and time. The "Not synced" label disappears once they're sent.
- **Deletes catch up too:** if you delete a meal or remove a glass of water while Health Connect isn't connected, Neutrino remembers it and removes it from Health Connect later, so nothing you deleted lingers in Google Health. Tapping **Undo** before then cancels the delete.
- **When it syncs:** when Neutrino starts, every time you return to it, right after you grant Health Connect access, and once a day in the background.

### Install or update
- **Already on 1.0.1:** download `neutrino-1.0.2.apk` below and open it. It installs over 1.0.1 and keeps all your data.
- **New install:** Android 9 or newer. Allow installing from your browser or file manager when Android asks.

## [1.0.1] - 2026-09-25 · v1-NeutrinoBuddy

The first public release of Neutrino: snap your plate, log your macros.

Neutrino is a free, open-source Android app that estimates the carbs, protein, fat and calories in your meals with the AI model you choose, using your own Google Gemini or OpenAI API key, and saves them to Health Connect. There are no Neutrino servers, no accounts and no tracking.

### Logging meals
- **Photo to foods:** take a photo or pick one from your gallery. The AI lists each food with its portion, and you review everything before saving.
- **Add foods by hand:** search your own foods, about 200 built-in foods (USDA generic foods plus common Bangladeshi and South Asian dishes), packaged products from Open Food Facts, or add any new food and the AI estimates it once.
- **Everyday units:** plate, bowl, piece, cup, glass, can, g, kg, ml and L. Drinks show sizes in ml.
- **Learns your habits:** foods you eat often, recently or at this meal come first.
- **Automatic meal type:** breakfast, lunch, snack or dinner from your local time and your own meal times.
- **Edit anything later:** tap a meal to change its foods, amounts, name, type, date or time. Delete with Undo. Health Connect stays in sync.

### Days and Health
- **Days:** browse any day with the arrows or a calendar, and log meals or water for past days.
- **Water:** +250 ml and − on any day, shown in litres from 1 L, up to 10 L a day.
- **Health:** totals and charts for the last 30 days, 12 weeks, 12 months or 7 years. Touch or slide across bars to read each day, see where your calories come from, carbs by meal, water, and your most eaten foods.

### Reminders
- Breakfast, lunch and dinner reminders (10 AM, 2 PM and 6 PM by default), skipped when that meal is already logged. Times are adjustable in **Settings → Meals and reminders**.

### Backups
- **Automatic encrypted backup on your phone** that survives uninstalling the app.
- **Optional Google Drive backup** (daily, weekly or monthly) in a private app folder only Neutrino can open.
- **Restore on reinstall** from the phone or any Google account you pick. Everything is encrypted with your backup password before it's saved anywhere.

### AI and privacy
- Google Gemini or OpenAI with your own key, stored encrypted with Android Keystore. Pick any vision model your key can use, and choose photo detail to control token use.
- Optional cuisine and notes help the AI read your food.
- Photos are resized and stripped of location data before they're sent. Neutrino only writes to Health Connect and never reads your health data.

### Install
1. Download `neutrino-1.0.1.apk` below on your Android phone (Android 9 or newer).
2. Open it and allow installing from your browser or file manager when Android asks.
3. On Android 13 and older, install [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) if it isn't already on your phone.

Updates install over this version and keep your data. If you used a test build before, uninstall it first: your phone backup stays, and **Restore it** brings everything back.

### Good to know
- Estimates are approximate, especially portion sizes. Neutrino is not a medical device.
- Google Drive backup needs Google Play services.
- Neutrino is not on Google Play. The phone backup uses Android's "All files access" permission, which Neutrino uses only for its own backup file.
