package com.webvisor.app

import android.app.Application

/**
 * Clase Application de Web Visor.
 * Se deja preparada por si en el futuro se necesita inicializar
 * librerías globales (analytics, crash reporting, etc.).
 */
class WebVisorApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
