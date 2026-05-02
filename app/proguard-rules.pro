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

# LiteRT-LM
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.ai.edge.**

# OkHttp
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
