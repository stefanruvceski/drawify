# Drawify

A free AR tracing app for Android. Pick an image from your gallery and Drawify shows it
semi-transparently over the camera feed, so you see the paper, your hand and the image at the
same time and can trace its lines.

## Install

1. Open **Releases** in this repo and download the newest `drawify-*.apk`.
2. On the phone, allow installing from unknown sources and install the APK.
3. Every new build installs over the previous one (same signing key).

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

## Build

GitHub Actions (`.github/workflows/build.yml`) builds the APK on every push and publishes it to
Releases. Locally: `./gradlew assembleRelease` (needs the Android SDK and JDK 17).
