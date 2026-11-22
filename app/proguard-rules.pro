# Keep libsu shell entry points intact for reflection-free root calls.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
