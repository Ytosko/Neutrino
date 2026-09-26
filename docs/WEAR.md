# Neutrino on Wear OS

Neutrino has a small companion app for Wear OS watches (Wear OS 3 / Android 11 or newer, e.g. Pixel
Watch, Galaxy Watch 4 and later). It shows what the Neutrino app on your phone already knows:

- **Tile:** today's carbs, protein, fat and kcal as rings toward your goals (just the amount if you
  haven't set a goal), water for the day, your latest glucose reading coloured low / in range /
  high, and a **+ 250 ml** button for water.
- **Complications** for your watch face: latest glucose, and today's carbs (as a gauge toward your
  carb goal when you have one).
- **The app:** the same things in a list, with **+ 250 ml**.

Everything is logged on the phone. The watch only shows it, and **+ 250 ml** asks the phone to add
the water.

## What you need

- The **GitHub** version of Neutrino on your phone (`neutrino-….apk` from
  [Releases](https://github.com/Ytosko/Neutrino/releases)). The F-Droid version has no Google
  libraries, so it can't talk to a watch.
- A Wear OS watch paired with that phone in the Wear OS / Galaxy Wearable / Pixel Watch app.
- A computer with `adb` ([Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)),
  one time, to install the watch app. Once Neutrino is on Google Play, the watch app will install
  from there automatically and none of this is needed.

## Installing the watch app

Download `neutrino-wear-….apk` from the same release as your phone app. The two must come from the
same release page, because they're signed with the same key and Wear OS only connects apps whose
signatures match.

1. **Turn on developer options on the watch.** Settings → System → About → Versions (on some
   watches: Settings → About watch → Software information) → tap **Build number** seven times.
2. **Turn on wireless debugging.** Settings → Developer options → turn on **ADB debugging**, then
   **Wireless debugging** (or **Debug over Wi-Fi** on older watches). The watch and computer must
   be on the same Wi-Fi network.
3. **Pair the computer with the watch** (Wear OS 3 and newer). In Wireless debugging, tap
   **Pair new device**. The watch shows an address with a port, and a six-digit code. On the
   computer:

   ```bash
   adb pair 192.168.1.23:37099        # the pairing address and port shown on the watch
   # enter the six-digit code when asked
   ```

4. **Connect.** Back on the Wireless debugging screen, the watch shows its **IP address & port**
   (a different port from pairing):

   ```bash
   adb connect 192.168.1.23:41235
   adb devices                         # the watch should be listed
   ```

   On older watches with **Debug over Wi-Fi**, skip pairing: `adb connect 192.168.1.23:5555`.

5. **Install:**

   ```bash
   adb install neutrino-wear-v1.5-NeutrinoBuddy.apk
   ```

   With the phone also plugged in, add `-s <watch address>` so the APK goes to the watch.

6. **Tidy up.** Turn off wireless debugging and developer options on the watch if you like;
   the app stays installed.

Then add the Neutrino tile (long-press the watch face's tiles or open the tile editor in the
companion app) and the complications (long-press the watch face → Edit → Complications).

**Updating:** install the new `neutrino-wear-….apk` the same way (`adb install -r …`), after
updating the phone app from the same release.

## If it says "Open Neutrino on your phone"

The watch hasn't received anything yet. Opening the watch app asks the phone for today's summary;
if the phone is nearby this takes a few seconds. Otherwise open Neutrino on the phone and log
something: the watch updates whenever meals, water or glucose change. Check that the phone app is
the GitHub version and that both came from the same release.

## Privacy

- The phone sends one small summary for today: the four totals, your goals, water, and the latest
  glucose reading with its time and colour band. Nothing older, no meal names or photos.
- It goes only between your phone and your own paired watch, through Wear OS's Data Layer, the
  same connection your notifications use. When the watch isn't near the phone, Wear OS may relay
  it through Google's servers as it does for notifications; Neutrino itself sends nothing to
  anyone.
- The watch app has no internet permission, no analytics and no crash reporting. It keeps just
  the last summary, so the tile works when the phone is out of range.
- Uninstalling the watch app deletes that copy.

## For developers

```bash
./gradlew :wear:assembleDebug                 # wear/build/outputs/apk/debug/wear-debug.apk
./gradlew :wear-protocol:testDebugUnitTest    # snapshot and action format tests
adb -s <watch> install wear/build/outputs/apk/debug/wear-debug.apk
```

- `wear-protocol/`: what the phone and watch send each other. The phone writes a DataItem at
  `/neutrino/today` holding a JSON `WearSnapshot` (formatted values, raw numbers, goals, glucose
  band, timestamp). The watch sends messages to `/neutrino/action`: `water:250` to add water,
  `refresh` to ask for the snapshot again. Unknown actions are ignored, so new ones (e.g.
  `dose:<id>`) can be added later.
- Phone side: `app/src/full/…/wear/` (`WearBridge` publishes from `NeutrinoWidget.refresh`,
  `WearActionService` handles the watch's messages). The `libre` flavour has a no-op `WearBridge`.
- Watch side: `wear/` (Compose for Wear OS, Tiles with ProtoLayout, complication data sources).
- A debug watch app only connects to a debug phone app (both signed with your debug key).
