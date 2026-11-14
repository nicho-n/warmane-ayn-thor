# Warmane on AYN Thor

An Android launcher for playing [Warmane](https://www.warmane.com) (World of Warcraft
3.3.5a) on the AYN Thor. It installs as *Warmane Ayn Thor Launcher*.

This is a fork of [WoW Mobile](https://github.com/marcocastignoli/wow-mobile), and uses the
touch screen controls of the [AYN Thor WoW Launcher](https://github.com/wyattabuntjer/AYN-Thor-WoW-Launcher).

## Features

- **Touch targeting:** a page on the bottom screen lists nearby units; tap one to target it.
- **Touch screen controls:** button pages, a keyboard and a trackpad on the bottom screen,
  with the game on the top screen.
- **Opinionated console controls:** a fixed ConsolePort controller layout, plus **A** to
  play, enter the world and log in, and the **D-pad** to pick a character.

Also: optional auto-login (password encrypted on the device), an optional touch-controls
overlay on the game screen, and Box64 0.4.5, which Warmane accepts.

## Default controller layout

Characters that don't have ConsolePort bindings yet start with this layout. Change it in
ConsolePort's binding menu; `/thor defaults` puts it back on the current character.

| Button | Alone | L2 held | R2 held | L2 + R2 held |
|---|---|---|---|---|
| A | Action bar 5 | Action bar 9 | Right bar 2, button 5 | Utility ring |
| B | Action bar 3 | Action bar 8 | Bottom-left bar 3 | Open all bags |
| X | Action bar 1 | Action bar 6 | Bottom-left bar 1 | Bottom-left bar 6 |
| Y | Action bar 2 | Action bar 7 | Bottom-left bar 2 | Bottom-left bar 7 |
| L1 | Target nearest enemy | Bottom-left bar 4 | Minimap zoom in | Bottom-left bar 9 |
| R1 | Jump | Action bar 10 | Bottom-left bar 5 | Bottom-left bar 10 |
| D-pad up | Bottom-left bar 12 | Camera zoom in | Bottom-right bar 6 | Bottom-right bar 10 |
| D-pad down | Action bar 11 | Camera zoom out | Bottom-right bar 8 | Bottom-right bar 12 |
| D-pad left | Bottom-left bar 11 | Bottom-right bar 1 | Bottom-right bar 5 | Bottom-right bar 9 |
| D-pad right | Run/walk | Bottom-right bar 3 | Bottom-right bar 7 | Bottom-right bar 11 |
| Select | Target nearest friend | World map | Camera zoom out | Camera zoom in |
| Start | Interact with the unit under the pointer | Game menu | Raid cursor | Open chat |

Left stick moves, right stick turns the camera, R3 switches the right stick to moving the
pointer and L3 clicks. L2 and R2 are the layer modifiers.

## Requirements

- AYN Thor. Other Android devices may run it, but the bottom-screen features need the
  Thor's second screen.
- Your own World of Warcraft 3.3.5a (build 12340) client. No game files are included.
- A Warmane account.

## Setup

1. Install the APK from [Releases](../../releases), or build it (see below).
2. Open the app and pick the folder that contains `Wow.exe`. The app lists the addons it
   will install and, if you continue, first zips your `Interface/AddOns` folder into the
   game folder as `AddOns-backup-<date>.zip`.
3. In **WoW Settings**, set the realm server to `logon.warmane.com` (*Custom*). Turn on
   auto-login if you want it.
4. Press **Play**.

## Addons

The app installs these. Only their own folders are replaced; other addons are left alone.

| Addon | Installed | What it does |
|---|---|---|
| `ThorControls` | Always | Sets up ConsolePort for the controller layout and the right-stick camera; Thor layout fixes (`/thor`). |
| `WoWMobileLink` | Always | Sends unit info to the touch targeting page. |
| ConsolePortLK | If ConsolePort is missing or is WoW Imito's | Controller UI. |
| NotPlater | If missing | Nameplates. |

`ThorControls` and `WoWMobileLink` are updated on Play when the app has a newer version.

Coming from WoW Imito: its ConsolePort is replaced with the standard one and its addons
(WoWImito, ImitoTouchUI) are moved to `Interface/AddOns-disabled`. Your ConsolePort
bindings are kept.

## Building

```
./gradlew assembleRelease
```

Requires JDK 17, Android SDK (platform 34), NDK 24.0.8215888 and CMake 3.22.1.
Release signing reads `~/.wow-mobile-secrets/keystore.properties`
(`storeFile`/`storePassword`/`keyAlias`/`keyPassword`); without it the release
build is unsigned.

## Credits

- [WoW Mobile](https://github.com/marcocastignoli/wow-mobile) by marcocastignoli: the app
  this is forked from.
- [AYN Thor WoW Launcher](https://github.com/wyattabuntjer/AYN-Thor-WoW-Launcher) by
  wyattabuntjer: the bottom-screen pad is ported from it.
- [WoW Imito](https://github.com/MrAnonymz/WoW-Imito) by MrAnonymz: uses its controls.
- [Winlator](https://github.com/brunodev85/winlator) by brunodev85: the Wine + Box64
  engine.
- [Box64](https://github.com/ptitSeb/box64) by ptitSeb and contributors.
- [ConsolePortLK](https://github.com/leoaviana/ConsolePortLK) by leoaviana: bundled
  unmodified.
- [NotPlater](https://github.com/RichSteini/NotPlater) by RichSteini: bundled (MIT).

## License

This fork is distributed under the GPL-3 (see `COPYING`): it contains the AYN Thor WoW
Launcher's GPL-3 code. Winlator and WoW Mobile are LGPL-2.1 (see `LICENSE`). Bundled
addons keep their own licenses. Box64 source details:
`app/src/main/assets/box64/BOX64-0.4.5-SOURCE.txt`.

World of Warcraft is a trademark of Blizzard Entertainment. This project is not
affiliated with Blizzard Entertainment, Warmane, WoW Imito or AYN.
