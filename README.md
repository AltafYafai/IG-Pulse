# IG-Pulse

An LSPosed module for the official Instagram app (`com.instagram.android`).

IG-Pulse is a fork of [WA-Pulse](https://github.com/AltafYafai/WA-Pulse) — it reuses that
project's module architecture and keeps its extension points, but the hook targets are Instagram's.

> Not affiliated with, endorsed by, or supported by Instagram or Meta.

## Features

**Privacy**
- Hide read receipts (`direct_v2/seen_items`), globally or per account
- Hide the typing / activity indicator
- Hide Active Now presence
- Hide story views from selected accounts
- Notify on screenshot or screen capture

**Media**
- Save photos, videos, stories and reels from the viewers
- Name files after the author, or by timestamp
- Choose the destination folder

**Customization**
- Hide bottom-navigation tabs
- Override Instagram's accent, background and text colours
- Change the profile-grid column count
- Disable shared-element and fragment transitions

**Utilities**
- Long-press a direct message or caption for Copy / Translate / Share

## Requirements

- LSPosed (or a compatible Xposed implementation)
- A rooted device or a custom ROM
- Android 9 (API 28) or newer
- The official Instagram app, stable or beta

## How it works

Instagram ships through R8 with aggressive renaming, so nothing can be hooked by class name.
IG-Pulse resolves every target with **DexKit** at first launch and caches the result in the
Instagram sandbox, keyed by the app's version code.

Two families of anchors survive obfuscation, and every resolver in
[`Unobfuscator`](app/src/main/java/com/igpulse/xposed/core/devkit/Unobfuscator.kt) is built from
them:

1. **GraphQL operation names** — `direct_v2/seen_items`, `direct_v2/get_presence`,
   `direct_v2/reels_media_seen` and friends are protocol strings the server requires, so they
   cannot be renamed away.
2. **Reflectively-referenced class and layout names** — anything Instagram instantiates by name
   from a manifest, a resource, or `Class.forName`.

A resolver that matches nothing returns `null` rather than throwing. The feature skips itself,
logs which anchors it tried, and the module keeps working with one feature missing. This matters
because Instagram ships breaking changes weekly and a hard block would take the whole module
down on every update.

## After an Instagram update

Open LSPosed's log and look for lines shaped like:

```
[IG-Pulse] seenItems <- "direct_v2/seen_items" -> Xyz->a(String)
[IG-Pulse] seenItems unresolved. None of the anchors matched: direct_v2/seen_items, ...
```

The first form is a hit. The second means Instagram renamed the operation; re-pin it by probing
the installed build:

```bash
adb shell am broadcast -a com.igpulse.PROBE --es needle "direct_v2/see"
```

The result is printed to the LSPosed log, and also written to
`Android/data/com.igpulse/cache/last_probe.txt`.

## Version support

`isVersionSupported` merges the allowlist in `arrays.xml` with a remotely-fetched list, and
anything at or above the baseline in `FeatureLoader.BASELINE` loads optimistically. A new beta
does not require a new APK. Turn on **Ignore version warnings** to force loading regardless.

## Building

```bash
./gradlew assembleDebug
```

Point the build at your own repositories in `gradle.properties` before publishing:

```properties
sourceRepo=you/IG-Pulse
releasesRepo=you/IG-Pulse-releases
rawBranch=main
```

## Licence

GPL-3.0, inherited from WA-Pulse. See [LICENSE](LICENSE).
