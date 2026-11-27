package android.content;

/* Lab-only compile stub (never shipped, never dexed): lets the verification
 * lab compile OtaConfigSync's apply/sync surface (which takes a real
 * android.content.Context on device) without the Android SDK. Only the
 * members OtaConfigSync touches are declared; the device build compiles the
 * same source against android.jar's full Context.
 */
public interface Context {
    int MODE_PRIVATE = 0;

    SharedPreferences getSharedPreferences(String name, int mode);

    java.io.File getFilesDir();

    Context getApplicationContext();
}
