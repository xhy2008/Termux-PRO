# Termux-PRO app rules for the R8 pass of the release build.

# Class names stay intact on purpose: component names are referenced from strings
# (TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, the termux-am socket, RUN_COMMAND
# results in crash reports) and the primitive fields of JniResult are set by name from
# liblocal-socket.so. Obfuscating them would need a much wider keep surface for no gain.
-dontobfuscate

# com.termux.pro.BuildConfig is deliberately NOT kept. Its one field, TERMUX_PACKAGE_VARIANT,
# is a compile-time String constant, so javac inlines it into TermuxApplication and no class
# reference survives to be reflected upon. The reflective reader
# TermuxBootstrap.setTermuxPackageManagerAndVariantFromTermuxApp() existed for plugin apps that
# shared our sharedUserId, which this app no longer supports, and has no caller.

# Referenced by name from local-socket.cpp: FindClass("com/termux/shared/jni/models/JniResult")
# + GetMethodID("<init>", "(IILjava/lang/String;I)V") + GetFieldID for retval/errno/errmsg/intData.
# Also covered by @Keep, listed explicitly so it cannot regress silently.
-keepclassmembers class com.termux.shared.jni.models.JniResult {
    public <init>(int, int, java.lang.String);
    public <init>(int, int, java.lang.String, int);
    int retval;
    int errno;
    java.lang.String errmsg;
    int intData;
}

# Instantiated by FragmentManager from the android:fragment string in res/xml/root_preferences.xml
# and res/xml/termux_preferences.xml.
-keep class com.termux.app.fragments.settings.** { <init>(); }

# Preference fragments instantiate the keys of TermuxPreferenceConstants by name lookups, and
# Logger.logStackTraceWithMessage() prints Log.getStackTraceString(), which is only useful with
# real names anyway.
-keepattributes SourceFile,LineNumberTable

# Guava -jre and commons-compress carry compile-only annotations and optional integrations that
# are deliberately absent from the APK.
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn javax.management.**
-dontwarn org.apache.tools.bzip2.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.dec.**
-dontwarn org.xerial.snappy.**
