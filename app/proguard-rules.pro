# Reglas ProGuard/R8 para Web Visor
-keepclassmembers class com.webvisor.app.MainActivity$JsInterfaceBridge {
    public *;
}
-dontwarn org.jetbrains.annotations.**
