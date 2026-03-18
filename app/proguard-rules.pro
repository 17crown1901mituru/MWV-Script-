# MWV Script ProGuard Rules
-keep class com.mwvscript.app.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.faendir.rhino_android.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn com.faendir.rhino_android.**