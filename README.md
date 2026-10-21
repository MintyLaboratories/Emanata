<img src="screenshots/icon.png" alt="Emanata Comic Reader" width="96" align="right" />

# Emanata Comic Reader

An ad-free, offline comic & PDF reader for Android, focused on image quality,
features and customizability.

- **Formats:** CBZ / CBR / CB7 / CBT / PDF, folders of images; WebP, JPEG XL,
  AVIF, TIFF and more.
- **Rendering:** Lanczos3 scaling everywhere, including GPU Lanczos3 while
  zooming (Android 13+).
- **Private:** no ads, no analytics, no tracking; clean-room Kotlin.
- **No required connection:** works offline. No internet permission is
  requested; network features, once added, will use the network only for what
  you explicitly set up, never in the background.

Based on [ComicReader by infinity6901](https://github.com/infinity6901/ComicReader) (MIT).

## Download

Get the latest APK from the [**Releases**](https://github.com/MintyLaboratories/EmanataComicReader/releases/latest) page.

The app id is `minty.emanata` (debug: `minty.emanata.debug`), so it installs
beside ComicReader rather than over it. To bring your library across, back it up
in ComicReader (Settings → Your data) and restore the file in Emanata.

## Screenshots

> Outdated: these show upstream ComicReader and predate Emanata's changes.

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
- Browse local storage by folder; list and grid layouts.
- Each tab keeps its own view (menu → View changes the current tab; Settings →
  Library has one entry per tab). Browse and Favorites start as grids, History
  and Bookmarks as lists.
- **Grid dimensions:** columns × rows, each Auto or 1–6, picked on a 7 × 7 table
  (tap a cell, or drag and release). Rows share the height below the Recent row
  and above the floating button.
- **Title display:** Hidden, Single line (… at the end), Single line, middle
  (… in the middle, so the year or number stays), Double line (default), Bounce
  (glides to the end, rests, glides back) or Marquee (loops leftward). Only
  overflowing titles move, at about 30 dp/s, all in sync, paused while
  scrolling, with soft edges; with system animations removed they hold still,
  cut in the middle. **Shorten repeated prefixes** (on by default) leaves out
  the series name all items of a Set share (`Lumen - 001.cbz` shows as `001.cbz`).
- **Detail display** (per tab): Hidden, Comfortable (details on their own line)
  or Dense (right-aligned at the end of the title's last line, in small gray
  tabular digits: folders `12`, `3/12`; comics `192 · 87 MB`, `40/192 · 87 MB`;
  bookmarks their page).
- **List density** (per tab): list thumbnail size on a six-step slider, Tiny,
  Compact, Normal (default), Large, Extra large or Huge, applied live. A
  progress badge too wide for its thumbnail shows a percentage, or a dot.
- **Cover shape:** 2:3 (default), 3:4 or the cell's own shape; covers sit in the
  largest box of that shape that fits the cell, centered. **Cover spacing**
  (Settings, 0–32 dp, default 8) sets the gap between grid covers.
- Covers are made at the exact size of their cell (Lanczos3-scaled, drawn 1:1)
  and cached losslessly.
- **Thumbnail fit:** Fill crops the cover to its box; Fit shows the whole cover
  over a blurred, filled copy of itself; Auto (default) fills when the cover's
  shape is within 10% of the box's, else fits. **Crop anchor** keeps the top
  (default) or the center of a cover cropped for height.
- **Bookmarks** show the book's cover with the bookmarked page in front, at 80%
  of the tile, in the bottom right corner.
- **Wraparound covers** (front and back side by side, about twice as wide as
  the book's own inner pages) show their front: the half without the barcode,
  else the half the book starts on in its reading direction (right for
  left-to-right, left for right-to-left), one page wide so the spine stays out.
- Tabs for **Browse**, **Favorites**, **History** and **Bookmarks**.
- **Toolbar buttons:** Sort, Filter, Favorite (☆/★ for the folder browsed) and
  Search; the others step aside while searching. Settings → **Toolbar
  buttons** shows, hides and reorders them (checkboxes and drag handles); a
  hidden one stays in the menu.
- A **Recent** row sits above the browsing list and opens the folder holding the
  last chapter you read.
- **Sort** (toolbar, per tab): Name, Year, Added, Accessed, Size, Length,
  File type or Reading direction, each named by its direction (`newest first`);
  tap the selected option to flip it. Changes apply live; tap outside to close.
  Year, File type and Reading direction take a **Then by** level; Name A → Z
  (natural order, so `page2` sorts before `page10`) breaks every tie. Items
  without a value sort last. **Folders first** is on by default. Browse and
  Favorites start by name, History and Bookmarks by last access (adding a
  bookmark counts). Page counts, ComicInfo years, detected directions and
  folder totals are found in the background; page counts fill in without moving
  rows. A book with inner archives not yet copied out takes ComicInfo's
  `PageCount`.
- **Filter** (per tab): file type, reading direction, read status, year and
  length ranges. A **Filtered** chip shows while anything is hidden; tap it to
  edit, × to clear. While browsing, folders face only the file type rule, so
  the books below them stay reachable. A folder's read status comes from the
  books directly in it; a folder of loose page images counts them as its length.
- **Peek:** spread two fingers on a comic and a grid of its pages grows out of
  it; pinch back to collapse, release part-way to keep it open (scroll, tap a
  page to open there), release fully spread to open the book. On a folder the
  grid shows the covers inside (tap one to open it; fully spread opens the
  folder). Settings → **Pinch to peek**: Off, Peek or Peek & open (default);
  also in the long-press menu.
- Page grids show each page's **preview** at once, then the exact page in its
  place. Previews (256 px) are kept on disk whenever a page is decoded, for any
  reason; a page never decoded gets a quick one where its format allows (JPEG
  at 1/8 scale, WebP scaled while decoding, AVIF from an embedded thumbnail or
  a layered file's small first layer, Android 12+). Folder peeks reuse the library's
  covers and make missing ones nearest the middle of the view first.
- Incremental search within the current folder.
- Favorites (star) and reading history, with jump-back to the last page read.
  Every history entry shows its **parent folder below the name**, so bare
  chapters like "Chapter 1" stay recognizable. Tapping a history entry opens
  the book as **Resume where I left off** says; its long-press menu adds
  **Show in folder** next to **Continue reading**.
- **Favorites** are grouped into collapsible **Libraries**, **Sets** and
  **Comics** by what they hold (a folder of comics is a Set, comics two or more
  levels down make a Library); "Group favorites" turns this off. Favorites with
  the same name get a grayed prefix that tells them apart (`stacks/Comics`,
  `books…Comics`, `Device…Comics`).
- **Startup tab** and **startup location** (last seen, first or most recent
  favorite folder, or a custom folder picked from the favorites).
- Long-press a file or folder to favorite it, make a folder the startup folder,
  show it in its folder, rename, mark read/unread or delete it.
- Location bar below the tabs as tappable crumbs (`Device / Comics / Series`), roots
  named (`Device`, the SD card's label), at least 48 dp tall; back navigates up,
  never above a root. Going up keeps the deeper folders as gray **forward
  crumbs**; opening another folder drops them.
- **Locations:** the crumbs start with the app's icon in the theme's color
  (`[icon] / Device / Comics`); it opens a sheet of every
  location with its free space: the device, SD cards and **USB-OTG drives**
  (Android 11+, with all-files access). Drives appear and disappear live;
  pulling one out while a book on it is open closes the book and says why.
- Folder rows show "N items · M read"; a folder whose items are all read gets the
  read badge. **Continue reading** jumps to the next unfinished book in a folder,
  and a whole folder can be marked read or unread.
- **Search subfolders** (menu) looks below the current folder, not just in it.
- Opening a book resumes at the last page unless it was already finished.
- Files opened from other apps (`content://`) are copied to the cache and opened.
- Housekeeping: **duplicate file finder** (bit-identical copies, SHA-256
  verified, shown as one cover with every place a copy is; spread two fingers
  on it to peek), **largest files** list,
  reading **statistics**, and **backup / restore** of your library and settings
  (Settings → Your data) to a JSON file you choose.

### Reader
- **Formats:** images (`jpg`, `jpeg`, `png`, `gif` (first frame), `bmp`, `webp`,
  `avif`, `heic`/`heif`, `jxl`, `tif`/`tiff` (first page)) and archives
  `zip`/`cbz`, `rar`/`cbr`, `7z`/`cb7`, `tar`/`cbt`, plus `pdf`. The extension
  decides which images are listed; their content decides how they are decoded.
- **Broken pages** show a placeholder naming the page and the reason.
- **Archives:** recognized by content, so a `.cbr` that is really a zip opens.
  Pages in subfolders keep folder order. Archives inside an archive (one per
  issue) show their pages in place. macOS, hidden and empty entries are skipped.
- **Page modes:** paged (horizontal swipe), vertical (one page per screen) or
  **long strip** (continuous webtoon scrolling, no gaps).
- **Two-page spread:** Off, On or Landscape only; facing pages side by side
  (respects RTL ordering).
- **Page thumbnails:** grid picker to jump straight to any page.
- **Crop:** crop the current page to a PNG.
- **Reading direction:** left-to-right or right-to-left (left/right tap zones
  follow unless mirroring is turned off). **Detect reading direction** (on by
  default) reads a book right to left from, in order: ComicInfo
  `Manga=YesAndRightToLeft`; a wraparound cover's front half (found by its
  barcode); a manga publisher in ComicInfo `Publisher` or a bracketed tag in the
  file or page names (`[Kodansha Comics]`). Manga-style page names
  (`c001 (v01) - p000`) do not decide on their own, as western scans use them
  too. A short note on open says why ("Right-to-left (from ComicInfo)").
  Choosing a direction keeps it for the book's folder, and only the direction;
  a folder's direction always wins over detection. **Auto** hands it back to
  detection and the default.
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
- **Chapter drawer** (toolbar button, or Chapters in the menu): *Chapters in
  this book* (its folders, or the inner archives of a nested book) and *In this
  folder* (the files beside it), the current ones highlighted; tap one to go there.
- **Page actions** (Page ▸ in the menu, or a long-press on a page): share or
  save the page as an image, crop it, or rotate the screen.
- **Apply to: This folder / All books** at the top of the reader settings:
  *This folder* keeps the whole layout (mode, direction, fit, spread, auto-crop,
  page spacing) for the book's folder; *All books* changes the defaults and
  keeps only a direction chosen for the folder. Layouts
  remembered by older versions that matched the defaults keep only their direction.
- **Book info:** file, folder, format (from the file's leading bytes, e.g.
  "CBR (ZIP)"), size, pages, the reading direction with where it comes from, and
  every ComicInfo field.
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
  direct link to the next chapter, centered at 75% of the screen height.
- **Auto next chapter** (on by default): reaching the last page rolls straight
  into the next chapter and the end-of-chapter card is skipped entirely (it still appears
  when the setting is off, or on the last chapter of a folder). In **long strip**
  and **vertical** modes the next chapter's pages are already laid into the strip,
  so scrolling (or an upward flick) simply continues into them the way page-to-page
  scrolling does inside a single archive - no stop, no reload, no end card.
- **Previous chapter on repeated back** (Disabled, 2 or 3 swipes; default 2):
  going back on a chapter's first page (swipe, tap zone, volume key) shows
  *Swipe back again for the previous chapter*; the last one within 1.5 s of
  the one before opens the previous chapter at its last page or spread.
- Bookmarks per page (toolbar button), plus a bookmark list to jump between them.
- **Favorites ▸** adds the open comic, or the folder it is in, to the favorites,
  or removes it.
- Go-to-page, orientation lock, rotate, keep-screen-on, immersive fullscreen,
  and tap-to-toggle the UI bars. Volume keys turn pages.
- The **reader settings** sheet holds every reading option in three groups:
  Layout (page mode, direction, fit, two-page, auto-crop, page spacing), Color
  (filter, brightness, contrast, saturation, night filter) and Behavior (auto
  next chapter, auto-scroll speed, keep screen on, fullscreen, orientation).
  Each single choice is a row of segmented buttons with one always selected. It
  opens over half the screen, so changes show behind it.
- The overflow menu stays short: Go to page…, Chapters, Bookmarks, Favorites ▸,
  Auto-scroll, Page ▸, Book info, Settings.
- **Resume where I left off:** Ask (default), Yes or No. Ask offers *Resume
  page* or *First page*, with the page number and percentage; ticking
  *Don't ask again* turns the answer into Yes or No. Only a book with a saved
  position asks; a finished book opens at the first page. Chapters opened
  explicitly (history's Continue reading, bookmark, chapter drawer, next-chapter)
  never ask.
- Per-book reading position is stored in history.
- Every reader option is written back to preferences, so the mode, fit, filter,
  spacing, direction and toggles **persist across chapters and app restarts**.

### Settings
- Theme: system / light / dark.
- Per-app language: system or English (Simplified, en-US).
- Archive filename charset (UTF-8, CP949, Shift_JIS, GBK, Windows-1251).
- Default reading direction, page mode, fit, filter, page spacing.
- Default adjustments (brightness, contrast, saturation) for every book.
- **Linear-light scaling** (off by default): pages and covers are shrunk in
  linear light, so halftones and screentones keep their brightness; thin dark
  lines come out lighter. Enlarging stays in sRGB.
- Reader: resume where I left off (ask, yes or no), open the next chapter
  automatically when a chapter ends, and how many backs on a first page open
  the previous one.
- Gestures (tap zone layout and right-to-left mirroring, volume keys, brightness
  drag, double-tap zoom, and **momentum scrolling**, on by default: zoomed
  pages keep gliding after a flick and stop at their edges).
- File-list options (title display, status bar, breadcrumb, quick button).
- Clear history, clear bookmarks, clear thumbnails, reset all settings; every
  Clear or Reset asks first. Resetting settings keeps per-book adjustments and
  reading progress.
- About: version, source, upstream credit, the license and third-party licenses.

## Format support mapping

| Extension | Handler | Library |
|---|---|---|
| jpg/jpeg/png/gif/bmp/webp/avif/heic/heif | `ImageSource` | platform `ImageDecoder` (`BitmapFactory` below API 28) |
| jxl | `ImageSource` | jxl-coder (libjxl) |
| tif/tiff | `ImageSource` | built-in `Tiff` (strips/tiles; none, PackBits, LZW, Deflate; JPEG via the platform) |
| zip / cbz | `ArchiveSource` + `ZipReader` | `java.util.zip` |
| rar / cbr | `ArchiveSource` + `RarReader` | junrar 8.x (RAR4 and RAR5/7) |
| 7z / cb7 | `ArchiveSource` + `SevenZipReader` | Apache Commons Compress |
| tar / cbt | `ArchiveSource` + `TarReader` | Apache Commons Compress |
| pdf | `PdfSource` | platform `PdfRenderer` |

- The backend is picked from the file's magic bytes (`ArchiveKind`), the
  extension only when they match nothing.
- Every image, in an archive or not, decodes through `Decoders`, which picks the
  decoder from the magic bytes (`ImageKind`). PNG, JPEG XL and TIFF sizes come
  from the header alone (`ImageHeaders`, `Tiff`).
- Images decode at full resolution, then Lanczos3 (`Lanczos`, pure Kotlin,
  parallel) scales them to the exact size they are shown at. Only a source over
  50 MP is first reduced by the decoder's own power-of-two sampling, to bound
  memory.
- Pages sort naturally per path component (`NaturalOrder`): `156` < `156b` <
  `157`, padding ignored, a folder before its longer-named siblings.
- Page previews (`PreviewStore`) live in `cacheDir/previews` as JPEGs keyed by
  the book's path, size and time, the page and the preview size; least recently
  used evicted past 128 MiB. Every page source leaves one per loaded page
  (`PreviewingSource`), made on a background writer: JPEG and WebP from the
  page's bytes, other formats from the full-size decode halved once before any
  crop. Detail (zoom) loads leave none.
- Nested archives (up to two levels) are copied to `cacheDir/nested`
  (`NestedArchives`): reused across opens, least recently used evicted past
  2 GiB (never while a book holds them), inner archives over 1 GiB or past the
  per-book budget skipped. They sort by file name among their siblings. Covers
  copy only the inner archive holding the first page.
- A copy that fails for lack of storage fails the whole open, so a book never
  opens with pages missing. Copies stop when the open is canceled; the
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

### Install on a connected device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The debug build installs beside the release build (`minty.emanata.debug`).

## Permissions

- `MANAGE_EXTERNAL_STORAGE` ("All files access", Android 11+): browse and
  open comics in any folder, on SD cards and USB drives; rename and delete
  them. The app asks on first launch.
- `READ_EXTERNAL_STORAGE` (Android 8–10) and `WRITE_EXTERNAL_STORAGE`
  (Android 8–9): the same on older versions.

No internet permission is requested.

## Notes

- `minSdk 26`, `targetSdk 34`.
- AVIF decoding depends on the platform: it works natively on Android 12+
  (API 31+); on older devices AVIF pages show the error placeholder.
- Zoom: `LanczosShader` (AGSL) draws while the gesture moves; `DetailTile`,
  `DetailStore` and `PageSource.loadDetail` re-render the settled view.
- The archive/PDF backends (`SevenZFile`, junrar `Archive`, `PdfRenderer`) keep
  per-instance decode state and are **not thread-safe**. Because the page grid
  loads the current page and its neighbors concurrently, all access to these
  backends is serialized on the reader or source instance (`@Synchronized`).
- Page/thumbnail decode failures are logged (`ArchiveSource`, `PdfSource`,
  `PageAdapter`, `ThumbAdapter`, `ViewerActivity`) instead of being silently
  swallowed.
- The file browser reloads the current folder on resume, so files added or
  removed outside the app appear without a manual refresh.

## License

The app's own code is released under the [MIT License](LICENSE). Based on
[ComicReader](https://github.com/infinity6901/ComicReader) by infinity6901 (MIT).

Bundled third-party components keep their own licenses. The app shows each
license and NOTICE text under Settings → About (files in
`app/src/main/assets/licenses/`).

| Component | License |
|---|---|
| AndroidX, Material Components for Android | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| JetBrains Java Annotations, JSpecify, Error Prone annotations, Guava ListenableFuture | Apache-2.0 |
| [junrar](https://github.com/junrar/junrar) | UnRAR License (see below) |
| Apache Commons Compress, IO, Lang, Codec | Apache-2.0 |
| XZ for Java | Public domain |
| SLF4J (`slf4j-api`, `slf4j-nop`) | MIT |
| [jxl-coder](https://github.com/awxkee/jxl-coder) | Apache-2.0 or BSD-3-Clause |
| libjxl, skcms (in jxl-coder) | BSD-3-Clause |
| Highway (in jxl-coder) | Apache-2.0 or BSD-3-Clause |
| Brotli, Little CMS, GIFLIB (in jxl-coder) | MIT |
| libpng with APNG patch (in jxl-coder) | PNG Reference Library License v2 |
| [Eigen](https://gitlab.com/libeigen/eigen) (in jxl-coder) | MPL-2.0 |
| Android NDK cpufeatures (in jxl-coder) | BSD-2-Clause |
| Rust standard library; crates addr2line, gimli, libc, miniz_oxide, rustc-demangle (in jxl-coder) | MIT or Apache-2.0 |
| Rust crates novtb, pic-scale, pxfm (in jxl-coder) | BSD-3-Clause or Apache-2.0 |
| LLVM libc++abi, libunwind (in jxl-coder) | Apache-2.0 with LLVM Exceptions |
| [ZXing](https://github.com/zxing/zxing) (`core`) | Apache-2.0 |
| Material Symbols | Apache-2.0 |

Dual-licensed components are used under Apache-2.0.

**UnRAR:** junrar is derived from the UnRAR source code. All copyrights to RAR
and UnRAR are owned by Alexander Roshal. The UnRAR code may not be used to
develop a RAR (WinRAR) compatible archiver.
