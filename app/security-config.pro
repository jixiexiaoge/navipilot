# Obfuscation raises reverse-engineering cost; it does not encrypt the APK.
-repackageclasses 'com.navipilot.obf'

# Remove verbose/debug/info Android logs, retaining warnings and errors.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
