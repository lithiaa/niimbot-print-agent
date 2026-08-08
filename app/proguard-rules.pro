# ProGuard rules for Niimbot Print Agent

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class com.niimbot.printagent.NiimbotPrintApplication { *; }

# Keep Room entities and DAOs
-keep class com.niimbot.printagent.data.** { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Keep Ktor serialization
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# Keep Bluetooth classes
-keep class android.bluetooth.** { *; }
-keep class com.niimbot.printagent.ble.** { *; }

# Keep Service and Receiver
-keep class com.niimbot.printagent.service.** { *; }
-keep class com.niimbot.printagent.receiver.** { *; }

# Keep UI classes referenced from XML
-keep class com.niimbot.printagent.ui.** { *; }

# Keep generated view binding classes
-keep class com.niimbot.printagent.databinding.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.zxing.** { *; }

# Timber
-keep class com.jakewharton.timber.** { *; }

# Suppress warnings
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn dagger.hilt.**
-dontwarn android.bluetooth.**