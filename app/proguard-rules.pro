# Reglas de R8. AndroidX (WorkManager, WebKit) ya trae las suyas.
# Mantener nombres en las trazas de error para poder depurar informes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Puente con la web (window.MiNubeAndroid): R8 no debe renombrar ni quitar estos métodos
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
