# SignalSmith Android SDK — rules applied to consumers that shrink.
# Public API surface (Kotlin metadata kept so default arguments survive).
-keep class com.zeotap.sdk.Zeotap { *; }
-keep class com.zeotap.sdk.Analytics { public *; }
-keep class com.zeotap.sdk.Configuration { *; }
-keep class com.zeotap.sdk.Configuration$Builder { *; }
-keep class com.zeotap.sdk.ConsentOptions { *; }
-keep class com.zeotap.sdk.ConsentOptions$Builder { *; }
-keep class com.zeotap.sdk.ConsentInput { *; }
-keep class com.zeotap.sdk.ConsentInput$* { *; }
-keep class com.zeotap.sdk.ConsentSignals { *; }
-keep class com.zeotap.sdk.ConsentStateSnapshot { *; }
-keep class com.zeotap.sdk.ConsentSource { *; }
-keep class com.zeotap.sdk.ConsentDefault { *; }
-keep class com.zeotap.sdk.CmpMode { *; }
-keep class com.zeotap.sdk.IntegrationMeta { *; }
-keep class com.zeotap.sdk.Callback { *; }
-keep class com.zeotap.sdk.SdkListener { *; }
-keep class com.zeotap.sdk.BatchResult { *; }
-keep class com.zeotap.sdk.Storage { *; }
-keep class com.zeotap.sdk.Logger { *; }
-keep class com.zeotap.sdk.NotInitializedException { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, Exceptions

# Reflective, optional Advertising ID lookup (play-services-ads-identifier is
# compileOnly; hosts may or may not ship it).
-dontwarn com.google.android.gms.ads.identifier.**
-keepnames class com.google.android.gms.ads.identifier.AdvertisingIdClient
-keepnames class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info
-keepclassmembers class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info { public *; }

# kotlinx.serialization is used as a JSON tree only (no generated serializers).
-dontwarn kotlinx.serialization.**
