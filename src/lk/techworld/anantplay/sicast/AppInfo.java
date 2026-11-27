package lk.techworld.anantplay.sicast;

/** Version + identity constants (no Gradle BuildConfig — plain aapt2/javac/d8 build).
 *
 *  v3.4.6.a (code 30407): THE ANDROID 16 CRASH FIX + THE FULL MORNYE PARITY — the
 *  v3.4.6 build shipped a 4 KB-page-class libflutter.so that the Android 16
 *  dynamic linker refuses on 16 KB-page devices (instant startup crash). This
 *  release pins the 16 KB-ready per-ABI engine artifact and completes the Mornye
 *  v5.4.0 visual parity. Same Mornye Liquid-Glass Flutter UI (Option A) —
 *  Mornye v5.4.0 visual engine vendored under LGPL-3.0-only into ui/, hosted by a
 *  FlutterActivity over the same pure-Java cast/player backend, with pure-SVG
 *  Liquid-Glass Cinema assets and a 55 MB updater cap. Strictly v3.x — never v4.
 *
 *  v3.4.0 (code 30400): THE VECTOR UI OVERHAUL — 63 VectorDrawables replace every
 *  emoji, a brand-new adaptive + monochrome launcher icon, two REAL Sinhala weights
 *  (400/600, 742 KB smaller than the variable original), the Mornye-grade shell with
 *  the full 75-option customization catalog, the redesigned cinema HUD with three
 *  bottom sheets, and the Safari-grade browser (speed dial, bottom omnibar, capture
 *  dock). Strictly v3.x — never v4.
 *  v3.3.0 (code 30300): THE CINEMA PLAYER & NAVIGATION OVERHAUL — the fixed bottom
 *  navigation bar, the HTTP-403 header engine, the VideoViewport aspect engine, the
 *  VLC-grade HUD (lock/rotate/aspect/tracks/external VLC) and the Sri Lankan
 *  subtitle quick-finder with .zip capture. Strictly v3.x — never v4.
 *
 *  v3.2.0 (code 30200): the zero-rate-limit GitHub updater + the flawless
 *  Sinhala subtitle engine (on-device keyless translator, auto .zip extractor,
 *  script-ratio validation) + the Mornye/Apple-grade customization hub + the
 *  dedicated 10-point Troubleshooter. Strictly v3.x — never v4. */
final class AppInfo {
    // v3.4.6.k (code 30417): THE SILENT ZERO-PROMPT SELF-UPDATE (LAW #1:
    // UPDATE_PACKAGES_WITHOUT_USER_ACTION + USER_ACTION_NOT_REQUIRED on API
    // 31+, two-lane letter-patch/major versioning, the 2–3× installer-loop
    // kill) + THE SIGNED JSON OTA (ota/v1/manifest.json: both-CDN consensus
    // + SHA-256, Subtitle Bridge mirrors + index feeds + ad rules + the
    // 7-day local SRT cache TTL) + THE 100 MiB APK CEILING + the 7-video
    // audit's core kills: the blank PlayActivity screen (the missing
    // buildUi()/startPlayback()), the DLNA-cast-opens-HomeScreen BAL fix,
    // the YouTube renderer-crash shield + the pure-Java ad-free extractor +
    // SponsorBlock auto-skip, the stale Play-in-Cinema banner, the #EXTM3U
    // in-app IPTV channel sheet, the setup-wizard forward-swipe lock +
    // slide persistence + no cold-launch flash, the animated permission
    // toggle graphic, the bottom-anchored arrow-guided coach, the
    // scrolling-tile backdrop lag kill, the stuck Force-liquid-blur switch,
    // the Mornye bubble sliders + live Sinhala preview, the ⋮ popover
    // anchor, and the non-disruptive troubleshooter.
    // v3.4.6.f (code 30412): THE HONEST UPDATER & UI FIX — kills the
    // v3.4.6.e→v3.4.6.e self-lock, the background multi-download, the
    // `No active stream to cancel` EventChannel collision, and the fake
    // PASSes in the deep troubleshooter (Probe 47's HTTP 0 lie).
    static final String VERSION_NAME = "3.4.6.k";
    static final int VERSION_CODE = 30417;
    static final String NAME = "AnantPlay Sinhala Player";
    private AppInfo() { }
}
