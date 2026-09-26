# Changelog

Every release has a section here, headed `## [x.y.z] - YYYY-MM-DD` with an optional ` · Release name`. The release workflow publishes the section as the GitHub release notes (and the name as its title), and refuses to release a version that has none.

## [1.0.5] - 2026-09-26 · v1.4-NeutrinoBuddy

Health Connect now covers your glucose readings too, and pages scroll all the way to the bottom.

### Health Connect
- **Blood glucose is part of the connection:** the Health Connect page lists nutrition, hydration and blood glucose, each marked Allowed or Not allowed. It's checked every time you open the page, so turning something off in Health Connect shows up straight away.
- **Allow only what's missing:** one button asks Health Connect for just what isn't allowed yet ("Allow blood glucose" or "Allow the rest"). New users are asked for all three when they connect.
- **Settings tells you what's off:** the Health Connect row says "Connected: nutrition, water and glucose", or which ones aren't allowed.
- **A note on the glucose card:** if your meals go to Health Connect but your glucose readings don't, the card says so, with a button to allow it. Readings saved in the meantime are sent once you do.

### Fixes
- Settings and other pages no longer stop above an empty strip at the bottom; they scroll all the way under the gesture bar.
- Release downloads are now named after the release, e.g. neutrino-v1.4-NeutrinoBuddy.apk.

## [1.0.4] - 2026-09-26 · v1.3-NeutrinoBuddy

A fresh new look, a Health page by day, week, month and year, a home screen widget, faster logging, your glucose next to your meals, reports for your doctor, and Neutrino in Bangla.

### A fresh new look
- **Warm and colourful:** Neutrino's warm blush background with white cards, so food photos and numbers stand out.
- **Colours that mean something:** coral for Neutrino and its main buttons; amber carbs, teal protein, violet fat, blue water, magenta glucose, green for "in range"; each part of Settings has its own colour.
- **Clearer warnings:** errors use a deeper red, so they no longer look like the brand colour.
- **iPhone-style polish:** soft shadows instead of outlines; compact page headers whose title shrinks as you scroll; solid icon squares in Settings; iPhone-style alerts and floating messages; a sliding Day / Week / Month / Year control; a "Log a meal" sheet with big tiles; press and hold a meal or a glucose reading to lift it over a blurred background with its options.
- **Motion and feel:** cards and buttons respond to a press, totals count up and goal rings glide to their new value, the water card is a glass that fills, and the phone gives a gentle tap for water, switches, saving and deleting.
- **Lighter text:** text is 20% smaller throughout for a calmer, more compact look (never below a readable minimum, and your phone's text-size setting still applies).
- **Health by calendar period:** Day, Week, Month and Year now show one real period at a time (today, this week, September 2026, 2026), with ‹ › arrows to step back and forth. Day splits into four 6-hour blocks, or 24 hours with "Hour by hour"; Year shows each month as a per-day average. Every card on the page follows the chosen period.
- **Charts:** rounded pill bars on a faint track, a dashed goal line with a ✓ in bars that reached your goal, the current day or month in bold, the touched value in a bubble, and friendly illustrations when a day or range is empty.
- **Widget:** carbs, protein, fat and calories are now rings that fill toward your daily goal, with the amount and its name in the middle ("72g Protein"), and even spacing.

### Logging
- **Log again:** press and hold any meal on your day and choose **Log again** to log the same foods (no photo, no AI cost), with Undo. For a meal from another day, choose **Today** or **that day** (at the same time). **Delete meal** is in the same menu, so meal cards stay clean.
- **Home screen widget:** today's carbs, protein, fat and calories (with your goal rings), your water with a one-tap + 250 ml, a camera button that opens straight into logging a meal, and your latest glucose reading (you can hide it in Settings).
- **App shortcuts:** long-press the Neutrino icon for "Log a meal" and "Add water".
- **Daily goals:** optional carbs, protein, fat, calories and water targets (Settings → Daily goals). Progress fills the border of each total on your day, so nothing shifts; tap a total to see the exact figures.

### Glucose
- **Add readings by hand:** tap + on the glucose card (or "Add a glucose reading" when logging) for readings from any meter. Readings you type in can be edited; readings from a paired meter never change.
- **mg/dL or mmol/L:** Settings → Glucose unit. It only changes how readings are shown. Existing users keep mmol/L; new users get the unit of their country.
- **Meals and glucose:** each meal shows your reading before it and about 2 hours after, and the Health page shows which meals raise your glucose the most, on average. Your own numbers only, no advice.
- **A page for each day's readings:** the day view shows your latest 3 readings; "See all" opens every reading of that day with a chart over your target range.
- **Test reminder:** optionally, "Time to check your glucose?" about 2 hours after a meal, skipped if you've already tested.

### Your data
- **Report for your doctor:** a PDF for the last 2 weeks, 1 month or 3 months, with glucose average, time in range, a daily chart, food and water per day, and every reading. Share it or save it (Settings → Reports and export).
- **Export everything:** meals, foods, water, glucose and your food list as spreadsheet (CSV) files in one zip.
- **Weekly summary:** an optional Sunday-evening notification about your week. Values are hidden on the lock screen.

### Privacy
- **App lock:** unlock Neutrino with your fingerprint, face or screen lock. It locks again after 2 minutes away.
- **Hide in recent apps:** blank Neutrino's preview in the app switcher and block screenshots.

### Bangla
- **Neutrino in Bangla (বাংলা):** Settings → App language, or follow your phone's language. Numbers stay in regular digits so readings are never ambiguous.

### Fixes
- Restoring a backup on Android 9 to 12 no longer fails.

## [1.0.3] - 2026-09-26 · v1.2-NeutrinoBuddy

Blood glucose from your Bluetooth meter, right next to your meals.

### Glucose meter
- **Pair once:** Settings → Glucose meters → **+**, choose your model (Contour Plus One, Contour Plus Elite or Contour Plus Blue), and follow its pairing steps: put the meter in pairing mode, choose it, and type the PIN it shows. Tap **Done** and it's in your list. If you use the CONTOUR DIABETES app, turn off its syncing first.
- **Up to 5 meters:** for example one at home and one at work. Each keeps its own sync history; tap a meter to see its status, sync it now or forget it.
- **Readings wait in the meter:** tests taken while your phone is away are kept in the meter's memory and arrive the next time it connects (after your next test, or when you turn the meter on near your phone). No internet is needed.
- **Syncs on its own:** after each test, Android wakes Neutrino and the new reading is saved, even if you haven't opened the app for days. Readings taken while the phone was away arrive the next time the meter connects.
- **Correct times:** if the meter's clock is wrong, Neutrino sets it to your phone's time. If the meter doesn't allow that, every reading is corrected by the difference. Readings whose time can't be trusted (for example after a battery change) are marked so you can fix them.
- **On the Days page:** below your meals, each reading with its time, value in mmol/L, low / in range / high, and meal mark. Readings without a mark are saved as General. Tap one to change the meal mark or time, or delete it with Undo.
- **On the Health page:** average glucose, a chart per day, week, month or year, time in range, and averages by meal mark.
- **Target range:** 4.0–10.0 mmol/L by default. Change it on any meter's page; it applies to all your meters.
- **Health Connect:** readings are written as blood glucose, with the meal mark, so they appear in Google Health. Neutrino still never reads your health data.
- **Private:** readings stay on your phone, in your encrypted backups and in Health Connect. Notifications show only how many readings arrived, never the values. Needs the "Nearby devices" permission, which is never used for location.

### Also in this release
- Meter readings in Health Connect are marked as recorded by your meter, not entered by hand.
- Days page: busy days show the latest 3 readings, with **Show all** for the rest.

### Install or update
- **Already on 1.0.2 or 1.0.1:** download `neutrino-1.0.3.apk` below and open it. It installs over the old version and keeps all your data.
- **New install:** Android 9 or newer. Allow installing from your browser or file manager when Android asks.
- **Glucose meter:** background syncing needs Android 12 or newer. On older phones, readings sync when you open Neutrino or tap Sync now.

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
