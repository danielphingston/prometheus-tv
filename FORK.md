# About this fork

This is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) that adds two launcher features:
a **Continue watching** row on the Home screen, below the dock, and a **Wake PC** pill in the top bar.

Everything else (the debloat lists, the tools, the safety rules in `PROMPT.md`) is unchanged from upstream.

## What it does

- Reads what apps publish to Android TV's TV provider:
  - **Watch Next** rows from every app (Prime Video and others), newest first. Rows untouched for 120 days are skipped.
  - **Preview channels** named "Continue watching" or "Keep watching". Stremio publishes one.
- Shows each item as a poster card with:
  - an episode badge, such as `S1 E1`
  - the icon of the app that OK opens
  - a progress bar
  - the title underneath
- Sorts every app's items together, most recently watched first. Watch Next rows carry their own time. A preview
  channel has none, so its items take the time Home last opened that app, in the channel's own order.
- Shows at most 5 items per app and 20 in all. It hides finished items (95% watched or more) and repeats, and skips
  Watch Next watchlist entries, which were never started.
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

## Wake PC

A **Wake PC** pill takes the Wi-Fi pill's place in the top bar. OK asks "Wake the PC?", and **Wake** sends a
Wake-on-LAN magic packet to the PC's MAC address. Cancel sends nothing.

- Off by default. Switch it on with the PC's MAC address, in `config.xml`
  (`<string name="wake_pc_mac">AA-BB-CC-DD-EE-FF</string>`) or a theme's `config.json` (`"wake_pc_mac"`).
- The packet is a UDP broadcast on the TV's own network only (255.255.255.255 and the subnet's broadcast address,
  ports 9 and 7), sent three times. Nothing leaves the local network.
- The PC needs Wake-on-LAN on: "Wake on Magic Packet" in the network adapter's Advanced settings, "Allow this device
  to wake the computer" under Power Management, and usually a BIOS setting. Wired Ethernet is the reliable case.
  Waking from a full shutdown on Windows often needs Fast startup off.
- With `top_menu` on there is no pill row, so the Wake PC pill is not shown.

## How it differs from upstream

Upstream keeps the launcher offline on purpose. This fork adds two permissions:

| Permission | Why |
|---|---|
| `READ_TV_LISTINGS` | Read Watch Next and preview channel rows. Granted with `pm grant`, never requested on screen. |
| `INTERNET` | Download the poster images those rows point at, and send the Wake PC packet on the local network. |

The launcher contacts nothing else over the network: no analytics and no background polling. The Wake PC packet
stays on the local network and is sent only when you confirm.

- **When it reads:** only when Home comes to the front.
- **What goes online:** only the image addresses the apps publish. With the switch off, nothing is fetched.
- **Poster cache:** posters are stored on disk in the app's cache, refreshed after 14 days, and decoded at card size
  in RGB_565 (about 8 MB of memory at most).

The APK is about 10 KB over upstream's 576 KB size gate, so upstream CI's size check fails on this fork.

## Tested on

Vu Masterprice TV, Google TV 12, firmware STT2.230310.002, with Stremio 1.12.1, Prime Video and JioHotstar.

## New files

- `launcher/app/src/main/java/com/gathin/tvhome/ContinueWatching.java`: reads the TV provider.
- `launcher/app/src/main/java/com/gathin/tvhome/ContinueRow.java`: the row, cards and D-pad handling.
- `launcher/app/src/main/java/com/gathin/tvhome/WakeOnLan.java`: builds and broadcasts the magic packet.
- `launcher/app/src/main/java/com/gathin/tvhome/PosterCache.java`: downloads posters and caches them on disk and in
  memory.
