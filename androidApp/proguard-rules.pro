# RoastCurve R8/ProGuard 保留规则
# kotlinx.serialization：保留 @Serializable 类的序列化器（1.5+ 自带 consumer rules，这里兜底）
-keepattributes *Annotation*, InnerClasses, Signature, ExceptionTable, EnclosingMethod
-keep,includedescriptorclasses class com.roastcurve.**$$serializer { *; }
-keepclassmembers class com.roastcurve.** {
    *** Companion;
}
-keepclasseswithmembers class com.roastcurve.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BuildConfig 官方签名指纹（编译期常量，防 R8 意外裁剪）
-keep class com.roastcurve.android.BuildConfig { *; }

# ktor/协程（websocket、反射类加载路径）
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# slf4j（ktor 传递依赖引用绑定类但运行时不需要，R8 报缺失属误报）
-dontwarn org.slf4j.**

# kotlinx.coroutines 调试名（可选）
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
