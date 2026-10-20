# Keep junrar / commons-compress reflection-free but generic
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
# jxl-coder: its native code looks up these classes by name
-keep class com.awxkee.jxlcoder.** { *; }
