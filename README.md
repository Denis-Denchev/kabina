# Kabina

**Turn any Android tablet into a factory-looking car screen.**

*Kabina* (кабина) is Bulgarian for "cabin" — the cockpit of your car.

Kabina is a home-screen (launcher) app for a tablet mounted in your dashboard: big clock and speed,
one-tap tiles for navigation, music and video, live engine data over a cheap Bluetooth OBD-II adapter,
control of Bluetooth ambient LED strips, and a day/night look that follows the sun.

> Created by **Denis Denchev** · Licensed under **GPL-3.0** with attribution terms — see [NOTICE](NOTICE).

## Features

- **Home screen**: clock, date, GPS/OBD speed, engine temperature and battery voltage, Wi-Fi and battery status,
  media controls (previous / play-pause / next for whatever player is active).
- **Tiles**: Navigation, Music, YouTube, Vehicle, Lighting, CarPlay/Android Auto dongle apps
  (Autokit, ZLink, Carlinkit…). Each tile opens the first installed app it knows; long-press Navigation opens Waze.
- **Built-in navigation**: own map with course-up rotation, Google Places search, Google Routes turn-by-turn with voice
  in the tablet's language, day and night map styles. Works with your own Google Maps Platform key
  (falls back to OpenStreetMap's public tiles without one — fine for light personal use under the
  [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/), not for heavy use).
- **Vehicle**: rev counter, speed, coolant, intake air, load, throttle, fuel level, battery voltage;
  read and clear trouble codes. Any ELM327 Bluetooth (classic SPP) adapter.
- **Ambient lighting**: BLE LED controllers ELK-BLEDOM, Triones and LEDDMX ("LED LAMP" app) — colours, brightness,
  effects (breathe, pulse, aurora, colour follows the revs), welcome animation when the ignition comes on.
- **Ignition aware**: USB power on → screen wakes with the logo intro and the car links start;
  power off → "Goodbye" animation and the screen turns off.
- **Day / night**: automatic from the sun's position and the light sensor, or fixed.
- **Car mode**: hides Android's status bar, navigation bar and notification shade (see below for what each Android
  version allows). Hold the title of the *All apps* screen for service mode.
- **Brand packs**: put your car maker's logo, a 3D model of your car and a photo of your cabin in a private folder —
  they are overlaid at build time and never need to be in the repo.
- **Languages**: English and Bulgarian. Translations welcome.

## Compatibility

Android **5.0 to 15**, phones and tablets, landscape. Designed for 8–10" tablets; smaller screens are scaled down.

| Feature | Android 5–7 | Android 8–10 | Android 11+ |
|---|---|---|---|
| Launcher, tiles, navigation, OBD, LEDs | ✓ | ✓ | ✓ |
| Hide bars in *all* apps (needs `WRITE_SECURE_SETTINGS` via adb) | ✓ | ✓ | – |
| Block the notification shade | ✓ (overlay) | device owner | device owner |
| Wake on ignition / screen off on power loss | ✓ | ✓ (app must stay running) | ✓ (device owner or "display over other apps") |

GPS, Bluetooth and the light sensor are optional — Wi-Fi-only tablets work, those features just stay idle.

## Install

1. Download the APK from [Releases](../../releases) and install it.
2. Press Home and choose **Kabina**, *Always*.
3. Allow **location** and **Bluetooth** when asked.
4. Pair your OBD adapter in Android's Bluetooth settings (PIN is usually `1234`). The LED strip is found automatically.

### Optional: full car mode (one-time, from a computer with adb)

```sh
# hide Android's bars in every app (Android 5–10)
adb shell pm grant bg.dmd.carhome android.permission.WRITE_SECURE_SETTINGS

# lock-screen-free kiosk, shade blocked, screen off on ignition off (any version).
# Works only on a device with no accounts added — remove Google accounts first.
adb shell dpm set-device-owner bg.dmd.carhome/.Admin
```

To undo device owner: *All apps* → hold the title → service mode, then
`adb shell dpm remove-active-admin bg.dmd.carhome/.Admin` (or factory reset).

### Google Maps key (for the built-in navigation)

Create a key in [Google Cloud Console](https://console.cloud.google.com/google/maps-apis) with
**Places API (New)**, **Routes API** and **Map Tiles API** enabled. Then either paste it in the app
(Navigation → search → *No Google API key — tap to add one*) or put it in `apikey.txt` before building.
Google gives a monthly free usage allowance; you pay for anything above it.

## Build

Needs JDK 17 and the Android SDK (`ANDROID_HOME` or `local.properties`).

```sh
./gradlew assembleDebug                         # neutral build
./gradlew assembleBrandedDebug -Pbrand=mycar     # with brands/mycar overlaid
```

Release builds are signed when `KABINA_KEYSTORE`, `KABINA_KEYSTORE_PASSWORD`, `KABINA_KEY_ALIAS` and
`KABINA_KEY_PASSWORD` are set. GitHub Actions builds an APK on every push and attaches it to tagged releases.

## Brand packs

See [brands/README.md](brands/README.md). In short: `brands/<name>/res/drawable-nodpi/logo.png`, an optional
`wordmark.png`, `ic_launcher.png` and `cabin.jpg`, `brands/<name>/assets/car.bin` (a 3D model baked with
`tools/bake_car.py`) and `brands/<name>/res/values/brand.xml` (app name, colour names, model credit).
Brand packs are git-ignored: **don't publish car makers' logos** — they are trademarks.

## Translating

Copy `app/src/main/res/values/strings.xml` to `values-<language code>/strings.xml`, translate it, open a pull request.

## Credits

- Created by **Denis Denchev**.
- Built-in map fallback: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
- Not affiliated with any car manufacturer, Google, Apple or the makers of the supported adapters.

## License

GPL-3.0-only with additional terms under section 7 (author attribution, marking of modified versions, no use
of the project's name and logo for forks) — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
You may use, study, share and modify it; anything you distribute based on it must stay open source
under the same terms and keep the author credit.
