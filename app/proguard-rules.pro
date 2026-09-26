# Keep Koin
-keep class org.koin.** { *; }
-keepclassmembers class org.koin.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep domain models used in reflection
-keep class com.alex193a.rootmypixel.domain.model.** { *; }
-keep class com.alex193a.rootmypixel.data.model.** { *; }
