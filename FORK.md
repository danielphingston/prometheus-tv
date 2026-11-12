# About this fork

This is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) that adds one launcher feature:
a **Continue watching** row on the Home screen, below the dock.

Everything else (the debloat lists, the tools, the safety rules in `PROMPT.md`) is unchanged from upstream.

## What it does

- Reads what apps publish to Android TV's TV provider:
  - **Watch Next** rows from every app (Prime Video and others), newest first. Rows untouched for 120 days are skipped.
  - **Preview channels** named "Continue watching" or "Keep watching". Stremio publishes one.
- Shows each item as a poster card with:
  - an episode badge, such as `S1 E1`
  - a progress bar
  - the title underneath
- Hides finished items (95% watched or more) and repeats. It shows at most 20 items.
- Pressing OK resumes the item in its own app. The launcher only opens a link inside the app that published it, and
  falls back to opening that app.
- Down from the dock goes to the row, and Down again goes to the app grid. Up goes back the same way. The row hides
  itself when there is nothing to continue.

## Switching it on

The feature is **off by default**. Switch it on in either place:

- `launcher/app/src/main/res/values/config.xml`: `<bool name="continue_watching">true</bool>`
- a theme's `config.json`: `"continue_watching": true`

The row needs a dock (`dock_row`) and is not shown in the single-row layout.

`launcher/build-and-install.sh` (and `.ps1`) grants the permission the row needs:

```
pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS
```

## How it differs from upstream

Upstream keeps the launcher offline on purpose. This fork adds two permissions:

| Permission | Why |
|---|---|
| `READ_TV_LISTINGS` | Read Watch Next and preview channel rows. Granted with `pm grant`, never requested on screen. |
| `INTERNET` | Download the poster images those rows point at. |

The launcher contacts nothing else over the network: no analytics and no background polling.

- **When it reads:** only when Home comes to the front.
- **What goes online:** only the image addresses the apps publish. With the switch off, nothing is fetched.
- **Poster cache:** posters are stored on disk in the app's cache, refreshed after 14 days, and decoded at card size
  in RGB_565 (about 8 MB of memory at most).

The APK is a few KB over upstream's 576 KB size gate, so upstream CI's size check fails on this fork.

## Tested on

Vu Masterprice TV, Google TV 12, firmware STT2.230310.002, with Stremio 1.12.1 and Prime Video.

## New files

- `launcher/app/src/main/java/com/gathin/tvhome/ContinueWatching.java`: reads the TV provider.
- `launcher/app/src/main/java/com/gathin/tvhome/ContinueRow.java`: the row, cards and D-pad handling.
- `launcher/app/src/main/java/com/gathin/tvhome/PosterCache.java`: downloads posters and caches them on disk and in
  memory.
