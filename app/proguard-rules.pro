-keep class kotlinx.serialization.** { *; }
-keep class com.znet.app.data.model.** { *; }
-dontwarn kotlinx.serialization.**

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
