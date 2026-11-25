# Mi Nube para Android

Carcasa nativa (Kotlin + WebView) de la interfaz web de Mi Nube, que se sirve desde el servidor de casa por Tailscale.
Cualquier cambio en el HTML del servidor se ve al instante en los móviles, sin reinstalar nada.

## Qué hace
- **Puerta de conexión:** antes de abrir la web comprueba que el servidor responde. Si no, explica en español qué pasa: sin internet, Tailscale apagado o sin instalar, o servidor apagado. Ofrece **Reintentar** y **Abrir Tailscale**.
- **Web completa:**
  - Login: la sesión se recuerda con `index-quantum-v6.html`.
  - Subir con el selector de archivos.
  - Descargar a `Descargas/` con DownloadManager.
  - Vídeo a pantalla completa.
  - Botón Atrás integrado con la web.
  - El teclado nunca tapa los campos.
- **Compartir → «Guardar en Mi Nube»:** fotos y archivos desde la Galería o WhatsApp. Se suben solos a la carpeta **Recibidos** de quien haya iniciado sesión, con notificación de progreso, aunque se cierre la app.
- **Auto-actualización:** al abrir (como mucho cada 6 h), con el botón de la pantalla de conexión o con una pulsación larga en el icono → «Buscar actualización».

## Configuración privada (nunca en el repositorio)
| Clave | Dónde va |
|---|---|
| `MINUBE_SERVER_URL` | `%USERPROFILE%\.gradle\gradle.properties` en el PC · variable `SERVER_URL` del repositorio en GitHub |
| Keystore y contraseñas | `.jks` guardado fuera del repositorio (con copia de seguridad) · secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |

Ver `config.example.properties`. `.gitignore` bloquea `*.jks`, `*.keystore`, `local.properties` y los APK.

> ⚠️ **No pierdas nunca la keystore ni sus contraseñas.** Sin ellas no se puede publicar una actualización que se instale encima. Habría que desinstalar la app en cada móvil (en el servidor no se pierde nada, pero es un engorro).

## Compilar en el PC (pruebas)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```
- **Salida:** `app\build\outputs\apk\debug\app-debug.apk`.
- **Aparece como «Mi Nube (pruebas)»** (paquete `com.carlos.minube.debug`), así que convive con la versión de verdad.
- **No se auto-actualiza.**

## Publicar una versión nueva
```powershell
git tag -a v1.0.1 -m "Qué cambia (esto sale en el aviso de actualización)"
git push origin v1.0.1
```
GitHub Actions (`.github/workflows/release.yml`):
1. Compila y firma el APK.
2. Crea la release con `MiNube-1.0.1.apk` y `latest.json`.

El `versionCode` es el número de ejecución del workflow, así que siempre sube.

## Preparar el móvil de un familiar (una vez)
1. Instalar **Tailscale** e iniciar sesión en la tailnet.
2. Recomendado: Ajustes → Red → VPN → Tailscale → **VPN siempre activa**.
3. Descargar `MiNube-x.y.z.apk` de la última release (en el móvil) e instalarlo.
4. Abrir Mi Nube y conceder las notificaciones.
5. La primera vez que ofrezca actualizar, conceder **«Instalar apps desconocidas»** a Mi Nube.

## Estructura
```
app/src/main/java/com/carlos/minube/
  MainActivity.kt            WebView, puerta de conexión, insets, Atrás, pantalla completa, lote compartido
  Notifs.kt                  canales de notificación
  net/Connectivity.kt        diagnóstico (internet / Tailscale / servidor)
  net/Quantum.kt             rutas de Quantum 2.0.2 copiadas de realApi (encodeURIComponent exacto)
  web/MiNubeWebClient.kt     navegación y errores de la página
  web/MiNubeChromeClient.kt  selector de archivos y vídeo a pantalla completa
  web/Downloads.kt           descargas → DownloadManager
  share/ShareActivity.kt     recibe ACTION_SEND / SEND_MULTIPLE
  share/ShareCache.kt        copia temporal (se borra a las 24 h)
  share/UploadWorker.kt      subida por streaming a /Recibidos/
  update/Updater.kt          latest.json → DownloadManager → SHA-256 → PackageInstaller
  update/InstallReceiver.kt  confirmación de instalación (o notificación si la app no está en pantalla)
```

## Contrato con la web (`index-quantum-v6.html`)
Se activa solo si el User-Agent contiene `MiNubeApp`:
- `window.MiNube.token()`: token vigente, o `null` si no hay sesión.
- `window.MiNube.source()`: fuente de Quantum (`Archivos`).
- `window.MiNube.refresh()`: recarga la carpeta abierta.
- La sesión se guarda en `localStorage` (`nube.app.sesion`). Si al abrir está caducada, se borra y se muestra el login sin avisos.
- Sin autobloqueo por inactividad.

Si el servidor sigue con la v5, la app usa las variables globales `TOKEN` y `CONFIG.source` como alternativa, pero entonces la sesión no se recuerda.
