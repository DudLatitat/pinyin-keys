# Strip verbose/debug logging from release builds (see CLAUDE.md).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
