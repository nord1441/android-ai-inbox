# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class uk.nordtek.aiinbox.**$$serializer { *; }
-keepclassmembers class uk.nordtek.aiinbox.** {
    *** Companion;
}
-keepclasseswithmembers class uk.nordtek.aiinbox.** {
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
