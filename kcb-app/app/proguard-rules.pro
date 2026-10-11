# Keep rules for lzuapp release build

# kotlinx.serialization model classes (serializers are generated in the same package)
-keep class li.lyc.lzuapp.data.api.model.** { *; }
-keepclasseswithmembers class li.lyc.lzuapp.data.api.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep default attributes needed by serialization & Room
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# OkHttp / Okio ship consumer rules; silence their notes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# osmdroid（地图）：内部有反射/SPI 加载，整体保留
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# 高德定位 SDK
-keep class com.amap.api.** { *; }
-dontwarn com.amap.api.**

# 高德定位 SDK 的可选引用（GNSS 软定位/jafama 未打包进 jar，网络定位用不到）
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
