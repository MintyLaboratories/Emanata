# Release builds only: drops the trace sections libraries make (the app's own are off through
# BuildConfig.TRACES); checkReleaseTraceSections fails if any android.os.Trace call is left.
-assumenosideeffects class android.os.Trace {
    public static void beginSection(...);
    public static void endSection();
    public static void beginAsyncSection(...);
    public static void endAsyncSection(...);
    public static void setCounter(...);
    public static boolean isEnabled() return false;
}
