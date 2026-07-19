# Web Visor

App Android nativa (Kotlin) que actúa como visor web minimalista: sin barra de
direcciones, sin botón de cámara, pensada para abrirse automáticamente al
escanear un QR o tocar un enlace desde WhatsApp, el correo o la galería.

## Índice
1. Requisitos previos
2. Cómo abrir el proyecto en Android Studio
3. Subir el proyecto a GitHub desde cero
4. Generar el .APK (GUI y línea de comandos)
5. Instalar el APK en el celular
6. Notas técnicas importantes

---

## 1. Requisitos previos

- **Android Studio** (canal Stable), versión reciente. Se puede descargar de
  https://developer.android.com/studio
- **JDK 17** (Android Studio ya lo trae embebido, no hace falta instalarlo aparte).
- Una cuenta de **GitHub** (si vas a versionar el código).
- Un celular Android con **"Depuración USB"** activada, o un emulador.

---

## 2. Cómo abrir el proyecto en Android Studio

1. Descomprimí el .zip de este proyecto (`WebVisor/`) en una carpeta de tu PC.
2. Abrí Android Studio → **Open** → seleccioná la carpeta `WebVisor`.
3. Android Studio va a detectar el `build.gradle.kts` y va a ofrecerte
   sincronizar el proyecto ("Sync Now"). Aceptá.
4. La primera sincronización descarga el Gradle Wrapper y las dependencias:
   puede tardar unos minutos según tu conexión.
5. Si Android Studio te sugiere actualizar el Android Gradle Plugin (AGP) o
   Gradle a una versión más nueva, podés aceptar sin problema: el código no
   depende de ninguna API obsoleta.

> **Nota:** este proyecto no incluye los binarios `gradlew` / `gradlew.bat` ni
> `gradle-wrapper.jar` (son archivos binarios). Android Studio los genera
> solos al abrir el proyecto. Si preferís generarlos manualmente por consola
> antes de abrir Android Studio, con tener Gradle instalado en tu PC alcanza
> con parado en la carpeta del proyecto correr:
> ```bash
> gradle wrapper --gradle-version 8.9
> ```

---

## 3. Subir el proyecto a GitHub desde cero

Desde una terminal, parado en la carpeta `WebVisor/`:

```bash
# 1. Inicializar el repositorio local
git init

# 2. Agregar todos los archivos (el .gitignore ya excluye /build, .gradle, apks, etc.)
git add .

# 3. Primer commit
git commit -m "Proyecto inicial: Web Visor (visor web con deep links y QR)"

# 4. Renombrar la rama principal a main (opcional, buena práctica)
git branch -M main

# 5. Crear el repositorio remoto en GitHub
#    Opción A: hacerlo manualmente en https://github.com/new
#    Opción B: con GitHub CLI instalada
gh repo create web-visor --public --source=. --remote=origin

# 6. Si creaste el repo manualmente en la web, conectalo así:
git remote add origin https://github.com/TU_USUARIO/web-visor.git

# 7. Subir el código
git push -u origin main
```

A partir de ahí, cualquier cambio futuro se sube con:
```bash
git add .
git commit -m "Descripción del cambio"
git push
```

---

## 4. Generar el .APK

### Opción A — Desde Android Studio (recomendada, más simple)

1. Menú **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**.
2. Cuando termine, aparece un aviso abajo a la derecha: click en **"locate"**
   para abrir la carpeta donde quedó el archivo.
3. El APK de depuración queda en:
   `app/build/outputs/apk/debug/app-debug.apk`

Este APK de debug ya se puede instalar y probar, pero **no está firmado
para producción** ni optimizado (no tiene `minify` aplicado).

### Generar un APK de **release** firmado (para publicar o distribuir)

1. Menú **Build** → **Generate Signed Bundle / APK…**
2. Elegí **APK** → **Next**.
3. Click en **Create new…** para generar un *keystore* nuevo (guardalo en un
   lugar seguro, lo vas a necesitar para futuras actualizaciones de la app):
   - Key store path: elegí dónde guardarlo (ej. `webvisor-release-key.jks`)
   - Password, alias y datos del certificado: completá con tus datos.
4. Seleccioná **release** como Build Variant → **Finish**.
5. El APK firmado queda en:
   `app/release/app-release.apk`

### Opción B — Por línea de comandos (sin abrir la interfaz gráfica)

Parado en la carpeta raíz del proyecto (una vez que Android Studio ya generó
`gradlew` la primera vez que abriste el proyecto):

```bash
# APK de debug (rápido, para probar)
./gradlew assembleDebug
# En Windows: gradlew.bat assembleDebug

# El resultado queda en:
# app/build/outputs/apk/debug/app-debug.apk
```

```bash
# APK de release SIN firmar (necesita firma manual después con apksigner)
./gradlew assembleRelease

# El resultado queda en:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

Para un release firmado por línea de comandos, lo más simple es configurar
la firma directamente en `app/build.gradle.kts` (bloque `signingConfigs`)
apuntando a tu keystore, o usar `apksigner` manualmente después del build.
Si vas a distribuir la app fuera de Play Store, la Opción A (Android Studio)
es más simple porque te guía paso a paso con la firma.

---

## 5. Instalar el APK en el celular

**Método 1 — Cable USB:**
1. Conectá el celular con Depuración USB activada.
2. En Android Studio, con el proyecto abierto, click en el botón ▶ **Run**.
   Instala y abre la app automáticamente.

**Método 2 — Transferir el archivo .apk directamente:**
1. Copiá `app-debug.apk` (o `app-release.apk`) al celular (por cable, Drive,
   o cualquier medio).
2. En el celular, abrí el archivo desde el explorador de archivos.
3. Si es la primera vez, Android va a pedir permiso para **"instalar apps de
   fuentes desconocidas"** para esa app específica (Gmail, Archivos, etc.):
   aceptalo.
4. Confirmá la instalación.

---

## 6. Notas técnicas importantes

- **Ícono:** `#87A96B` (Verde Sage), definido en `colors.xml` y usado en el
  ícono adaptativo (`ic_launcher_background.xml` / `ic_launcher_foreground.xml`).
  Si querés un ícono más elaborado (con logo propio), lo más prolijo es
  reemplazar estos vectores usando **Android Studio → click derecho en `res`
  → New → Image Asset**, subiendo tu propio diseño en verde sage.

- **Captura de enlaces (Deep Links):** el `AndroidManifest.xml` declara un
  `intent-filter` para `VIEW` + `BROWSABLE` sin restricción de host. Como la
  app **no es dueña de un dominio propio** (no hay forma de "verificar"
  automáticamente que Web Visor debe abrir *cualquier* URL), Android la va
  a mostrar como una opción más en el selector **"Abrir con..."** cuando el
  usuario escanee un QR o toque un link desde WhatsApp/Email/Galería. El
  usuario puede además marcar "Usar siempre" para que deje de preguntar.
  Esto es una limitación del sistema operativo, no del código: sin un
  dominio propio verificado (Android App Links con `assetlinks.json`), no
  existe forma de que la app se abra 100% automáticamente sin selector.

- **Regla de Dominio Flexible:** implementada en `DomainPolicy.kt`. Por
  defecto (`STRICT_MODE = false`) la navegación es totalmente libre —igual
  que un navegador—, así que nunca vas a ver el mensaje de "este link te
  lleva fuera de este sitio". Si en el futuro necesitás una app tipo
  "kiosco" limitada a un solo sitio, podés activar `STRICT_MODE = true`;
  aun así, YouTube, Google/Facebook login y las pasarelas de pago listadas
  en `ALWAYS_ALLOWED_HOSTS` van a seguir funcionando.

- **Login con Google dentro de un WebView:** Google bloquea el login OAuth
  dentro de WebViews genéricos por política propia de seguridad (no es algo
  que Web Visor pueda evitar). Si un sitio depende del login de Google y
  falla, esa restricción viene del lado de Google, no de esta app.

- **Barra de estado transparente:** configurada en `MainActivity.kt`
  (`setupEdgeToEdgeTransparentStatusBar()`) y en `themes.xml`. El contenido
  del WebView se dibuja también detrás de la status bar para maximizar el
  espacio visible.

- **Sin barra de búsqueda ni botón de cámara:** por diseño, el layout
  (`activity_main.xml`) solo contiene el WebView (con pull-to-refresh) y una
  pantalla de espera que se muestra únicamente si la app se abre desde el
  ícono sin ningún enlace asociado.
