# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.example.aiinbox.**$$serializer { *; }
-keepclassmembers class com.example.aiinbox.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.aiinbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# OkHttp
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
