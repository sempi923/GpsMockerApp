package com.dev.gpsmocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dev.gpsmocker.MainActivity
import com.dev.gpsmocker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MockLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockJob: Job? = null

    private lateinit var locationManager: LocationManager

    @Volatile
    private var currentLatitude: Double = 0.0

    @Volatile
    private var currentLongitude: Double = 0.0

    @Volatile
    private var targetLatitude: Double? = null

    @Volatile
    private var targetLongitude: Double? = null

    @Volatile
    private var currentSpeedKmh: Float = 30f // Velocidad por defecto

    @Volatile
    private var currentBearing: Float = 0f

    @Volatile
    private var isNavigatingRoute: Boolean = false

    private var notificationUpdateCounter = 0

    sealed class ServiceState {
        object Stopped : ServiceState()
        data class Running(
            val latitude: Double,
            val longitude: Double,
            val targetLatitude: Double? = null,
            val targetLongitude: Double? = null,
            val speedKmh: Float = 30f,
            val bearing: Float = 0f,
            val isNavigating: Boolean = false,
            val distanceToTargetMeters: Float? = null
        ) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mock_location_channel"
        private const val CHANNEL_NAME = "Servicio de Ubicación Simulada"

        const val ACTION_START = "com.dev.gpsmocker.action.START"
        const val ACTION_STOP = "com.dev.gpsmocker.action.STOP"
        const val ACTION_UPDATE_SPEED = "com.dev.gpsmocker.action.UPDATE_SPEED"
        const val ACTION_SET_TARGET = "com.dev.gpsmocker.action.SET_TARGET"
        const val ACTION_CLEAR_TARGET = "com.dev.gpsmocker.action.CLEAR_TARGET"
        const val ACTION_MOVE_DIRECTION = "com.dev.gpsmocker.action.MOVE_DIRECTION"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_TARGET_LAT = "extra_target_lat"
        const val EXTRA_TARGET_LNG = "extra_target_lng"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"
        const val EXTRA_BEARING = "extra_bearing"

        private val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        /**
         * Inicia o actualiza la simulación con coordenadas y velocidad.
         */
        fun startMocking(
            context: Context,
            latitude: Double,
            longitude: Double,
            targetLatitude: Double? = null,
            targetLongitude: Double? = null,
            speedKmh: Float = 30f
        ) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LATITUDE, latitude)
                putExtra(EXTRA_LONGITUDE, longitude)
                if (targetLatitude != null && targetLongitude != null) {
                    putExtra(EXTRA_TARGET_LAT, targetLatitude)
                    putExtra(EXTRA_TARGET_LNG, targetLongitude)
                }
                putExtra(EXTRA_SPEED_KMH, speedKmh)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Configura el Punto B (Destino) para navegación por ruta.
         */
        fun setTarget(context: Context, targetLat: Double, targetLng: Double) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_SET_TARGET
                putExtra(EXTRA_TARGET_LAT, targetLat)
                putExtra(EXTRA_TARGET_LNG, targetLng)
            }
            context.startService(intent)
        }

        /**
         * Cancela la navegación hacia el destino.
         */
        fun clearTarget(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_CLEAR_TARGET
            }
            context.startService(intent)
        }

        /**
         * Actualiza dinámicamente la velocidad en km/h.
         */
        fun updateSpeed(context: Context, speedKmh: Float) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_UPDATE_SPEED
                putExtra(EXTRA_SPEED_KMH, speedKmh)
            }
            context.startService(intent)
        }

        /**
         * Desplaza la ubicación según el rumbo del Joystick (0 = Norte, 90 = Este, 180 = Sur, 270 = Oeste).
         */
        fun moveDirection(context: Context, bearing: Float) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_MOVE_DIRECTION
                putExtra(EXTRA_BEARING, bearing)
            }
            context.startService(intent)
        }

        /**
         * Detiene la simulación y libera los proveedores.
         */
        fun stopMocking(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val speed = intent.getFloatExtra(EXTRA_SPEED_KMH, currentSpeedKmh)

                val targetLat = if (intent.hasExtra(EXTRA_TARGET_LAT)) intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0) else null
                val targetLng = if (intent.hasExtra(EXTRA_TARGET_LNG)) intent.getDoubleExtra(EXTRA_TARGET_LNG, 0.0) else null

                currentSpeedKmh = speed
                targetLatitude = targetLat
                targetLongitude = targetLng
                isNavigatingRoute = targetLat != null && targetLng != null

                startMockingProcess(lat, lng)
            }
            ACTION_SET_TARGET -> {
                targetLatitude = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0)
                targetLongitude = intent.getDoubleExtra(EXTRA_TARGET_LNG, 0.0)
                isNavigatingRoute = true
            }
            ACTION_CLEAR_TARGET -> {
                targetLatitude = null
                targetLongitude = null
                isNavigatingRoute = false
            }
            ACTION_UPDATE_SPEED -> {
                currentSpeedKmh = intent.getFloatExtra(EXTRA_SPEED_KMH, currentSpeedKmh)
            }
            ACTION_MOVE_DIRECTION -> {
                val bearing = intent.getFloatExtra(EXTRA_BEARING, 0f)
                handleJoystickStep(bearing)
            }
            ACTION_STOP -> {
                stopMockingProcess()
            }
        }
        return START_STICKY
    }

    private fun handleJoystickStep(bearing: Float) {
        currentBearing = bearing
        targetLatitude = null
        targetLongitude = null
        isNavigatingRoute = false

        // Calcular distancia de paso según velocidad (mínimo 3 metros para buena respuesta táctil)
        val stepDistance = maxOf(3.0, (currentSpeedKmh / 3.6) * 0.8)
        val (nextLat, nextLng) = computeDestinationPoint(
            currentLatitude,
            currentLongitude,
            stepDistance,
            currentBearing
        )
        currentLatitude = nextLat
        currentLongitude = nextLng

        try {
            injectMockLocation(currentLatitude, currentLongitude, currentBearing, currentSpeedKmh / 3.6f)
            _serviceState.value = ServiceState.Running(
                latitude = currentLatitude,
                longitude = currentLongitude,
                targetLatitude = null,
                targetLongitude = null,
                speedKmh = currentSpeedKmh,
                bearing = currentBearing,
                isNavigating = false,
                distanceToTargetMeters = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error al mover con Joystick: ${e.localizedMessage}")
        }
    }

    private fun startMockingProcess(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude

        // 1. Iniciar servicio en primer plano
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Configurar Test Providers
        try {
            setupTestProviders()
            emitRunningState()
        } catch (e: SecurityException) {
            val errorMsg = "Permiso denegado: debes seleccionar 'GPS Mocker' como aplicación de ubicación de prueba en Opciones de Desarrollador."
            Log.e(TAG, errorMsg, e)
            _serviceState.value = ServiceState.Error(errorMsg)
            stopSelf()
            return
        } catch (e: Exception) {
            val errorMsg = "Error al configurar proveedores de prueba: ${e.localizedMessage}"
            Log.e(TAG, errorMsg, e)
            _serviceState.value = ServiceState.Error(errorMsg)
            stopSelf()
            return
        }

        // 3. Iniciar bucle de interpolación y emisión cada 800 ms
        if (mockJob == null || mockJob?.isActive == false) {
            mockJob = serviceScope.launch {
                while (isActive) {
                    try {
                        stepNavigation()
                    } catch (e: SecurityException) {
                        val errorMsg = "Se revocó el permiso de ubicación de prueba en Opciones de Desarrollador."
                        Log.e(TAG, errorMsg, e)
                        _serviceState.value = ServiceState.Error(errorMsg)
                        stopMockingProcess()
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en bucle de simulación: ${e.localizedMessage}", e)
                    }
                    delay(800)
                }
            }
        }
    }

    private fun stepNavigation() {
        var distanceToTarget: Float? = null
        val currentLoc = Location("temp").apply {
            latitude = currentLatitude
            longitude = currentLongitude
        }

        val targetLat = targetLatitude
        val targetLng = targetLongitude

        if (isNavigatingRoute && targetLat != null && targetLng != null) {
            val targetLoc = Location("temp").apply {
                latitude = targetLat
                longitude = targetLng
            }

            val distance = currentLoc.distanceTo(targetLoc)
            distanceToTarget = distance

            // Rumbo hacia el objetivo
            val bearing = (currentLoc.bearingTo(targetLoc) + 360f) % 360f
            currentBearing = bearing

            // Distancia que recorre en este ciclo de 800 ms (0.8s)
            val stepDistanceMeters = (currentSpeedKmh / 3.6) * 0.8

            if (distance <= stepDistanceMeters) {
                // Llegamos al destino
                currentLatitude = targetLat
                currentLongitude = targetLng
                targetLatitude = null
                targetLongitude = null
                isNavigatingRoute = false
                distanceToTarget = 0f
            } else {
                // Interpolar siguiente posición siguiendo la geodésica
                val (nextLat, nextLng) = computeDestinationPoint(
                    currentLatitude,
                    currentLongitude,
                    stepDistanceMeters,
                    currentBearing
                )
                currentLatitude = nextLat
                currentLongitude = nextLng
            }
        }

        val speedMps = if (isNavigatingRoute) (currentSpeedKmh / 3.6f) else 0f
        injectMockLocation(currentLatitude, currentLongitude, currentBearing, speedMps)

        _serviceState.value = ServiceState.Running(
            latitude = currentLatitude,
            longitude = currentLongitude,
            targetLatitude = targetLatitude,
            targetLongitude = targetLongitude,
            speedKmh = currentSpeedKmh,
            bearing = currentBearing,
            isNavigating = isNavigatingRoute,
            distanceToTargetMeters = distanceToTarget
        )

        // Actualizar notificación periódicamente (cada ~3.2 segundos) para no saturar NotificationManager
        notificationUpdateCounter++
        if (notificationUpdateCounter >= 4) {
            notificationUpdateCounter = 0
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun emitRunningState() {
        _serviceState.value = ServiceState.Running(
            latitude = currentLatitude,
            longitude = currentLongitude,
            targetLatitude = targetLatitude,
            targetLongitude = targetLongitude,
            speedKmh = currentSpeedKmh,
            bearing = currentBearing,
            isNavigating = isNavigatingRoute,
            distanceToTargetMeters = null
        )
    }

    private fun setupTestProviders() {
        for (provider in providers) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {}

            locationManager.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
        }
    }

    private fun injectMockLocation(latitude: Double, longitude: Double, bearing: Float, speedMps: Float) {
        val currentTime = System.currentTimeMillis()
        val elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        for (provider in providers) {
            val mockLocation = Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                this.altitude = 15.0
                this.accuracy = 3.0f
                this.bearing = bearing
                this.speed = speedMps
                this.time = currentTime
                this.elapsedRealtimeNanos = elapsedRealtimeNanos
            }
            locationManager.setTestProviderLocation(provider, mockLocation)
        }
    }

    private fun removeTestProviders() {
        for (provider in providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "Error removiendo test provider $provider: ${e.localizedMessage}")
            }
        }
    }

    private fun stopMockingProcess() {
        mockJob?.cancel()
        mockJob = null
        removeTestProviders()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        if (_serviceState.value !is ServiceState.Error) {
            _serviceState.value = ServiceState.Stopped
        }

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente para simulación de coordenadas GPS"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedLat = String.format("%.5f", currentLatitude)
        val formattedLng = String.format("%.5f", currentLongitude)
        val statusText = if (isNavigatingRoute) {
            "Ruta activa • ${currentSpeedKmh.toInt()} km/h • Rumbo ${currentBearing.toInt()}°"
        } else {
            "Posición: $formattedLat, $formattedLng • ${currentSpeedKmh.toInt()} km/h"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Mocker Activo")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Calcula el nuevo punto geográfico (latitud, longitud) desplazándose una distancia determinada
     * sobre una esfera siguiendo un rumbo dado (fórmula geodésica de gran círculo).
     */
    private fun computeDestinationPoint(
        lat: Double,
        lng: Double,
        distanceMeters: Double,
        bearingDegrees: Float
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0 // Radio de la Tierra en metros
        val delta = distanceMeters / earthRadius
        val theta = Math.toRadians(bearingDegrees.toDouble())
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lng)

        val sinPhi1 = Math.sin(phi1)
        val cosPhi1 = Math.cos(phi1)
        val sinDelta = Math.sin(delta)
        val cosDelta = Math.cos(delta)

        val sinPhi2 = sinPhi1 * cosDelta + cosPhi1 * sinDelta * Math.cos(theta)
        val phi2 = Math.asin(sinPhi2)

        val y = Math.sin(theta) * sinDelta * cosPhi1
        val x = cosDelta - sinPhi1 * sinPhi2
        val lambda2 = lambda1 + Math.atan2(y, x)

        val resultLat = Math.toDegrees(phi2)
        val resultLng = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0

        return Pair(resultLat, resultLng)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeTestProviders()
        if (_serviceState.value !is ServiceState.Error) {
            _serviceState.value = ServiceState.Stopped
        }
        Log.d(TAG, "MockLocationService destruido")
    }
}
