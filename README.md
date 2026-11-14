# Warmane on AYN Thor

An Android launcher for playing [Warmane](https://www.warmane.com) (World of Warcraft
3.3.5a) on the AYN Thor. It installs as *Warmane Ayn Thor Launcher*.

The game runs on the top screen. The bottom screen has button pages, a keyboard, a
trackpad and a touch-targeting page that lists nearby units so you can tap one to
target it.

This is a fork of [WoW Mobile](https://github.com/marcocastignoli/wow-mobile).

## Touch targeting

The **Target** page shows units as boxes with class colour, health and name. Tapping a
box targets that unit.

- Tapping a nearby unit clicks its nameplate, so you get that unit even when others share
  its name.
- A unit keeps its box while it's in view. When it goes out of range its box empties.
- Units that share a name are numbered, e.g. `(1) Wild Boar`, `(2) Wild Boar`, in the box
  and on the nameplate.

| Tab | Shows |
|---|---|
| Nearby | Hostile and neutral units |
| NPC | Friendly NPCs and players |
| Party | Your party on the left, nearby enemies on the right |
| BG Team | Your battleground team |
| BG All | Your team and the enemy team |
| Arena | Your team and the arena opponents |

The Target page needs the `WoWMobileLink` addon (see [Addons](#addons)).

## Other features

- Controller support for ConsolePort. On the app's start screen **A** presses Play; on the
  character screen **A** enters the world and the **D-pad** picks a character.
- Optional auto-login: the password is saved in *WoW Settings*, encrypted with an Android
  Keystore key, and stays on the device.
- Box64 0.4.5 (older builds were disconnected by Warmane at the character list).

## Requirements

- AYN Thor. Other Android devices may run it, but the bottom-screen features need the
  Thor's second screen.
- Your own World of Warcraft 3.3.5a (build 12340) client. No game files are included.
- A Warmane account.

## Setup

1. Install the APK from [Releases](../../releases), or build it (see below).
2. Open the app and pick the folder that contains `Wow.exe`.
3. In **WoW Settings**, set the realm server to `logon.warmane.com` (*Custom*). Turn on
   auto-login if you want it.
4. Copy the addons below into `Interface/AddOns`.
5. Press **Play**.

## Addons

Copy the ones you need from `addons/` into `World of Warcraft 3.3.5a/Interface/AddOns/`.
Each adds its own folder and doesn't change other addons.

| Addon | Needed? | What it does |
|---|---|---|
| `WoWMobileLink` | For the Target page | Sends unit info to the app and numbers units that share a name. |
| `ThorFixes` | Only with WoW Imito's addons | Hides WoW Imito's status strip when this app runs the game. |

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
