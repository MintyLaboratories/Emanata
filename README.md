<img src="screenshots/icon.png" alt="ComicReader" width="96" align="right" />

# ComicReader

A clean-room, ad-free comic / PDF reader for Android, written in Kotlin.

This is an **original implementation** built to match the *feature set* of a
reference comic reader — not a copy of its code. No ads, no analytics, no
in-app billing, and no network access.

## Download

Get the latest signed APK from the [**Releases**](https://github.com/infinity6901/ComicReader/releases/latest) page.

## Screenshots

| Library | Chapters | Reader — paged |
|---|---|---|
| ![Library](screenshots/library.png) | ![Chapters](screenshots/chapters.png) | ![Reader paged](screenshots/reader-paged.jpg) |

| Reader — long strip (webtoon) | End of chapter | Reader settings |
|---|---|---|
| ![Long strip](screenshots/reader-longstrip.jpg) | ![End of chapter](screenshots/end-of-chapter.jpg) | ![Reader settings](screenshots/reader-settings.png) |

| Two-page spread (RTL manga) | Favorites | History |
|---|---|---|
| ![Manga spread](screenshots/manga-spread-rtl.jpg) | ![Favorites](screenshots/favorites.png) | ![History](screenshots/history.png) |

| Dark theme — settings |
|---|
| ![Settings dark](screenshots/settings-dark.png) |

## Features

### File browser
- Browse local storage by folder; list and grid layouts. **Grid columns** and
  **Grid rows** (menu → View, or Settings) size the cells; rows share the height
  below the Recent row and above the floating button.
- Covers are made at the exact size of their cell (cropped and Lanczos3-scaled,
  drawn 1:1) and cached losslessly.
- Tabs for **Browse**, **Favorites**, **History** and **Bookmarks**.
- A **Recent** row sits above the browsing list and opens the folder holding the
  last chapter you read.
- Sort by name / size / date / type (natural number ordering, so `page2` sorts
  before `page10`), ascending or descending.
- Incremental search within the current folder.
- Favorites (star) and reading history, with jump-back to the last page read.
  Every history entry shows its **parent folder below the name**, so bare
  chapters like "Chapter 1" stay recognisable. Tapping a history entry opens
  the folder that contains that chapter.
- Long-press a file or folder to favorite it, set it as the home folder, open its
  location, rename, mark read/unread or delete it.
- Breadcrumb path bar, quick favorite button, back button navigates up (clamped
  at the root of internal storage).
- Folder rows show "N items · M read"; a folder whose items are all read gets the
  read badge. **Continue reading** jumps to the next unfinished book in a folder,
  and a whole folder can be marked read or unread.
- **Search subfolders** (menu) looks below the current folder, not just in it.
- Opening a book resumes at the last page unless it was already finished.
- Files opened from other apps (`content://`) are copied to the cache and opened.
- Housekeeping: **duplicate file finder** (content-checked), **largest files** list,
  reading **statistics**, and **backup / restore** of your library and settings
  (Settings → Your data) to a JSON file you choose.

### Reader
- **Formats:** images (`jpg`, `jpeg`, `png`, `gif`, `bmp`, `webp`, `avif`,
  `heic`/`heif`, `jxl`) and archives `zip`/`cbz`, `rar`/`cbr`, `7z`/`cb7`, plus
  `pdf`. The extension decides which images are listed; their content decides
  how they are decoded.
- **Broken pages** show a placeholder naming the page and the reason.
- **Archives:** recognised by content, so a `.cbr` that is really a zip opens.
  Pages in subfolders keep folder order. Archives inside an archive (one per
  issue) show their pages in place. macOS, hidden and empty entries are skipped.
- **Page modes:** paged (horizontal swipe), vertical (one page per screen) or
  **long strip** (continuous webtoon scrolling, no gaps).
- **Two-page spread:** facing pages side by side (respects RTL ordering).
- **Page thumbnails:** grid picker to jump straight to any page.
- **Crop:** crop the current page to a PNG.
- **Reading direction:** left-to-right or right-to-left (left/right tap zones
  follow unless mirroring is turned off).
- **Tap zones:** left/right, right/left, top/bottom, bottom/top or off; the outer
  thirds turn the page (a whole spread in two-page mode), the middle third
  toggles the UI.
- **Zoom / pan:** pinch-to-zoom, drag to pan, double-tap to zoom or reset.
  While moving, the page is drawn through a Lanczos3 GPU shader with an
  anti-ringing clamp (Android 13+; bilinear before). 150 ms after it stops,
  the visible part is re-rendered with Lanczos3 from the full-resolution page
  (vector for PDF) at exact screen resolution and swapped in pixel for pixel.
  Only the zoomed page or spread is kept at full resolution, within a memory
  budget, and it is freed when the reader is hidden.
- **Fit modes:** fit screen, fit width, fit height, stretch. Each page is
  scaled with Lanczos3 to the exact size it fills on screen (a spread half, or
  the whole row for a lone page) and drawn pixel for pixel, unfiltered.
- **Page filter:** none, invert, grayscale, high contrast, sepia. Invert flips
  lightness only, in OKLab, so colors keep their hue (Android 13+; an RGB
  invert before).
- **Auto-scroll** with adjustable speed (continuous in vertical / long-strip modes,
  timed page turns in paged mode); touching the screen takes over.
- **Auto-crop** trims blank page borders; pages ahead are **preloaded**.
- **Chapter list** to jump to any chapter in the folder.
- **Share** or **save** the current page as an image.
- **Per-folder layout:** "Remember layout for this folder" stores mode, direction, fit
  and spread for that folder only.
- **Adjustments:** brightness, contrast and saturation, each -100..+100, computed
  in OKLab on the GPU in the same pass as the page's scaling (Android 13+), live
  while dragging. Brightness blends toward black or white, contrast scales
  lightness about mid-gray, saturation scales chroma within the sRGB gamut.
  Changes are per book with a reset to the defaults set in Settings. A **night**
  filter sits on top.
- **Brightness drag:** in paged mode, drag up for brighter, down for darker.
  Crossing 0 holds there for 15% of the screen height. A drag locks to its first
  direction: vertical never turns the page, horizontal never changes brightness.
- **Page spacing** slider for vertical and long-strip modes.
- **Next / previous chapter** buttons, plus an **end-of-chapter card** with a
  direct link to the next chapter.
- **Auto next chapter:** reaching the last page rolls straight into the next
  chapter and the end-of-chapter card is skipped entirely (it still appears
  when the setting is off, or on the last chapter of a folder). In **long strip**
  and **vertical** modes the next chapter's pages are already laid into the strip,
  so scrolling (or an upward flick) simply continues into them the way page-to-page
  scrolling does inside a single archive - no stop, no reload, no end card.
- Bookmarks per page, plus a bookmark list to jump between them.
- Go-to-page, orientation lock, rotate, keep-screen-on, immersive fullscreen,
  and tap-to-toggle the UI bars. Volume keys turn pages.
- A reader settings **bottom sheet** exposes every reader option in place, and
  carries the same settings as the 3-dots menu (Display / Page filter / Reading
  / Page actions), so both surfaces stay in sync.
- Resumes at the last page read. If **Ask where to start** is on, a chapter
  with saved progress asks first: *Resume page* or *First page*, with the page
  number and percentage. Chapters opened explicitly (history, bookmark,
  chapter list, next-chapter) never ask.
- Per-book reading position is stored in history.
- Every reader option is written back to preferences, so the mode, fit, filter,
  spacing, direction and toggles **persist across chapters and app restarts**.

### Settings
- Theme: system / light / dark.
- Per-app language: system, English, 한국어, 日本語, Русский.
- Archive filename charset (UTF-8, CP949, Shift_JIS, GBK, Windows-1251).
- Default reading direction, page mode, fit, filter, page spacing.
- Default adjustments (brightness, contrast, saturation) for every book.
- **Linear-light scaling** (off by default): pages and covers are shrunk in
  linear light, so halftones and screentones keep their brightness; thin dark
  lines come out lighter. Enlarging stays in sRGB.
- Reader: resume where I left off, ask whether to resume or start from the
  first page, and open the next chapter automatically when a chapter ends.
- Gestures (tap zone layout and right-to-left mirroring, volume keys, brightness
  drag, double-tap zoom).
- File-list options (file names, status bar, breadcrumb, quick button).
- Clear history, clear bookmarks, clear thumbnails, reset all settings.

## Format support mapping

| Extension | Handler | Library |
|---|---|---|
| jpg/jpeg/png/gif/bmp/webp/avif/heic/heif | `ImageSource` | platform `ImageDecoder` (`BitmapFactory` below API 28) |
| jxl | `ImageSource` | jxl-coder (libjxl) |
| zip / cbz | `ArchiveSource` + `ZipReader` | `java.util.zip` |
| rar / cbr | `ArchiveSource` + `RarReader` | junrar 8.x (RAR4 and RAR5/7) |
| 7z / cb7 | `ArchiveSource` + `SevenZipReader` | Apache Commons Compress |
| pdf | `PdfSource` | platform `PdfRenderer` |

- The backend is picked from the file's magic bytes (`ArchiveKind`), the
  extension only when they match nothing.
- Every image, in an archive or not, decodes through `Decoders`, which picks the
  decoder from the magic bytes (`ImageKind`). PNG and JPEG XL sizes come from
  the header alone (`ImageHeaders`).
- Images decode at full resolution, then Lanczos3 (`Lanczos`, pure Kotlin,
  parallel) scales them to the exact size they are shown at. Only a source over
  50 MP is first reduced by the decoder's own power-of-two sampling, to bound
  memory.
- Pages sort naturally per path component (`NaturalOrder`): `156` < `156b` <
  `157`, padding ignored, a folder before its longer-named siblings.
- Nested archives (up to two levels) are copied to `cacheDir/nested`
  (`NestedArchives`): reused across opens, least recently used evicted past
  2 GiB (never while a book holds them), inner archives over 1 GiB or past the
  per-book budget skipped. They sort by file name among their siblings. Covers
  copy only the inner archive holding the first page.
- A copy that fails for lack of storage fails the whole open, so a book never
  opens with pages missing. Copies stop when the open is cancelled; the
  long-strip prefetch uses cached copies only.
- At most four inner archives stay open per book; others reopen from their
  copies when read.

## Project layout

```
app/src/main/java/com/infinity/comicreader/
  App.kt                     application; applies theme + locale
  data/                      Prefs, Library (history/bookmarks/favorites),
                             Thumbs, models
  reader/                    PageSource implementations + factory, Filters,
                             Decoders (image decoding), BitmapUtils
  ui/                        ListActivity, ViewerActivity, Settings*, AboutActivity,
                             CropActivity + CropView, PagesActivity + ThumbAdapter,
                             ZoomImageView, PageAdapter, BookAdapter
  util/                      Formats, NaturalOrder, FileUtils/Locales
screenshots/                 README screenshots
```

## Building

Requirements: JDK 17+ (JDK 21 verified) and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug
./gradlew assembleRelease   # signed + R8-minified
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`, the
release APK to `app/build/outputs/apk/release/app-release.apk`.

On Windows, if JDK 21 is not on PATH:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleRelease
```

### Release signing

`app/build.gradle.kts` reads `keystore.properties` from the project root and, if
present, signs the release build with `keystore/release.jks`. Both files are
listed in `.gitignore` and **must not be committed**. If `keystore.properties` is
absent the release build still compiles, just unsigned.

Release uses `isMinifyEnabled = true` + `isShrinkResources = true` (R8). The
proguard rules keep junrar and commons-compress whole so the reflection paths in
the archive backends survive minification.

### Prebuilt outputs

`dist/` holds the built artifacts:

| Artifact | Size | Notes |
|---|---|---|
| `ComicReader-1.3-foss-release.apk` | 2.63 MB | v2-signed, R8-minified, offline |
| `ComicReader-1.3-debug.apk` | 7.69 MB | debuggable, unsigned-config |
| `ComicReader-1.2-foss-release.apk` | 2.62 MB | v2-signed, R8-minified, offline |
| `ComicReader-1.2-debug.apk` | 7.53 MB | debuggable, unsigned-config |

### Install on a connected device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Note: the debug and release APKs use different signing keys, so installing one
requires uninstalling the other.

## Permissions

- `READ_MEDIA_IMAGES` (Android 13+) / `READ_EXTERNAL_STORAGE` (≤ Android 12)
- `MANAGE_EXTERNAL_STORAGE` — to browse folders anywhere on internal/external
  storage. The app prompts for "All files access" on first launch.

No internet permission is requested.

## Notes

- `minSdk 26`, `targetSdk 34`.
- AVIF decoding depends on the platform: it works natively on Android 12+
  (API 31+); on older devices AVIF pages show the error placeholder.
- Zoom: `LanczosShader` (AGSL) draws while the gesture moves; `DetailTile`,
  `DetailStore` and `PageSource.loadDetail` re-render the settled view.
- The archive/PDF backends (`SevenZFile`, junrar `Archive`, `PdfRenderer`) keep
  per-instance decode state and are **not thread-safe**. Because the page grid
  loads the current page and its neighbours concurrently, all access to these
  backends is serialized on the reader or source instance (`@Synchronized`).
- Page/thumbnail decode failures are logged (`ArchiveSource`, `PdfSource`,
  `PageAdapter`, `ThumbAdapter`, `ViewerActivity`) instead of being silently
  swallowed.
- The file browser reloads the current folder on resume, so files added or
  removed outside the app appear without a manual refresh.

## License

Released under the [MIT License](LICENSE).
