# Storage Cleaner for Android

[![Build](https://github.com/Anujpal-15th/android-storage-cleaner/actions/workflows/build.yml/badge.svg)](https://github.com/Anujpal-15th/android-storage-cleaner/actions/workflows/build.yml)
[![Download](https://img.shields.io/github/v/release/Anujpal-15th/android-storage-cleaner?label=download%20APK)](https://github.com/Anujpal-15th/android-storage-cleaner/releases/latest)

A free, open-source Android app (Android 8+, Kotlin + Jetpack Compose) that shows **what is filling your phone** and **what is actually safe to delete**. It is the mobile companion to the Windows [Storage Cleaner](https://github.com/Anujpal-15th/storage-cleaner).

Every item gets a safety level and a plain-English note:

| Level | Meaning | Examples |
|---|---|---|
| 🟢 **Safe** | Rebuildable: apps recreate it | Gallery thumbnails, temp/log files, cache folders, viewed WhatsApp statuses, empty folders |
| 🟡 **Review** | Deletable, but you lose something (the note says what) | Chat media, old APK installers, old downloads, duplicates, large files, leftovers, LOST.DIR |
| 🔴 **Protected** | Never deleted | Whole standard folders (DCIM, Pictures, Android/media…), `.nomedia` files |

## Features

- **Clean**: storage overview with a breakdown by file type (images, videos, audio, documents, archives), then Safe and Review items, each expandable to see the exact files.
  - Gallery thumbnail cache, temp and log files, cache folders in shared storage, app caches (Android 10 and below).
  - Chat media per folder for WhatsApp, WhatsApp Business and Telegram; viewed statuses are Safe.
  - Old APK installers, each checked against what's installed ("Already installed, installer not needed").
  - Duplicates (same size → same first 64 KB → same SHA-256); always keeps the oldest copy.
  - Large files, downloads untouched for 30+ days, empty folders.
  - **Leftovers**: `Android/media`, `data` and `obb` folders of apps that are no longer installed and unused for 30+ days.
- **Apps**: every app's size and cache, apps not opened for 30+ days, and one tap to that app's *Clear cache* page or uninstall. Android only lets an app's own settings page clear its cache.
- **More**: weekly automatic cleanup of Safe items only, while charging (WorkManager), a cleanup history, and the safety rules.

## How it stays safe

- Never follows symbolic links.
- Never deletes a whole standard folder, the storage root, or `.nomedia` markers, whatever an item says.
- Duplicates always keep one copy; leftovers must be untouched for 30 days.
- Empty-folder cleanup removes folders only: a file added after the scan is never deleted.
- Shows every selected item, and every Review note, before deleting. Nothing leaves your phone.

## Build

1. Open this folder in **Android Studio** (it downloads Gradle and the Android SDK).
2. *Run* on a device, or *Build → Build APK(s)*.
3. On first launch, grant **All files access** (to scan) and, on the Apps tab, **Usage access** (for app sizes).

Scanner tests run on a plain JVM, no device needed: right-click `app/src/test/.../ScannerTest` → *Run*.

## Code

| File | What |
|---|---|
| `Scanner.kt` | Pure Kotlin scanner, safety rules, delete guard and history (no Android APIs, so it is unit-tested on the JVM) |
| `Platform.kt` | Permissions, per-app storage stats, APK checks, weekly `CleanWorker` |
| `MainActivity.kt` | ViewModel and Compose UI (Clean / Apps / More) |

## License

[MIT](LICENSE). Provided as-is, without warranty: review what you delete.

## CI

Every push compiles, runs the unit tests and builds a signed APK ([Actions](https://github.com/Anujpal-15th/android-storage-cleaner/actions)). Each run's page shows a summary table with every compile error or failed test as `file:line`, and errors are annotated on the code. From a terminal:

```bash
gh run watch                       # follow the latest run
gh run view --log-failed           # only the failing step's log
gh run download -n apk             # get the APK a run built
```

Releasing: `git tag v1.2.0 && git push origin v1.2.0` builds, signs and publishes the release with the APK.
