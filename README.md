# Monitor de ruido IIoT — Sound Friendly Workplace

Trabajo práctico de **Desarrollo de Aplicaciones 1** (UADE, 2026).
Equipo: Antonella Ponce (UI/UX), Victoria Rodriguez Burgos (arquitectura y persistencia) y Ramiro Carranza (dispositivo IoT y backend).

Un sensor por sector mide el ruido en forma continua, calcula el nivel equivalente de cada minuto y lo envía a un backend.
El backend calcula la **exposición del turno** según la Res. SRT 295/03 (85 dBA para 8 horas, tasa de intercambio de 3 dB),
genera alertas al 80 % y al 100 % del límite diario y las expone por una API REST que consume la app Android.

## Arquitectura

```
Sensor ESP32 ──WiFi/MQTT (QoS 1)──► Broker Mosquitto ──► Backend Ktor ◄──API REST──► App Android
 MAX9814 + SD + RTC                                         │
 (cola en la SD sin red)                                    ▼
                                                   TimescaleDB (PostgreSQL)
```

Animación del recorrido de una medición: [video/animacion_1_arquitectura.mp4](video/animacion_1_arquitectura.mp4)

## Estructura del repositorio

| Carpeta | Contenido |
| --- | --- |
| `firmware/` | Firmware del ESP32 (PlatformIO, framework Arduino) |
| `backend/` | Docker Compose (TimescaleDB, Mosquitto, Flyway), migraciones SQL y API en Ktor |
| `backend/db/migration/` | Esquema de la base y datos de demo (V1 a V5) |
| `backend/api/` | Backend en Kotlin: ingesta MQTT, reglas de alertas, API REST y simulador |
| `video/` | Animaciones explicativas para el video de la defensa |
| `app/` | App Android (se agrega cuando esté lista) |

## Dispositivo

- **Placa:** ESP32 clásica (ESP-WROOM-32).
- **Micrófono:** MAX9814 en GPIO34, ganancia 40 dB.
- **MicroSD por SPI:** CS D5, SCK D18, MISO D19, MOSI D23.
- **Reloj DS3231 por I2C:** SDA D21, SCL D22.
- **Medición:** muestreo por I2S a 17.630 muestras/s, filtro de ponderación A y LAeq, Lmax y Lmin por minuto.
- **Conectividad:** WiFi con WiFiManager (portal `MonitorRuido-nm-001`, en 192.168.4.1) y hora por NTP con respaldo en el RTC.
- **Sin red:** las mediciones se guardan en la SD y se envían en lotes al reconectar, sin duplicados.
- **Comandos por el monitor serie:** `m<IP>` cambia la IP del broker; `w` borra el WiFi guardado.

## Cómo levantar el backend

Requisitos: Docker Desktop y un JDK 21.

```
cd backend
copy .env.example .env          (en Linux/Mac: cp .env.example .env)
docker compose up -d            (base en 127.0.0.1:15432 y broker en el puerto 1883)
cd api
gradlew.bat run                 (en Linux/Mac: ./gradlew run)
```

Al arrancar, el backend aplica las migraciones, se suscribe al broker y queda escuchando en el puerto **8080**.
También arranca un **simulador** de dos sensores (`nm-002` Prensas y `nm-003` Depósito), para que la demo muestre
sectores en distintos estados. Se desactiva con la variable de entorno `SIMULADOR=0`.

Para verificar que está funcionando, abrir `http://localhost:8080/health`.

## API REST (base `/api/v1`, sin autenticación)

| Método | Ruta | Para qué |
| --- | --- | --- |
| GET | `/sectores` | Dashboard: estado, nivel, % de exposición y sensor de cada sector |
| GET | `/sectores/{id}` | Detalle del sector con turno y sensor |
| GET | `/sectores/{id}/exposicion?fecha=` | Exposición del turno y curva cada 15 minutos |
| GET | `/sectores/{id}/mediciones?desde=&hasta=&agrupar=1m\|15m\|1h` | Serie de niveles en dBA |
| GET | `/sectores/{id}/historial?desde=&hasta=` | Un resumen por turno |
| GET | `/alertas?estado=&sectorId=&actualizadoDesde=` | Alertas, las más nuevas primero |
| GET | `/alertas/{id}` | Alerta con sus intervenciones |
| POST | `/alertas/{id}/reconocer` | Reconocer una alerta (cuerpo opcional: `reconocidaPor`, `reconocidaEn`) |
| POST | `/intervenciones` | Registrar una intervención (`id` UUID generado por la app, `alertaId`, `descripcion`, `responsable`) |
| GET | `/intervenciones?sectorId=&desde=&hasta=` | Intervenciones del período |

Las fechas van en ISO 8601 UTC. Los errores tienen siempre el formato `{"error": "codigo", "mensaje": "..."}`.

## Limitaciones conocidas

- La calibración se hizo contra una app de sonómetro, no contra un sonómetro certificado.
- La dosis es la del **puesto** (sensor fijo por sector), no la de cada trabajador.
- El AGC del MAX9814 puede aplanar los niveles muy altos.
- El límite de pico de 140 dBC queda fuera de alcance.
