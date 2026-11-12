# About this fork

This is a fork of [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) that adds two launcher features:
a **Continue watching** row on the Home screen, below the dock, and a **PC** pill in the top bar that wakes a PC and switches to its input.

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

## PC pill (Wake-on-LAN)

A **PC** pill (a monitor icon) opens the top bar, before Inputs, and the Wi-Fi pill is hidden. OK asks first. With an input set it asks
"Switch to the PC?", and **Switch** sends a Wake-on-LAN magic packet and switches the TV to that input, so the picture
comes up as the PC wakes; a PC that is already on ignores the packet, so this also just switches to it. With no input
it asks "Turn on the PC?", and **Turn on** sends the packet. Cancel does nothing.

### Setting it up on the TV

Hold OK on Settings, then choose **Launcher settings**:

- **PC (Wake-on-LAN)**: type the PC's MAC address, e.g. `AA-BB-CC-DD-EE-FF`. Saving shows the pill, and
  **Remove** hides it again. On Windows the address is under Settings > Network > Ethernet > Physical address,
  or `getmac /v`. Use the wired adapter's address.
- **PC input**: the HDMI port the PC is plugged into, or Don't switch.

These choices win over `config.xml` and a theme, where the same settings are `wake_pc_mac` and `wake_pc_input`.
Both are empty by default, so the feature is off. With `top_menu` on there is no pill row, so the pill is not
shown.

### What it sends

One 102-byte packet, three times, to the TV's own subnet broadcast address (192.168.x.255 on a /24 network) on
UDP port 9. The packet is the PC's MAC repeated 16 times.

Wake-on-LAN has to broadcast: a sleeping PC has no IP address to send to, so its network card listens for its
own MAC in every packet, and every other device drops the packet. Routers don't forward broadcasts, so nothing
leaves the home network. 255.255.255.255 is used only when the TV can't tell its subnet.

### What the PC needs

- "Wake on Magic Packet" on in the network adapter's Advanced settings.
- "Allow this device to wake the computer" under the adapter's Power Management.
- Usually a BIOS setting: "Wake on LAN" or "Power On by PCI-E" on.
- To wake from a full shutdown, not only sleep: "ErP" (or "Deep Sleep") off in the BIOS. ErP cuts power to the
  network port when the PC is off, so nothing reaches it; the port's light stays dark. This was the fix on the
  tested PC.

Wired Ethernet is the reliable case.

A line above the pills takes over from the hidden Wi-Fi pill: the network name (Android shows it only while the
TV's Location setting is on; otherwise it says Wi-Fi or Ethernet) and, once a PC input is set, a green **PC on** or
grey **PC off**. On means the PC's HDMI input has a picture, as the TV reports it; it changes when the TV says so,
with no polling. A PC that isn't sending a picture to the TV (the TV isn't one of its displays) shows as off.
With the PC on, the dialog says "The PC is on" and **Switch** goes straight to its input.

## Settings menu

With `settings_menu` on (config.xml or a theme's `"settings_menu": true`), a click on **Settings** drops a menu
with **Launcher settings** and **TV system settings** first, then what hold OK offers: module cards, edit dock,
reorder apps and restart. Off by default.

## How it differs from upstream

Upstream keeps the launcher offline on purpose. This fork adds two permissions:

| Permission | Why |
|---|---|
| `READ_TV_LISTINGS` | Read Watch Next and preview channel rows. Granted with `pm grant`, never requested on screen. |
| `INTERNET` | Download the poster images those rows point at, and send the PC pill's wake packet on the local network. |

The launcher contacts nothing else over the network: no analytics and no background polling. The PC pill's wake packet
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
