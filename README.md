# SRR Tracker (dice-control practice PWA)

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
