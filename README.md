# GPS Mocker 📍

Aplicación Android nativa desarrollada en **Kotlin** y **Jetpack Compose (Material 3)** para simular coordenadas GPS en tiempo real con soporte de rutas continuas y control joystick.

## 🚀 Características
- **Mapa interactivo OpenStreetMap (`osmdroid`)**: No requiere API keys comerciales.
- **Modos de simulación**:
  - **Punto fijo**: Toca cualquier punto en el mapa o introduce coordenadas manualmente.
  - **Ruta A ➔ B**: Define origen y destino para simular movimiento continuo entre ambos puntos.
  - **Joystick de 4 direcciones**: Muévete en tiempo real hacia Norte, Sur, Este u Oeste.
- **Cálculo de Rumbo (`bearing`)**: Calcula dinámicamente el ángulo de avance usando fórmulas geodésicas de gran círculo para que la flecha en apps de navegación (Google Maps, Waze, etc.) gire hacia donde avanza el desplazamiento.
- **Ajuste de Velocidad**: Slider dinámico de 5 km/h a 80 km/h (Caminando, Bicicleta, Ciudad y Vehículo).
- **Servicio en Primer Plano (`Foreground Service`)**: Notificación persistente con control rápido para detener la simulación.
- **Detección de Permisos**: Verifica permisos de ubicación, notificaciones y guía al usuario para activar la app en *Opciones de Desarrollador > Elegir aplicación para simular ubicación*.

## 🛠️ Tecnologías
- **Kotlin**: 1.9.24
- **Jetpack Compose & Material 3** (BOM 2024.05.00)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **OpenStreetMap (`osmdroid-android:6.1.18`)**
- **Coroutines & StateFlow**

## 📦 Compilación y APK
El repositorio cuenta con un flujo de trabajo automatizado en **GitHub Actions** (`.github/workflows/android-build.yml`). Cada push a `main` compila el APK de depuración y lo sube como un artefacto descargable.

Para compilar localmente:
```bash
./gradlew assembleDebug
```
El APK se genera en: `app/build/outputs/apk/debug/app-debug.apk`.
