package com.webvisor.app

import android.net.Uri

/**
 * Regla de "Dominio Flexible".
 *
 * Objetivo: el usuario nunca debe ver un mensaje de bloqueo tipo
 * "este link te lleva fuera de este sitio". Por defecto, Web Visor
 * deja navegar libremente hacia cualquier URL http/https (igual que
 * un navegador normal), porque cualquier restricción termina rompiendo
 * flujos legítimos (checkout de pagos, login con Google/Facebook,
 * videos embebidos de YouTube, etc.).
 *
 * Esta clase queda como punto único de configuración por si en el
 * futuro se quiere endurecer la política (por ejemplo, para una app
 * "quiosco" que solo debe mostrar un sitio). Con STRICT_MODE = false
 * (valor por defecto) esa restricción NO se aplica.
 */
object DomainPolicy {

    /**
     * Si se pone en true, la navegación quedará limitada al dominio
     * original + la lista de ALWAYS_ALLOWED_HOSTS de abajo.
     * Por defecto en false: navegación abierta, sin bloqueos.
     */
    const val STRICT_MODE = false

    /**
     * Dominios que SIEMPRE se permiten aunque STRICT_MODE esté activo:
     * video, pagos y autenticación. Se matchean por sufijo de host.
     */
    private val ALWAYS_ALLOWED_HOSTS = listOf(
        // Video
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com",
        // Autenticación / OAuth
        "accounts.google.com", "google.com", "gstatic.com",
        "facebook.com", "fbcdn.net", "apple.com",
        // Pasarelas de pago comunes en Latinoamérica y globales
        "mercadopago.com", "mercadopago.com.ar", "mercadolibre.com",
        "paypal.com", "stripe.com", "checkout.stripe.com",
        "todopago.com.ar", "decidir.com", "getnet.com.ar",
        "modo.com.ar", "payu.com", "webpay.cl"
    )

    private fun hostMatches(host: String?, allowed: String): Boolean {
        if (host == null) return false
        return host.equals(allowed, ignoreCase = true) ||
            host.endsWith(".$allowed", ignoreCase = true)
    }

    /**
     * Decide si una URL puede cargarse dentro del propio WebView.
     * originalHost: host con el que se abrió la app por primera vez.
     */
    fun isNavigationAllowed(url: String, originalHost: String?): Boolean {
        if (!STRICT_MODE) return true

        val targetHost = Uri.parse(url).host ?: return false

        if (originalHost != null && hostMatches(targetHost, originalHost)) {
            return true
        }
        return ALWAYS_ALLOWED_HOSTS.any { hostMatches(targetHost, it) }
    }
}
