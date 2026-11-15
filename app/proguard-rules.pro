# Strip verbose/debug logging from release builds (see CLAUDE.md).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# kotlinx.serialization: keep generated serializers for our data classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.pinyinkeys.ime.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.pinyinkeys.ime.translate.** { *; }
