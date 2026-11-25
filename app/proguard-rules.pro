# Keep commons-compress reflection-free but generic
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class org.apache.commons.compress.** { *; }
# jxl-coder: its native code looks up these classes by name
-keep class com.awxkee.jxlcoder.** { *; }
# Called back by name from jxl_jni.c during a page decode
-keep interface minty.emanata.compact.LibJxl$Stop { *; }
# Called back by name from resample_jni.c between GPU chunks
-keep interface minty.emanata.reader.PixelKernels$Stop { *; }
# Called back by name from archive_jni.c for reads, seeks and skips
-keep interface minty.emanata.reader.LibArchive$Source { *; }
# SMBJ: its event bus (MBassador) finds handlers by annotation; Kerberos and GSS classes are absent on Android
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn org.bouncycastle.**
