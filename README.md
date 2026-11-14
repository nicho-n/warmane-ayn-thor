# WoW Mobile for AYN Thor

**The best way to play [Warmane](https://www.warmane.com) (World of Warcraft 3.3.5a) on the AYN Thor.**

The game runs on the Thor's top screen; the bottom screen becomes a full control
surface, with button pages, a keyboard, a trackpad and an **innovative touch-targeting
system**: every nearby enemy, NPC, party member and battleground player gets its own box
on the bottom screen. Tap a box and you target exactly that unit.

This is a fork of [WoW Mobile](https://github.com/marcocastignoli/wow-mobile), tuned
for the AYN Thor's two screens and for Warmane.

## Touch targeting

The **Target** page on the bottom screen shows the units around you as boxes with their
class colour, health and name.

- **Tap to target, exactly.** Tapping a nearby unit clicks its nameplate, so you get
  *that* boar, not the closest unit with the same name.
- **Boxes stay put.** A unit keeps its box for as long as it is in view, so the box you
  were about to tap doesn't move when something else leaves. When a unit goes out of
  range its box empties; if it comes back soon it gets the same box again.
- **Numbered look-alikes.** When several units share a name, each gets a number,
  `(1) Wild Boar`, `(2) Wild Boar`, shown both in its box and on its nameplate in the
  game, so you always know which one you're picking.
- **Tabs for every situation:**

  | Tab | Shows |
  |---|---|
  | Nearby | Enemies around you (hostile and neutral) |
  | NPC | Friendly NPCs and players (vendors, quest givers, innkeepers…) |
  | Party | Your party on the left, nearby enemies on the right (more rows appear as more enemies show up) |
  | BG Team | Your battleground team |
  | BG All | Your team and the enemy team side by side |
  | Arena | Your team and the arena opponents |

Targeting works through the bundled **WoW Mobile Link** addon (see [Addons](#addons)).

## More features

- **Bottom-screen pad** with button pages, an on-screen keyboard and a trackpad.
- **Controller support** for ConsolePort, plus the handheld's buttons on the menus:
  press **A** on the app's start screen to play, **A** on the character screen to enter
  the world, and the **D-pad** to pick a character.
- **Auto-login:** save your password in *WoW Settings* and the app logs you in when the
  login screen appears. The password is encrypted with a key kept in Android's secure key
  storage and never leaves the device or touches the game folder.
- **Box64 0.4.5**, which Warmane accepts (older Box64 builds get disconnected at the
  character list).

## What you need

- An **AYN Thor** (the app also runs on other Android devices, but the bottom-screen pad
  and targeting are built for the Thor's second screen).
- Your own **World of Warcraft 3.3.5a (build 12340)** client folder on the device. No
  game files are included.
- A **Warmane** account.

## Setup

1. Install the APK (from [Releases](../../releases), or build it, see below).
2. Open the app, pick the folder that contains `Wow.exe`.
3. In **WoW Settings**, set the realm server to `logon.warmane.com` (choose *Custom*),
   and optionally turn on auto-login.
4. Copy the addons (below) into the game's `Interface/AddOns` folder.
5. Press **Play** (or **A**).

## Addons

The `addons/` folder holds the addons this app works with. Copy the ones you want into
`World of Warcraft 3.3.5a/Interface/AddOns/`. Each one only adds its own folder; nothing
else in your AddOns folder is touched.

| Addon | Needed? | What it does |
|---|---|---|
| `WoWMobileLink` | Yes, for the Target page | Sends nearby, party, battleground and arena units to the app and numbers look-alike units on their nameplates. Only draws its data while the Target page is open. |
| `ThorFixes` | Only if you use WoW Imito's addons | Small fixes for WoW Imito's addons on the Thor (hides their status strip when this app runs the game). |

The ConsolePortLK addons are installed by the app itself on first launch (if you don't
already have them), as in upstream WoW Mobile.

## Building

```
./gradlew assembleRelease
```

Requires JDK 17, Android SDK (platform 34), NDK 24.0.8215888 and CMake 3.22.1.
Release signing reads `~/.wow-mobile-secrets/keystore.properties`
(`storeFile`/`storePassword`/`keyAlias`/`keyPassword`); without it the release
build is unsigned.

## Credits

This project stands on the work of others:

- **[WoW Mobile](https://github.com/marcocastignoli/wow-mobile)** by marcocastignoli:
  the app this is forked from (game folder setup, tuned Wine container, ConsolePortLK
  provisioning, WoW settings).
- **[AYN Thor WoW Launcher](https://github.com/wyattabuntjer/AYN-Thor-WoW-Launcher)**
  by wyattabuntjer: the bottom-screen pad (button pages, keyboard, trackpad) is ported
  from it.
- **WoW Imito**: the Android WoW app that showed how good WoW can be on a handheld and
  inspired this project. This app works alongside its addons but does not include them.
- **[Winlator](https://github.com/brunodev85/winlator)** by brunodev85: the
  Windows-on-Android engine (Wine + Box64) everything runs on.
- **[Box64](https://github.com/ptitSeb/box64)** by ptitSeb and contributors.
- **[ConsolePortLK](https://github.com/leoaviana/ConsolePortLK)** by leoaviana:
  controller UI for WoW 3.3.5a (bundled unmodified).

## License

Winlator and WoW Mobile are LGPL-2.1. The bottom-screen pad comes from the AYN Thor WoW
Launcher, which is GPL-3, so this fork as a whole is distributed under the **GPL-3**.
Source for everything in the APK is in this repository (Box64: see
`app/src/main/assets/box64/BOX64-0.4.5-SOURCE.txt`).

World of Warcraft is a trademark of Blizzard Entertainment. This project is not
affiliated with or endorsed by Blizzard Entertainment, Warmane, WoW Imito or AYN.
