# ProGuard rules for DeskPet

# Keep annotations (required for serialization and Compose)
-keepattributes *Annotation*

# Keep Kotlin Serialization
-keep class kotlinx.serialization.** { *; }

# Keep data model classes (stored in DataStore as JSON)
-keepclassmembers class com.deskpet.data.model.** { *; }

# Keep Compose runtime classes from being stripped
-keep class androidx.compose.** { *; }

# Coil 3.x (okio)
-dontwarn okio.**
-keep class okio.** { *; }
