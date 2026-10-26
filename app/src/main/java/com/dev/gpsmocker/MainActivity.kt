package com.dev.gpsmocker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dev.gpsmocker.service.MockLocationService
import com.dev.gpsmocker.ui.theme.GpsMockerTheme
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

enum class MapPinMode {
    POINT_A,
    POINT_B
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Configuration.getInstance().userAgentValue = packageName

        setContent {
            GpsMockerTheme {
                GpsMockerScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsMockerScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Estado reactivo del servicio
    val serviceState by MockLocationService.serviceState.collectAsState()
    val isRunning = serviceState is MockLocationService.ServiceState.Running
    val runningState = serviceState as? MockLocationService.ServiceState.Running

    // Coordenadas Punto A (Origen / Posición actual)
    var latitudeText by remember { mutableStateOf("40.416775") }
    var longitudeText by remember { mutableStateOf("-3.703790") }

    // Coordenadas Punto B (Destino)
    var targetPointB by remember { mutableStateOf<GeoPoint?>(null) }

    // Velocidad en km/h
    var speedKmh by remember { mutableFloatStateOf(30f) }

    // Modo de colocación de pines en el mapa
    var pinMode by remember { mutableStateOf(MapPinMode.POINT_A) }

    // Rumbo actual
    var currentBearing by remember { mutableFloatStateOf(0f) }

    // Diálogo de error / Opciones de Desarrollador
    var showDevOptionsDialog by remember { mutableStateOf(false) }
    var dialogErrorMessage by remember { mutableStateOf("") }

    // Referencias a los componentes de osmdroid
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var markerAInstance by remember { mutableStateOf<Marker?>(null) }
    var markerBInstance by remember { mutableStateOf<Marker?>(null) }
    var polylineInstance by remember { mutableStateOf<Polyline?>(null) }

    // Sincronizar UI con el progreso en tiempo real del servicio
    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is MockLocationService.ServiceState.Running -> {
                latitudeText = String.format(Locale.US, "%.6f", state.latitude)
                longitudeText = String.format(Locale.US, "%.6f", state.longitude)
                currentBearing = state.bearing
                speedKmh = state.speedKmh

                val currentPos = GeoPoint(state.latitude, state.longitude)
                markerAInstance?.position = currentPos

                if (state.targetLatitude != null && state.targetLongitude != null) {
                    val dest = GeoPoint(state.targetLatitude, state.targetLongitude)
                    targetPointB = dest
                    polylineInstance?.setPoints(listOf(currentPos, dest))
                } else {
                    targetPointB = null
                    polylineInstance?.setPoints(emptyList())
                    markerBInstance?.let { mapViewInstance?.overlays?.remove(it) }
                    markerBInstance = null
                }
                mapViewInstance?.invalidate()
            }
            is MockLocationService.ServiceState.Error -> {
                dialogErrorMessage = state.message
                showDevOptionsDialog = true
            }
            is MockLocationService.ServiceState.Stopped -> {
                currentBearing = 0f
            }
        }
    }

    // Permisos
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun startSimulationWithValidation() {
        val lat = latitudeText.toDoubleOrNull()
        val lng = longitudeText.toDoubleOrNull()

        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Toast.makeText(
                context,
                "Coordenadas inválidas. Latitud [-90, 90], Longitud [-180, 180].",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        MockLocationService.startMocking(
            context = context,
            latitude = lat,
            longitude = lng,
            targetLatitude = targetPointB?.latitude,
            targetLongitude = targetPointB?.longitude,
            speedKmh = speedKmh
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLocationGranted || coarseLocationGranted) {
            startSimulationWithValidation()
        } else {
            Toast.makeText(
                context,
                "Se requieren permisos de ubicación para simular el GPS.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun checkPermissionsAndStart() {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasFine && hasNotifications) {
            startSimulationWithValidation()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "GPS Mocker",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. MAPA INTERACTIVO (osmdroid)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)

                        val initialPoint = GeoPoint(
                            latitudeText.toDoubleOrNull() ?: 40.416775,
                            longitudeText.toDoubleOrNull() ?: -3.703790
                        )
                        controller.setCenter(initialPoint)

                        // Polilínea de ruta entre A y B
                        val polyline = Polyline().apply {
                            outlinePaint.color = AndroidColor.parseColor("#3F51B5")
                            outlinePaint.strokeWidth = 10f
                        }
                        overlays.add(polyline)
                        polylineInstance = polyline

                        // Marcador inicial para Punto A
                        val markerA = Marker(this).apply {
                            position = initialPoint
                            title = "Punto A (Origen)"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(markerA)
                        markerAInstance = markerA

                        // Receptor de eventos táctiles en el mapa
                        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                if (pinMode == MapPinMode.POINT_A) {
                                    latitudeText = String.format(Locale.US, "%.6f", p.latitude)
                                    longitudeText = String.format(Locale.US, "%.6f", p.longitude)
                                    markerA.position = p

                                    if (targetPointB != null) {
                                        polyline.setPoints(listOf(p, targetPointB!!))
                                    }

                                    if (MockLocationService.serviceState.value is MockLocationService.ServiceState.Running) {
                                        MockLocationService.startMocking(
                                            ctx,
                                            p.latitude,
                                            p.longitude,
                                            targetPointB?.latitude,
                                            targetPointB?.longitude,
                                            speedKmh
                                        )
                                    }
                                } else {
                                    // Asignar o mover Punto B (Destino)
                                    targetPointB = p
                                    if (markerBInstance == null) {
                                        val markerB = Marker(this@apply).apply {
                                            position = p
                                            title = "Punto B (Destino)"
                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        }
                                        overlays.add(markerB)
                                        markerBInstance = markerB
                                    } else {
                                        markerBInstance?.position = p
                                    }

                                    val curA = GeoPoint(
                                        latitudeText.toDoubleOrNull() ?: p.latitude,
                                        longitudeText.toDoubleOrNull() ?: p.longitude
                                    )
                                    polyline.setPoints(listOf(curA, p))

                                    if (MockLocationService.serviceState.value is MockLocationService.ServiceState.Running) {
                                        MockLocationService.setTarget(ctx, p.latitude, p.longitude)
                                    }
                                }
                                invalidate()
                                return true
                            }

                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        })
                        overlays.add(0, eventsOverlay)

                        mapViewInstance = this
                    }
                }
            )

            // Manejo de ciclo de vida del mapa
            DisposableEffect(lifecycleOwner, mapViewInstance) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> mapViewInstance?.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapViewInstance?.onPause()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    mapViewInstance?.onDetach()
                }
            }

            // CONTROLES SUPERPUESTOS EN LA PARTE SUPERIOR DEL MAPA
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Selector de modo de clic en el mapa (Punto A vs Punto B)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = pinMode == MapPinMode.POINT_A,
                            onClick = { pinMode = MapPinMode.POINT_A },
                            label = { Text("Punto A") },
                            leadingIcon = {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )

                        FilterChip(
                            selected = pinMode == MapPinMode.POINT_B,
                            onClick = { pinMode = MapPinMode.POINT_B },
                            label = { Text("Destino B") },
                            leadingIcon = {
                                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )

                        if (targetPointB != null) {
                            IconButton(
                                onClick = {
                                    targetPointB = null
                                    polylineInstance?.setPoints(emptyList())
                                    markerBInstance?.let { mapViewInstance?.overlays?.remove(it) }
                                    markerBInstance = null
                                    mapViewInstance?.invalidate()
                                    if (isRunning) {
                                        MockLocationService.clearTarget(context)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Eliminar Destino", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // Botón para centrar en el marcador actual
                    FloatingActionButton(
                        onClick = {
                            val lat = latitudeText.toDoubleOrNull()
                            val lng = longitudeText.toDoubleOrNull()
                            if (lat != null && lng != null) {
                                mapViewInstance?.controller?.animateTo(GeoPoint(lat, lng))
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Centrar en A",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // CONTROL TIPO JOYSTICK (4 FLECHAS: NORTE, SUR, ESTE, OESTE)
            Card(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(6.dp)
                ) {
                    // NORTE (0°)
                    IconButton(
                        onClick = {
                            if (!isRunning) checkPermissionsAndStart()
                            MockLocationService.moveDirection(context, 0f)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Norte")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // OESTE (270°)
                        IconButton(
                            onClick = {
                                if (!isRunning) checkPermissionsAndStart()
                                MockLocationService.moveDirection(context, 270f)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Oeste")
                        }

                        // Indicador de rumbo con flecha giratoria
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = "Rumbo",
                                modifier = Modifier
                                    .size(22.dp)
                                    .rotate(currentBearing),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // ESTE (90°)
                        IconButton(
                            onClick = {
                                if (!isRunning) checkPermissionsAndStart()
                                MockLocationService.moveDirection(context, 90f)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Este")
                        }
                    }

                    // SUR (180°)
                    IconButton(
                        onClick = {
                            if (!isRunning) checkPermissionsAndStart()
                            MockLocationService.moveDirection(context, 180f)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Sur")
                    }
                }
            }

            // 2. TARJETA FLOTANTE INFERIOR
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Estado e información de movimiento
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        color = if (isRunning) Color(0xFF2E7D32) else Color.Gray,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRunning) {
                                    if (runningState?.isNavigating == true) "En Ruta a Destino" else "Simulación Activa"
                                } else "Detenida",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) Color(0xFF2E7D32) else Color.Gray
                            )
                        }

                        // Rumbo en grados
                        Text(
                            text = "Rumbo: ${currentBearing.toInt()}°",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // CONTROL DE VELOCIDAD CON SLIDER
                    val speedCategory = when {
                        speedKmh < 10f -> "Caminando"
                        speedKmh < 25f -> "Bicicleta"
                        speedKmh < 55f -> "Ciudad"
                        else -> "Vehículo / Carretera"
                    }
                    val speedIcon = when {
                        speedKmh < 10f -> Icons.Default.DirectionsWalk
                        speedKmh < 25f -> Icons.Default.DirectionsBike
                        else -> Icons.Default.DirectionsCar
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = speedIcon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Velocidad: ${speedKmh.toInt()} km/h",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = speedCategory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Slider(
                        value = speedKmh,
                        onValueChange = { newSpeed ->
                            speedKmh = newSpeed
                            if (isRunning) {
                                MockLocationService.updateSpeed(context, newSpeed)
                            }
                        },
                        valueRange = 5f..80f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // Campos de texto para Latitud y Longitud
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = latitudeText,
                            onValueChange = { newLat ->
                                latitudeText = newLat
                                val lat = newLat.toDoubleOrNull()
                                val lng = longitudeText.toDoubleOrNull()
                                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                                    val point = GeoPoint(lat, lng)
                                    markerAInstance?.position = point
                                    mapViewInstance?.invalidate()
                                    if (isRunning) {
                                        MockLocationService.startMocking(
                                            context,
                                            lat,
                                            lng,
                                            targetPointB?.latitude,
                                            targetPointB?.longitude,
                                            speedKmh
                                        )
                                    }
                                }
                            },
                            label = { Text("Latitud A") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next
                            )
                        )

                        OutlinedTextField(
                            value = longitudeText,
                            onValueChange = { newLng ->
                                longitudeText = newLng
                                val lat = latitudeText.toDoubleOrNull()
                                val lng = newLng.toDoubleOrNull()
                                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                                    val point = GeoPoint(lat, lng)
                                    markerAInstance?.position = point
                                    mapViewInstance?.invalidate()
                                    if (isRunning) {
                                        MockLocationService.startMocking(
                                            context,
                                            lat,
                                            lng,
                                            targetPointB?.latitude,
                                            targetPointB?.longitude,
                                            speedKmh
                                        )
                                    }
                                }
                            },
                            label = { Text("Longitud A") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botón Principal Grande
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (isRunning) {
                                MockLocationService.stopMocking(context)
                            } else {
                                checkPermissionsAndStart()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = if (isRunning) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "Detener Simulación" else "Iniciar Simulación",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // 3. DIÁLOGO DE CONFIGURACIÓN DE DESARROLLADOR (SecurityException)
    if (showDevOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showDevOptionsDialog = false },
            title = {
                Text(
                    text = "Configuración de Desarrollador Requerida",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "$dialogErrorMessage\n\nPara continuar:\n1. Ve a Opciones de Desarrollador.\n2. Busca \"Elegir aplicación para simular ubicación\" (o Mock Location App).\n3. Selecciona \"GPS Mocker\"."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDevOptionsDialog = false
                        openDeveloperSettings(context)
                    }
                ) {
                    Text("Abrir Opciones de Desarrollador")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDevOptionsDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun openDeveloperSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "No fue posible abrir los ajustes automáticamente. Ábrelos de forma manual.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
