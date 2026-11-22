package lk.techworld.anantplay.sicast;

/** Version + identity constants (no Gradle BuildConfig — plain aapt2/javac/d8 build).
 *  v3.1.2 (code 30102): the "keeps stopping" crash fix + the selfupd spin-loop
 *  fix + the privacy-first deep troubleshooter. Strictly v3.x — never v4. */
final class AppInfo {
    static final String VERSION_NAME = "3.1.2";
    static final int VERSION_CODE = 30102;
    static final String NAME = "AnantPlay Sinhala Player";
    private AppInfo() { }
}
