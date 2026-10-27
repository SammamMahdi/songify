# NewPipeExtractor uses reflection and runs YouTube's player JavaScript through Rhino.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
# YouTube responses are parsed with protobuf-lite, which reflects on message fields.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
