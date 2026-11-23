package android.content;

/* Lab-only compile stub (never shipped, never dexed): lets the verification
 * lab compile AppPrefs's thin Android glue on a bare JVM, exactly like
 * tools/core-lambda-stubs.jar lets build.sh compile lambdas against old
 * android.jar. Only the READ methods AppPrefs uses are declared.
 *
 * v3.4.2 — Editor/apply() added so WatchHistory's two persistence calls
 * (load/save) are exercised by the lab exactly as the app runs them. */
public interface SharedPreferences {

    String getString(String key, String def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    boolean getBoolean(String key, boolean def);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
}
