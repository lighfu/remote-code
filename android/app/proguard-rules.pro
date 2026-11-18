-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.remotecode.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.remotecode.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
