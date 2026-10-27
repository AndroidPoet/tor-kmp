# Shipped inside the AAR; R8/ProGuard in consuming apps apply it automatically.
# The Rust side looks these up by name through JNI, so they must not be renamed or removed.
-keepclasseswithmembernames class io.github.androidpoet.tor.internal.TorBridge { native <methods>; }
-keep class io.github.androidpoet.tor.TorException { <init>(java.lang.String); }
