package lk.techworld.anantplay.sicast;

/** Version + identity constants (no Gradle BuildConfig — plain aapt2/javac/d8 build).
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
    static final String VERSION_NAME = "3.4.2";
    static final int VERSION_CODE = 30402;
    static final String NAME = "AnantPlay Sinhala Player";
    private AppInfo() { }
}
