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
