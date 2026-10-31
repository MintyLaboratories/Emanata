# Keep junrar / commons-compress reflection-free but generic
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
# jxl-coder: its native code looks up these classes by name
-keep class com.awxkee.jxlcoder.** { *; }
# Called back by name from jxl_jni.c during a page decode
-keep interface com.infinity.comicreader.compact.LibJxl$Preview { *; }
# SMBJ: its event bus (MBassador) finds handlers by annotation; Kerberos and GSS classes are absent on Android
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn org.bouncycastle.**
