# Drawify

A free AR tracing app for Android. Pick an image from your gallery and Drawify shows it
semi-transparently over the camera feed, so you see the paper, your hand and the image at the
same time and can trace its lines.

## Install

1. Open **Releases** in this repo and download `drawify.apk` from the newest `android-b<N>` release.
2. On the phone, allow installing from unknown sources and install the APK.
3. After that the app updates itself: when a newer build is published, a banner at the top says
   “New version · build N” – tap **Install**, it downloads the APK and opens Android's installer.
   The first time, Android asks to allow “Install unknown apps” for Drawify.

## Usage

- **Image** – pick an image from the gallery (or share one to Drawify from any app).
- Drag with one finger, pinch to zoom and rotate with two.
- **Opacity** – slider at the bottom.
- **Filter** – *Original*, *No white* (white becomes transparent, only the lines stay) or
  *Outline* (edge detection, good for photos).
- **Tilt** – for when the phone can't be held straight above the paper (e.g. propped up at 45°).
  Lay the paper flat on the table and aim the phone at it: the motion sensor measures the tilt and
  the image is warped so it looks like it lies flat on the paper. The current angle is shown at
  the top. Locking the image (🔒) freezes the correction while you draw.
- **Corners** – manual perspective: drag the 4 corner dots onto the corners of your paper (or drag
  inside the image to move it). Use this to fine-tune Tilt or when the paper isn't flat on a table.
- **Mirror** – flip the image horizontally. **Reset** – center the image again.
- Top bar: 👁 hide the image temporarily, ⚡ flashlight, 🔒 lock the image so it can't move.

Tip: keep the phone still (e.g. lean it on a glass or use a stand) – the image is fixed to the
screen, not tracked on the paper.

## Build & releases

`.github/workflows/build.yml` runs the unit tests and builds the APK. On every push to `main` it
publishes a **signed** release APK as a GitHub Release tagged `android-b<N>`, where
N = `versionCode` = the workflow run number (always increasing). The release notes are the commit
subject (or the `notes` input of a manual run); their first line is shown in the update banner.
Pull requests only get a test build signed with a throwaway debug key (uploaded as an artifact).

The app reads `https://api.github.com/repos/stefanruvceski/drawify/releases` without a token, so
**the repo must be public**. Update checks are off in debug builds.

### Signing (one-time)

All releases must be signed with the same key, otherwise Android refuses to install an update over
the installed app. The keystore is never committed. Create it and store it as GitHub secrets with:

```sh
./scripts/setup-signing.sh
```

This creates `~/.drawify-signing/` (keystore + password – back it up!) and sets the secrets
`DRAWIFY_KEYSTORE_BASE64` and `DRAWIFY_KEYSTORE_PASSWORD`. Without them, pushes to `main` fail
instead of publishing an unsigned or differently signed APK.

Locally: `./gradlew assembleDebug` / `./gradlew testDebugUnitTest` (needs the Android SDK and JDK 17).
