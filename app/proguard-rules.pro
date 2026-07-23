# Bird Proxy ProGuard rules
-keepattributes Signature
-keepattributes *Annotation*

# VPN service and UI classes.
-keep class com.birdproxy.v2.** { *; }
-keep class * extends android.net.VpnService { *; }

# JNI entry point; native symbols are generated for this exact package/class.
-keep class com.birdproxy.v2.nativecore.TProxyService { *; }
