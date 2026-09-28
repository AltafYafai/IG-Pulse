# Changelog

## 1.0.0

First IG-Pulse release. Ported from [WA-Pulse](https://github.com/AltafYafai/WA-Pulse) and
re-targeted at `com.instagram.android`.

### Privacy
- Hide seen — suppresses the outbound `direct_v2/seen_items` read receipt.
- Hide active status — drops the `direct_v2/get_presence` publish while still reading others.
- Hide typing — short-circuits `direct_v2/activity_indicator`, with a presence-cache fallback.
- Hide story views — filters `direct_v2/reels_media_seen` per-username.
- Screenshot notify — module-side notification hook on the media/reel viewers.

### Media
- Download photos, stories and reels from the viewers, staged at the highest-resolution point.

### Customization
- Theme colour override, profile grid column count, bottom-tab hiding.

### Utilities
- Long-press Copy / Translate / Share on message rows and captions.
- Restart Instagram and clear-resolution-cache actions from the settings UI.

### Diagnostics
- `DebugFeature` always loads and exposes a probe broadcast:

  ```
  adb shell am broadcast -a com.igpulse.PROBE --es needle "direct_v2/seen_items"
  ```

  Output lands in the LSPosed log — this is how anchors get re-pinned after an Instagram
  update without shipping a release.

### Notes
- Instagram anchors (DexKit string matches and reflective class names) have **not** been
  validated against a real APK. Run the probe above after installing and report misses.
- Version policy is optimistic: anything at or above the baseline in
  `FeatureLoader.isFutureBetaVersion` loads, and failures are reported per feature.
