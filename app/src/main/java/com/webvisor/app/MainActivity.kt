package com.webvisor.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.webvisor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Host del primer sitio cargado; referencia para DomainPolicy en modo estricto. */
    private var originalHost: String? = null

    /** Callback que la web deja pendiente mientras el usuario elige un archivo. */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // --- FocusGuard: pausa/tope diario en sitios que distraen ---
    /** Host de la página cargada actualmente (para saber si "recién se entra"). */
    private var currentHost: String? = null

    /**
     * Bloquea SOLO el buscador de Google en sí (google.com, www.google.com,
     * y sus variantes por país como google.com.ar, google.co.uk, etc.),
     * dejando pasar automáticamente cualquier otro servicio de Google
     * (Drive, Gmail, Maps, login/accounts, Meet, Translate, etc.) sin
     * necesidad de armar una lista de excepciones: esos hosts tienen un
     * subdominio antes de "google" (drive.google.com, accounts.google.com)
     * y por eso no matchean este patrón, que solo admite "google." o
     * "www.google." al principio.
     *
     * OJO: además, esto solo aplica a la navegación PRINCIPAL (ver
     * "request.isForMainFrame" más abajo) — un iframe de reCAPTCHA dentro
     * de otra página tampoco se bloquea.
     */
    private val googleSearchHostRegex = Regex("^(www\\.)?google\\.[a-z.]+$")

    private fun isManuallyBlockedHost(host: String?): Boolean {
        if (host == null) return false
        return googleSearchHostRegex.matches(host.lowercase())
    }

    /** URL distractora que queda esperando a que termine el cooldown. */
    private var pendingFocusUrl: String? = null

    private var focusCooldownHandler: Handler? = null
    private var focusCooldownRunnable: Runnable? = null

    /** true mientras la Activity está en primer plano (para no contar minutos en segundo plano). */
    private var isAppInForeground = false

    private val usageTickHandler = Handler(Looper.getMainLooper())
    private val usageTickRunnable = object : Runnable {
        override fun run() {
            val overlayVisible = binding.focusGuardOverlay.visibility == View.VISIBLE
            if (isAppInForeground && !overlayVisible && FocusGuard.isDistracting(currentHost)) {
                FocusGuard.addUsedSeconds(this@MainActivity, 1)
                if (FocusGuard.isBudgetExceeded(this@MainActivity)) {
                    showFocusBlockedScreen()
                }
            }
            usageTickHandler.postDelayed(this, 1000)
        }
    }

    /** Lanza el selector de archivos del sistema (galería, Drive, "Archivos", etc.). */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val results = if (result.resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStatusBar()
        setupWebView()
        setupFullScreenOnScroll()
        setupDownloads()
        setupFocusGuard()

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        applyDarkModePreferences()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        usageTickHandler.removeCallbacks(usageTickRunnable)
        focusCooldownRunnable?.let { focusCooldownHandler?.removeCallbacks(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La Activity está marcada con android:configChanges="...|uiMode" para
        // no reiniciarse (perdería la página cargada) cuando el sistema pasa
        // de claro a oscuro o viceversa. Por eso el ajuste se hace a mano acá.
        applyDarkModePreferences(newConfig)
    }

    private fun isSystemInDarkMode(config: Configuration = resources.configuration): Boolean {
        val nightModeFlags = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Sincroniza la app con el modo claro/oscuro del sistema:
     * - Color de los íconos de la barra de estado (oscuros sobre fondo claro,
     *   claros sobre fondo oscuro).
     * - Le pide al WebView que oscurezca automáticamente las páginas que no
     *   tengan su propio modo oscuro (si el dispositivo lo soporta).
     */
    private fun applyDarkModePreferences(config: Configuration = resources.configuration) {
        val isDark = isSystemInDarkMode(config)

        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isDark

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isDark)
        }

        // El cambio de modo claro/oscuro puede alterar cómo se ve la misma
        // página (por el oscurecido forzado del WebView), así que
        // recalculamos el color de las barras.
        syncStatusBarColorWithPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * La barra de estado y la de navegación reservan su propio espacio
     * (nada se dibuja detrás): la web se ve como el contenido normal de una
     * app, no "por debajo" de la hora/batería. El color de ambas barras se
     * sincroniza con el fondo de cada página (ver syncStatusBarColorWithPage).
     */
    private fun setupStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /**
     * true si el WebView está forzando actualmente un renderizado oscuro
     * (algorithmic darkening) porque el sistema está en modo oscuro. En ese
     * caso, el color de fondo "real" de la página (el que devuelve JS) no
     * coincide con lo que realmente se ve en pantalla, así que hay que
     * corregirlo al calcular el color de las barras.
     */
    private fun isForcedDarkRenderingActive(): Boolean {
        return isSystemInDarkMode() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    }

    /**
     * Lee el color de fondo real de la página cargada (el de <body>, o si
     * ese es transparente, el de <html>) y lo aplica a la barra de estado y
     * a la de navegación, para que se vean como una continuación de la web
     * en vez de una franja de color fijo ajena a la página.
     */
    private fun syncStatusBarColorWithPage() {
        val script = """
            (function() {
                var bodyColor = window.getComputedStyle(document.body).backgroundColor;
                if (!bodyColor || bodyColor === 'rgba(0, 0, 0, 0)' || bodyColor === 'transparent') {
                    return window.getComputedStyle(document.documentElement).backgroundColor;
                }
                return bodyColor;
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { rawResult ->
            var color = parseCssColor(rawResult) ?: return@evaluateJavascript

            // El WebView está mostrando la página oscurecida artificialmente,
            // pero el color que acabamos de leer es el original (claro) de
            // la web: usamos el fondo oscuro de la app en su lugar para que
            // las barras coincidan con lo que se ve en pantalla.
            if (isForcedDarkRenderingActive() && isColorLight(color)) {
                color = ContextCompat.getColor(this, R.color.window_background)
            }

            applyBarsColor(color)
        }
    }

    /** Aplica un color a ambas barras del sistema y ajusta el color de sus íconos. */
    private fun applyBarsColor(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color

        val lightIcons = isColorLight(color)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    /**
     * Convierte el resultado de evaluateJavascript (un string con formato
     * CSS, ej. "rgb(18, 18, 18)" o "rgba(18, 18, 18, 1)", entre comillas
     * dobles por venir de JSON) a un color de Android. Devuelve null si no
     * se pudo interpretar o si el fondo es completamente transparente.
     */
    private fun parseCssColor(rawResult: String?): Int? {
        if (rawResult.isNullOrBlank() || rawResult == "null") return null

        val unquoted = rawResult.trim().removeSurrounding("\"")
        val numbers = Regex("[\\d.]+").findAll(unquoted).map { it.value }.toList()
        if (numbers.size < 3) return null

        return try {
            val r = numbers[0].toFloat().toInt().coerceIn(0, 255)
            val g = numbers[1].toFloat().toInt().coerceIn(0, 255)
            val b = numbers[2].toFloat().toInt().coerceIn(0, 255)
            val alpha = if (numbers.size >= 4) numbers[3].toFloat() else 1f
            if (alpha <= 0f) return null
            Color.rgb(r, g, b)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** true si el color es "claro" (conviene usar íconos oscuros encima). */
    private fun isColorLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return luminance > 0.5
    }

    /**
     * Oculta la barra de navegación inferior (modo inmersivo) para que la
     * web ocupe toda la pantalla como una app. La barra de estado (reloj,
     * batería, señal) NO se toca: queda siempre visible.
     */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    /** Vuelve a mostrar la barra de navegación inferior. */
    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    /**
     * Al empezar a scrollear la web, la barra de navegación inferior
     * desaparece para dejar la página en pantalla completa, como si fuera
     * una app nativa. El reloj y la batería (barra de estado) siguen
     * visibles todo el tiempo. Si el usuario vuelve al principio de la
     * página, la barra de navegación reaparece.
     */
    private fun setupFullScreenOnScroll() {
        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 0) {
                hideSystemBars()
            } else {
                showSystemBars()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " WebVisor/1.0"

        // Puente para recibir imágenes que la propia web genera como blob:
        // (ej. el botón "Descargar" de Pinterest) y que no pueden bajarse
        // como una URL de red normal. Ver downloadBlobUrl().
        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobDownloader")

        // Mantener presionada una imagen la descarga (para imágenes normales;
        // las que usan blob: se resuelven en downloadBlobUrl()).
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val imageUrl = when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            }

            if (imageUrl != null) {
                webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                downloadImage(imageUrl)
                true
            } else {
                false
            }
        }

        // Compartir discreto: mantener presionado la franja inferior de la
        // pantalla (últimos ~56dp) comparte el link actual. Se limita a esa
        // franja a propósito para no interferir con la selección de texto
        // nativa del WebView en el resto del contenido.
        val shareStripHeightPx = (56 * resources.displayMetrics.density).toInt()
        val shareGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                shareCurrentUrl()
            }
        })
        webView.setOnTouchListener { view, event ->
            if (event.y >= view.height - shareStripHeightPx) {
                shareGestureDetector.onTouchEvent(event)
            }
            // Devolvemos false siempre para que el WebView reciba el evento
            // con normalidad (scroll, zoom, selección de texto, etc.).
            false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentHost = url?.let { Uri.parse(it).host }
                binding.swipeRefresh.isRefreshing = true
                binding.emptyState.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                syncStatusBarColorWithPage()
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                // No se ignoran errores SSL silenciosamente: se cancela la carga.
                handler.cancel()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()

                // Esquemas que no son web (intent://, tel:, mailto:, whatsapp:, market://, etc.)
                // se delegan a la app nativa correspondiente del sistema.
                if (scheme != "http" && scheme != "https") {
                    return openWithExternalApp(uri)
                }

                // ContentGuard: bloqueo duro de sitios para adultos, manda
                // por sobre cualquier otra regla.
                val targetHost = uri.host

                // Bloqueo manual: solo aplica a la navegación PRINCIPAL, no
                // a iframes de otras páginas (ej. el widget de reCAPTCHA
                // que corre bajo google.com/recaptcha/... dentro de otro
                // sitio) — por eso el chequeo "request.isForMainFrame".
                if (request.isForMainFrame && isManuallyBlockedHost(targetHost)) {
                    showManuallyBlockedScreen(targetHost)
                    return true
                }

                if (ContentGuard.isBlocked(targetHost)) {
                    showContentBlockedScreen()
                    return true
                }

                // ContentGuard: si es un buscador o YouTube sin modo seguro
                // forzado, se reescribe la URL y se recarga con ese modo.
                val safeUri = ContentGuard.applySafeSearch(uri)
                if (safeUri != null) {
                    binding.webView.loadUrl(safeUri.toString())
                    return true
                }

                // FocusGuard: si el destino es un sitio que distrae y recién
                // se está entrando ahí desde otro sitio, se frena la carga
                // (cooldown o bloqueo por tope diario) en vez de dejarla pasar.
                if (FocusGuard.isDistracting(targetHost) &&
                    !targetHost.equals(currentHost, ignoreCase = true)
                ) {
                    if (FocusGuard.isBudgetExceeded(this@MainActivity)) {
                        showFocusBlockedScreen()
                    } else {
                        showFocusCooldown(uri.toString())
                    }
                    return true // se intercepta: el WebView no la carga (todavía)
                }

                // Regla de Dominio Flexible: por defecto se permite navegar
                // libremente (YouTube, pagos, login, etc. incluidos). Ver DomainPolicy.
                return if (DomainPolicy.isNavigationAllowed(uri.toString(), originalHost)) {
                    false // false = "no lo intercepto", que lo cargue el propio WebView
                } else {
                    openWithExternalApp(uri)
                }
            }
        }

        // Sin esto, un <input type="file"> en la web (ej. subir el CV en un
        // Google Forms) no abre ningún selector: el WebView, a diferencia de
        // Chrome, no sabe manejarlo solo. onShowFileChooser delega la
        // elección al selector nativo de Android (galería, Drive, Archivos).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
    }

    /**
     * Registra un DownloadListener en el WebView: cualquier archivo que la
     * página quiera descargar (PDF, imágenes, adjuntos, etc.) se manda al
     * DownloadManager del sistema, que se encarga de bajarlo a la carpeta
     * "Descargas" del dispositivo y mostrar el progreso/notificación.
     */
    private fun setupFocusGuard() {
        usageTickHandler.post(usageTickRunnable)

        binding.focusGuardCancelButton.setOnClickListener {
            hideFocusOverlay()
            // "Volver": si veníamos de otra página la mostramos, si no, no
            // pasa nada más (el sitio distractor nunca llegó a cargarse).
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        binding.focusGuardContinueButton.setOnClickListener {
            val url = pendingFocusUrl
            hideFocusOverlay()
            if (url != null) {
                binding.webView.loadUrl(url)
            }
        }
    }

    /** Pantalla (discreta, no roja) para el buscador de Google bloqueado a mano. */
    private fun showManuallyBlockedScreen(host: String?) {
        pendingFocusUrl = null
        focusCooldownRunnable?.let { focusCooldownHandler?.removeCallbacks(it) }

        binding.focusGuardOverlay.setBackgroundColor(
            ContextCompat.getColor(this, R.color.manual_block_gray)
        )
        binding.focusGuardTitle.text = getString(R.string.manual_block_title)
        binding.focusGuardMessage.text = getString(R.string.manual_block_message)
        binding.focusGuardCountdown.text = ""
        binding.focusGuardContinueButton.visibility = View.GONE
        binding.focusGuardOverlay.visibility = View.VISIBLE
    }

    /** Pantalla de bloqueo duro para sitios NSFW (ContentGuard). Sin cooldown ni opción de pasar. */
    private fun showContentBlockedScreen() {
        pendingFocusUrl = null
        focusCooldownRunnable?.let { focusCooldownHandler?.removeCallbacks(it) }

        binding.focusGuardOverlay.setBackgroundColor(
            ContextCompat.getColor(this, R.color.content_block_red)
        )
        binding.focusGuardTitle.text = getString(R.string.content_block_title)
        binding.focusGuardMessage.text = getString(R.string.content_block_message)
        binding.focusGuardCountdown.text = ""
        binding.focusGuardContinueButton.visibility = View.GONE
        binding.focusGuardOverlay.visibility = View.VISIBLE
    }

    /** Pantalla de espera (cooldown) antes de entrar a un sitio distractor. */
    private fun showFocusCooldown(url: String) {
        pendingFocusUrl = url

        binding.focusGuardOverlay.setBackgroundColor(
            ContextCompat.getColor(this, R.color.sage_green_dark)
        )
        binding.focusGuardTitle.text = getString(R.string.focus_guard_cooldown_title)
        binding.focusGuardMessage.text = getString(R.string.focus_guard_cooldown_message)
        binding.focusGuardContinueButton.visibility = View.GONE
        binding.focusGuardOverlay.visibility = View.VISIBLE

        var secondsLeft = FocusGuard.COOLDOWN_SECONDS
        binding.focusGuardCountdown.text = secondsLeft.toString()

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft <= 0) {
                    binding.focusGuardCountdown.text = "0"
                    binding.focusGuardContinueButton.visibility = View.VISIBLE
                } else {
                    binding.focusGuardCountdown.text = secondsLeft.toString()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        focusCooldownHandler = handler
        focusCooldownRunnable = runnable
        handler.postDelayed(runnable, 1000)
    }

    /** Pantalla de bloqueo cuando ya se agotó el tope diario de minutos. */
    private fun showFocusBlockedScreen() {
        pendingFocusUrl = null
        focusCooldownRunnable?.let { focusCooldownHandler?.removeCallbacks(it) }

        binding.focusGuardOverlay.setBackgroundColor(
            ContextCompat.getColor(this, R.color.sage_green_dark)
        )
        binding.focusGuardTitle.text = getString(R.string.focus_guard_blocked_title)
        binding.focusGuardMessage.text = getString(
            R.string.focus_guard_blocked_message,
            FocusGuard.DAILY_BUDGET_MINUTES,
            FocusGuard.formatDuration(FocusGuard.secondsUntilReset())
        )
        binding.focusGuardCountdown.text = ""
        binding.focusGuardContinueButton.visibility = View.GONE
        binding.focusGuardOverlay.visibility = View.VISIBLE
    }

    private fun hideFocusOverlay() {
        focusCooldownRunnable?.let { focusCooldownHandler?.removeCallbacks(it) }
        binding.focusGuardOverlay.visibility = View.GONE
        pendingFocusUrl = null
    }

    private fun setupDownloads() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

                // Un blob: no es una URL de red real (es un archivo generado
                // en memoria por JS, típico de botones "Descargar" propios
                // de la web como el de Pinterest): no lo puede bajar el
                // DownloadManager, hay que resolverlo dentro del WebView.
                if (url.startsWith("blob:")) {
                    downloadBlobUrl(url, fileName)
                    return@setDownloadListener
                }
                val cookies = CookieManager.getInstance().getCookie(url)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(
                        mimeType.takeIf { it.isNotBlank() }
                            ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                    MimeTypeMap.getFileExtensionFromUrl(url)
                                )
                            ?: "application/octet-stream"
                    )
                    if (!cookies.isNullOrEmpty()) {
                        addRequestHeader("cookie", cookies)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    setDescription(getString(R.string.app_name))
                    setTitle(fileName)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val downloadManager =
                    getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)

                Toast.makeText(
                    this,
                    getString(R.string.downloading_toast, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.download_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Descarga una imagen detectada por long-press (ver setupWebView). */
    private fun downloadImage(imageUrl: String) {
        try {
            val mimeType =
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(imageUrl))
                    ?: "image/jpeg"

            val fileName = URLUtil.guessFileName(imageUrl, null, mimeType)
            val cookies = CookieManager.getInstance().getCookie(imageUrl)

            val request = DownloadManager.Request(Uri.parse(imageUrl)).apply {
                setMimeType(mimeType)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("cookie", cookies)
                }
                setDescription(getString(R.string.app_name))
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(this, getString(R.string.downloading_toast, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_error), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Le pide al propio WebView (en JS) que resuelva el blob: en base64 y
     * nos lo devuelva por BlobDownloadBridge, ya que un blob: solo existe
     * en memoria dentro de esa página y Kotlin no puede leerlo directo.
     */
    private fun downloadBlobUrl(blobUrl: String, suggestedFileName: String) {
        val safeFileName = suggestedFileName.replace("\"", "").replace("\\", "")
        val script = """
            (function() {
                fetch("$blobUrl")
                    .then(function(res) { return res.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidBlobDownloader.receiveBlob(base64, "$safeFileName");
                        };
                        reader.readAsDataURL(blob);
                    });
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    /** Decodifica el base64 recibido desde JS y lo guarda en Descargas. */
    private fun saveBase64File(base64Data: String, fileName: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val mimeType =
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileName))
                    ?: "image/jpeg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                java.io.File(downloadsDir, fileName).outputStream().use { it.write(bytes) }
            }

            Toast.makeText(
                this,
                getString(R.string.downloading_toast, fileName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_error), Toast.LENGTH_SHORT).show()
        }
    }

    /** Puente expuesto al JavaScript de la página (ver downloadBlobUrl). */
    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun receiveBlob(base64Data: String, fileName: String) {
            runOnUiThread { saveBase64File(base64Data, fileName) }
        }
    }

    private fun openWithExternalApp(uri: Uri): Boolean {
        return try {
            val externalIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(externalIntent)
            true
        } catch (e: ActivityNotFoundException) {
            // No hay app instalada que maneje ese esquema; evitamos crash.
            true
        }
    }

    /**
     * Comparte la URL actualmente cargada usando el selector nativo de
     * Android (WhatsApp, Email, copiar al portapapeles, etc.). Se activa
     * con un long-press en la franja inferior de la pantalla; como única
     * confirmación visible se usa una vibración breve, sin ícono ni botón.
     */
    private fun shareCurrentUrl() {
        val url = binding.webView.url
        if (url.isNullOrEmpty()) return

        binding.webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    /**
     * Extrae la URL de un Deep Link (QR escaneado, link compartido desde
     * WhatsApp/Email/Galería, etc.) y la carga en el WebView.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            originalHost = data.host
            binding.emptyState.visibility = View.GONE
            binding.webView.loadUrl(data.toString())
        } else if (binding.webView.url.isNullOrEmpty()) {
            // La app se abrió desde el ícono, sin ningún enlace: mostramos
            // una pantalla de espera en lugar de una web en blanco.
            binding.emptyState.visibility = View.VISIBLE
            applyBarsColor(ContextCompat.getColor(this, R.color.sage_green))
        }
    }

    override fun onBackPressed() {
        if (binding.focusGuardOverlay.visibility == View.VISIBLE) {
            hideFocusOverlay()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
