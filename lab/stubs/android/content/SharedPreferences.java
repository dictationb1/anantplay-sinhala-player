package android.content;

/* Lab-only compile stub (never shipped, never dexed): lets the verification
 * lab compile AppPrefs's thin Android glue on a bare JVM, exactly like
 * tools/core-lambda-stubs.jar lets build.sh compile lambdas against old
 * android.jar. Only the READ methods AppPrefs uses are declared. */
public interface SharedPreferences {
    String getString(String key, String def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    boolean getBoolean(String key, boolean def);
}
