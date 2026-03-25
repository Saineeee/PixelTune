-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# Keep serialization for smartwatch communication
-keepclassmembers class com.theveloper.pixeltune.shared.** {
    *;
}
