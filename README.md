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

Also: optional auto-login (password encrypted on the device) and Box64 0.4.5, which
Warmane accepts.

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

The app installs these from the `addons/` folder. Only their own folders are replaced;
other addons are left alone. When the app has a newer version of an installed one, it
updates it on Play.

| Addon | Installed | What it does |
|---|---|---|
| `WoWMobileLink` | Always | Sends unit info to the touch targeting page. |
| `ThorFixes` | With WoW Imito's addons | Hides WoW Imito's status strip when this app runs the game. |

ConsolePortLK is installed by the app on first launch if it isn't already there.

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

## License

Winlator and WoW Mobile are LGPL-2.1. The bottom-screen pad comes from the AYN Thor WoW
Launcher, which is GPL-3, so this fork as a whole is GPL-3. Box64 source details:
`app/src/main/assets/box64/BOX64-0.4.5-SOURCE.txt`.

World of Warcraft is a trademark of Blizzard Entertainment. This project is not
affiliated with Blizzard Entertainment, Warmane, WoW Imito or AYN.
