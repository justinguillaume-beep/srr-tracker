# SRR Tracker

Two apps, same job: watch a pair of dice and keep the sevens-to-rolls ratio (SRR).

- **Android app** in `android/` — phone sits face-down over the table and logs each throw by itself. This is the one to install.
- **Web app** at the repo root — the original browser version. It is unchanged.

## Install the Android app

Each push to `main` builds a debug APK and publishes it on the `latest` release.

1. On the phone, open this link in Chrome:
   `https://github.com/justinguillaume-beep/srr-tracker/releases/download/latest/srr-tracker-debug.apk`
2. If Android blocks the download, allow it: **Settings → Security** (or **Apps → Special app access**) → **Install unknown apps** → turn it on for Chrome (or Files).
3. Open `srr-tracker-debug.apk` and tap Install. If Android says **App not installed** and SRR Tracker is already on the phone, uninstall that copy once, then install this file. Copies built before the shared debug key cannot be updated in place. After this install, downloading the same link again updates the app without another uninstall.
4. Open **SRR Tracker**. Allow the camera. Follow the three setup screens: the phone lies face-down, and the dice should sit inside the box on screen.
5. Tap **Start**. Put the dice in the box, or throw them. The box turns green when it sees them. The status line then says **Dice seen**, **Holding still...**, **Capturing...**, **Counting...**, and **Logged 7** (or whatever the total is). Dice that are already sitting in the box are counted too. You do not have to throw them first. Sevens in the list are red.
6. If it cannot read the pips, it opens a check screen and tells you why. Tap the two numbers and save. **Count now** takes a photo immediately. **Undo last roll** removes the newest one. Tap a roll to see its photo, fix a number, or delete it. A roll the camera could not read stays in the list as **unread** so you can tap it and enter the dice.
7. Drag the box onto the two dice you are throwing, and drag a corner to resize it. The camera only looks inside that box. If more than two dice are inside it, the status says how many it found instead of logging a guess. Settings has **Reset box**, **Beep on each roll** (off at first), **Save a marked photo**, and **Save each die crop**. There is no vibration.

The APK is a debug build signed with the keystore in `android/app/debug.keystore`, the same key on every build. Each GitHub build gets a higher `versionCode` (the workflow run number), so a newer download can replace the installed app. `minSdk` is 26 and the APK includes arm64-v8a, armeabi-v7a, x86, and x86_64. Updating means downloading that same link again (the `latest` release is replaced on every push to main).

### What the Android app does

The back camera stays on. When dice are in the box and have been still (default 0.5 s), it takes the sharpest practical photo (up to 4K). A throw works, and so does setting the dice down or leaving them already resting in the box. After a roll it waits until the dice leave, a new throw lands, or the dice that are sitting there have clearly changed. **Count now** takes a photo if you do not want to wait. Small dice are found, cropped, and enlarged before the pips are counted, so a die only about 40–80 pixels wide in a 1080p frame can still be read. A clear read is saved on its own and the status line says what was logged. A doubtful or failed read opens the check screen and says why. Every roll stores a smaller JPEG on the phone, with the time. The beep is off until you turn on **Beep on each roll** in Settings. The phone does not vibrate.

SRR is rolls per seven, shown as `1:6.0`. A fair pair of dice is `1:6.0`. The percent under it is how often a 7 showed up.

### Build it yourself

You need a JDK 17 and the Android SDK.

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Unit tests cover the SRR math, the motion gate (a throw, dice already resting, dice placed without a motion spike, a new placement after the box is empty, and a changed arrangement), and pip counting.

On 18 synthetic photos at 1920×1080, with die sides of 40, 45, 50, 55, 60, 70, 80, and 90 px (white dice with dark pips and red dice with light pips, a few with a lighting gradient, one pair almost touching), the Android counter got **18/18 totals right and 18/18 faces exact**, all at high confidence. That includes every 40 px case (1+1, 1+6, 2+5, 3+4, 6+6, and a red 2+2). These are clean drawings, not photos of real casino dice.

### Limits

Justin's dice are small translucent purple dice and one amber die, with white pips, on grey cloth. Teal foam and the clutter beside the cloth are not counted as dice. Drag the box so only the two dice in use sit inside it. If the box holds more than two, the status says how many were found and nothing is logged as a pair.

White pips are counted as round blobs of similar size inside the top face. A read is saved on its own only when both dice match a legal face and the blobs are inset, even in size, and not a lone glare spot. On the four table photos that means:

- `0f574bd7…` reads **2 and 1** and is logged.
- `156f4bdb…` is Justin's **6 and 6**, but the saturated pixels are one or two blobs, so the guess stays unread.
- `3643033e…` finds all six dice. The amber die reads **6**. The other faces are not reliable enough to log, including when the box is tightened around two of them.
- `0cb28426…` finds all four dice. The amber die's bright edge is still counted with the pips, so that face is not logged.

**Save each die crop** (on by default) stores a JPEG of every die next to the roll, so a wrong face can be sent back. The four table photos are in the unit tests. The app has not been run on a physical phone in this environment. Nothing is uploaded.

---

# Web app (PWA)

Plain HTML/CSS/JS, no build step, no dependencies, fully offline after first load.

Files: `index.html`, `style.css`, `app.js` (UI, camera, IndexedDB), `detector.js` (pip-counting CV), `stats.js` (SRR math + CSV),
`sw.js` (service worker), `manifest.webmanifest`, `icons/`. `tests/` holds the synthetic-dice generator + test runners (not needed to run the app).

## Use
1. Open the HTTPS URL on your phone, allow the camera (rear camera is requested). Mount/hold the phone pointing straight down at the landing spot.
2. Tap **CAPTURE ROLL** (or tap the preview). The photo is read on-device; check the detected dice, tap a 1-6 button under either die to fix it, then **Log roll**.
   (Settings -> auto-accept logs it after N seconds if you don't touch anything. Off by default; by default it only auto-accepts "high" confidence.)
3. Main screen: roll count, overall SRR as `1:6.0` (rolls per seven) plus sevens/roll (`0.167`), and a list (newest first) of every total, 7s in red, with the running SRR after each roll.
4. Tap any roll: saved photo with detected pips circled, fix either die, or delete.
5. Menu (...): new session, all sessions, manual entry (no photo), CSV export (this session / all), settings, reset session.

## Get it on your phone (camera needs HTTPS)
Simplest free option: **GitHub Pages**
1. Create a free GitHub account/repo (e.g. `srr-tracker`, public).
2. "Add file -> Upload files": drag in the *contents* of this folder (index.html, app.js, ... and icons/). Commit.
3. Repo -> Settings -> Pages -> Source: "Deploy from a branch", Branch `main` / `(root)` -> Save.
4. After ~1 minute open `https://<your-username>.github.io/srr-tracker/` on the phone.
5. Install: Android Chrome menu -> "Install app"/"Add to Home screen"; iPhone Safari Share -> "Add to Home Screen". Open it once online; after that it works offline.

Alternative: Netlify Drop (app.netlify.com/drop) — drag the folder in the browser, get an https URL (create a free account to keep the site).
For local testing: `python3 -m http.server 8000` in this folder and open `http://localhost:8000` on that same computer (localhost counts as secure);
to test from a phone use a tunnel (e.g. `cloudflared tunnel --url http://localhost:8000`, `ngrok http 8000`) which gives an https URL.

Updating: bump `CACHE` in `sw.js` (e.g. `srr-tracker-v2`) whenever you change files, otherwise installed copies keep the cached version.

## Notes on accuracy
Pip counting is classical computer vision (top-hat blob detection + die-face template fit), tuned on synthetic images only. It works best with
good even light, camera straight down, dice that contrast with the table, and dice filling a decent part of the frame. Always confirm the total
on the review screen. Yellow/cyan circles show what it counted; "low confidence" means double-check.

Data lives in IndexedDB/localStorage in this browser only. Clearing site data deletes it — export CSV regularly.

## Tests
- `node tests/stats.test.js` — SRR math / CSV.
- `tests/gen.py` (Python PIL+numpy) makes synthetic dice photos + labels; `tests/par.sh` / `tests/run_detect.js` run the detector on them (Node).
- `tests/e2e.js` — headless Chrome with a fake camera (puppeteer-core); paths inside are for the box it was written on.
