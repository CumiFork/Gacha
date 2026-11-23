# Keep JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.arkana.cppoverlay.NativeBridge { *; }
-keep class com.topjohnwu.superuser.** { *; }
