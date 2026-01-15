# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep Nordic BLE library
-keep class no.nordicsemi.android.ble.** { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# Keep data classes
-keep class com.amua.audiodownloader.ble.ScannedDevice { *; }
-keep class com.amua.audiodownloader.ui.UiState { *; }
