package com.guardianai

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Clase Application principal de Guardian IA
 * Inicializa todos los componentes del sistema
 */
@HiltAndroidApp
class GuardianApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: GuardianWorkerFactory
    
    override fun onCreate() {
        super.onCreate()
        
        // Inicializar logging
        initializeLogging()
        
        // Inicializar WorkManager
        initializeWorkManager()
        
        // Inicializar componentes de Guardian IA
        initializeGuardianComponents()
        
        Timber.i("Guardian IA Application initialized successfully")
    }
    
    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // En producción, usar un árbol personalizado que no registre información sensible
            Timber.plant(ReleaseTree())
        }
    }
    
    private fun initializeWorkManager() {
        // WorkManager se inicializa automáticamente con la configuración proporcionada
        Timber.d("WorkManager initialized with custom configuration")
    }
    
    private fun initializeGuardianComponents() {
        try {
            // Los componentes se inicializarán cuando sean inyectados por primera vez
            Timber.d("Guardian IA components ready for initialization")
        } catch (e: Exception) {
            Timber.e(e, "Error initializing Guardian IA components")
        }
    }
    
    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
    }
    
    /**
     * Árbol de logging personalizado para builds de release
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Solo registrar errores y warnings en producción
            if (priority >= android.util.Log.WARN) {
                // Aquí podrías enviar logs a un servicio de crash reporting
                // como Firebase Crashlytics o Bugsnag
            }
        }
    }
}

