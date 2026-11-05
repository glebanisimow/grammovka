# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit discovers its components by reflection (ComponentDiscovery → no-arg constructor of each
# *Registrar). R8 removed those constructors and BarcodeScanning.getClient crashed with an NPE in release.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
