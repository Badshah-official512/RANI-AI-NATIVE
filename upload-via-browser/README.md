# Upload via browser — RANI-AI-NATIVE

The GitHub connector **cannot** push these files (binaries are rejected, and
`.github/workflows/*` returns 403). Upload them via github.com web upload
(Add file → Upload files) to the paths listed below. Everything else in the
repo goes through the connector.

| File in this folder | Upload to (repo path) |
|---|---|
| `.github/workflows/android-build.yml` | `.github/workflows/android-build.yml` |
| `android/gradle/wrapper/gradle-wrapper.jar` | `android/gradle/wrapper/gradle-wrapper.jar` |
| `android/app/src/main/res/drawable/ic_launcher_fg.png` | `android/app/src/main/res/drawable/ic_launcher_fg.png` |
| `android/app/src/main/res/drawable-nodpi/rani_face.png` | `android/app/src/main/res/drawable-nodpi/rani_face.png` |
| `android/app/src/main/res/drawable-nodpi/rani_poster.jpg` | `android/app/src/main/res/drawable-nodpi/rani_poster.jpg` |
| `android/app/src/main/res/drawable-nodpi/rani_creator.jpg` | `android/app/src/main/res/drawable-nodpi/rani_creator.jpg` |
| `android/app/src/main/res/mipmap-mdpi/ic_launcher.png` | `android/app/src/main/res/mipmap-mdpi/ic_launcher.png` |
| `android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | `android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png` |
| `android/app/src/main/res/mipmap-hdpi/ic_launcher.png` | `android/app/src/main/res/mipmap-hdpi/ic_launcher.png` |
| `android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | `android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png` |
| `android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` | `android/app/src/main/res/mipmap-xhdpi/ic_launcher.png` |
| `android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | `android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` |
| `android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | `android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` |
| `android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | `android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` |
| `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` |
| `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` |

Order: upload the workflow **last** — CI starts on the push that adds it, and
the build needs the icons + wrapper jar in place first. After upload, check
Actions → Android Build → the run for branch `main`.
