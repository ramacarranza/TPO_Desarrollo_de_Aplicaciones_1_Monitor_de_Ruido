// Monitor de ruido — firmware de medición (tareas 5 + 9 del plan)
// ESP32 clásica + MAX9814 (GPIO34) + MicroSD (SPI) + RTC DS3231 (I2C)
//
// - Muestreo continuo por I2S/DMA (ADC interno) a ~16 kHz, sin perder muestras
// - Filtro de ponderación A (3 biquads, diseñado en el arranque según fs real)
// - Nivel por segundo (LA y LZ) y por minuto: LAeq, Lmax, Lmin, validez
// - Cada minuto: una línea JSON (formato del Contrato de datos) en /queue.jsonl
// - WiFi con WiFiManager (portal no bloqueante) + hora por NTP que corrige el RTC (tarea 12)
// - MQTT QoS 1: medicion, estado (heartbeat) y conexion con LWT (tarea 13)
// - Cola en la SD (store-and-forward): reenvío por /lote al volver la conexión (tarea 15)

#include <Arduino.h>
#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <RTClib.h>
#include <Preferences.h>
#include <driver/i2s.h>
#include <driver/adc.h>
#include <esp_adc_cal.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <esp_sntp.h>
#include <time.h>
#include <MQTT.h>   // 256dpi/arduino-mqtt: publica con QoS 1 y espera el PUBACK

// ======================= Configuración =======================
#define AJUSTAR_RTC 0   // 1 = grabar en el RTC la hora de compilación (en UTC). Subir una vez y volver a 0.

const char*   DEVICE_ID   = "nm-001";
const char*   FW_VERSION  = "0.4.1";
const int32_t TZ_OFFSET_S = -3 * 3600;        // Argentina (solo para mostrar la hora local)

const adc1_channel_t MIC_CH = ADC1_CHANNEL_6; // GPIO34
const uint32_t FS_NOMINAL   = 16000;
const int      SD_CS        = 5;

// ---- Calibración ----
// PROVISORIA (15 oct): CAL_A estimado en teoría; falta verificar con ruido rosa (84,7 dBA de referencia).
float CAL_Z      = 50.6;  // dB sin ponderar real (= CAL_A: el 47,3 del 12 oct leía dBA de ruido rosa, no dBZ)
float NOISE_Z_MV = 0.0;   // piso eléctrico sin ponderar: 0 hasta medirlo con el nuevo muestreo
float CAL_A      = 50.6;  // = CAL_Z + 3,3 dB (atenuación teórica del filtro A sobre ruido rosa)
float NOISE_A_MV = 0.0;   // piso eléctrico ponderado A: 0 hasta medirlo
const float L_MIN = 65.0; // debajo de esto el sensor no es confiable: se registra 65 (criterio conservador)

// ======================= Filtro A =======================
struct Biquad {
  float b0, b1, b2, a1, a2, z1 = 0, z2 = 0;
  inline float run(float x) {
    float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
};
Biquad filtroA[3];

// Transformada bilineal de (nb2 s² + nb1 s + nb0) / (da2 s² + da1 s + da0)
static void bilineal(Biquad& q, double nb2, double nb1, double nb0,
                     double da2, double da1, double da0, double fs) {
  double K = 2.0 * fs, K2 = K * K;
  // (nombres nb_/da_ porque Arduino define B0, B1, B2... como macros)
  double nB0 = nb2 * K2 + nb1 * K + nb0, nB1 = 2 * nb0 - 2 * nb2 * K2, nB2 = nb2 * K2 - nb1 * K + nb0;
  double dA0 = da2 * K2 + da1 * K + da0, dA1 = 2 * da0 - 2 * da2 * K2, dA2 = da2 * K2 - da1 * K + da0;
  q.b0 = nB0 / dA0; q.b1 = nB1 / dA0; q.b2 = nB2 / dA0; q.a1 = dA1 / dA0; q.a2 = dA2 / dA0;
  q.z1 = q.z2 = 0;
}

static double magnitud(const Biquad& q, double f, double fs) {
  double w = 2 * PI * f / fs;
  double c1 = cos(w), s1 = sin(w), c2 = cos(2 * w), s2 = sin(2 * w);
  double nr = q.b0 + q.b1 * c1 + q.b2 * c2, ni = -(q.b1 * s1 + q.b2 * s2);
  double dr = 1 + q.a1 * c1 + q.a2 * c2,    di = -(q.a1 * s1 + q.a2 * s2);
  return sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
}

// Polos de la curva A (IEC 61672): 20.6, 107.7, 737.9 y 12194 Hz; ganancia 0 dB a 1 kHz
static void disenarFiltroA(double fs) {
  const double w1 = 2 * PI * 20.598997, w2 = 2 * PI * 107.65265,
               w3 = 2 * PI * 737.86223, w4 = 2 * PI * 12194.217;
  bilineal(filtroA[0], 1, 0, 0, 1, 2 * w1, w1 * w1, fs);
  bilineal(filtroA[1], 1, 0, 0, 1, w2 + w3, w2 * w3, fs);
  bilineal(filtroA[2], 0, 0, 1, 1, 2 * w4, w4 * w4, fs);
  double g = 1;
  for (auto& q : filtroA) g *= magnitud(q, 1000, fs);
  filtroA[0].b0 /= g; filtroA[0].b1 /= g; filtroA[0].b2 /= g;
}

// ======================= ADC por I2S =======================
double   mvPorCuenta = 0.8;
uint32_t fsReal      = FS_NOMINAL;  // muestras/s que entrega realmente el DMA
uint32_t decim       = 1;           // se promedian 'decim' muestras crudas -> 1 muestra procesada
uint32_t fsProc      = FS_NOMINAL;  // muestras/s después de decimar (~16 kHz)

static void iniciarADC() {
  i2s_config_t cfg = {};
  cfg.mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN);
  cfg.sample_rate          = FS_NOMINAL;
  cfg.bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags     = 0;
  cfg.dma_buf_count        = 8;
  cfg.dma_buf_len          = 512;
  cfg.use_apll             = false;
  i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(MIC_CH, ADC_ATTEN_DB_12);
  i2s_set_adc_mode(ADC_UNIT_1, MIC_CH);
  i2s_adc_enable(I2S_NUM_0);

  // Pendiente cuentas -> mV con la calibración de fábrica (eFuse), como analogReadMilliVolts
  esp_adc_cal_characteristics_t ch;
  esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_12, ADC_WIDTH_BIT_12, 1100, &ch);
  mvPorCuenta = (esp_adc_cal_raw_to_voltage(3000, &ch) - esp_adc_cal_raw_to_voltage(1000, &ch)) / 2000.0;
}

// Mide cuántas muestras por segundo entrega realmente el DMA
static uint32_t medirFs() {
  static uint16_t b[512];
  size_t bytes;
  for (int i = 0; i < 8; i++) i2s_read(I2S_NUM_0, b, sizeof(b), &bytes, portMAX_DELAY);  // vaciar
  uint64_t total = 0;
  uint32_t t0 = micros();
  while (micros() - t0 < 2000000) {
    i2s_read(I2S_NUM_0, b, sizeof(b), &bytes, portMAX_DELAY);
    total += bytes / 2;
  }
  uint32_t dt = micros() - t0;
  return (uint32_t)(total * 1000000.0 / dt + 0.5);
}

// ======================= Tarea de muestreo =======================
struct Segundo {
  double varZ;     // mV² (sin ponderar, sin DC)
  double varA;     // mV² (ponderado A)
  float  satFrac;  // fracción de muestras saturadas
  uint32_t ms;     // millis() al terminar el segundo (para fecharlo bien aunque se procese tarde)
};
QueueHandle_t colaSeg;

static void tareaMuestreo(void*) {
  static uint16_t buf[512];
  double sZ = 0, sZ2 = 0, sA2 = 0;
  uint32_t n = 0, nRaw = 0, nsat = 0, calentamiento = fsProc;  // descarta 1 s mientras el filtro se estabiliza
  uint32_t acc = 0, k = 0;
  for (;;) {
    size_t bytes = 0;
    i2s_read(I2S_NUM_0, buf, sizeof(buf), &bytes, portMAX_DELAY);
    int cnt = bytes / 2;
    for (int i = 0; i + 1 < cnt; i += 2) {  // el ADC por I2S entrega las muestras de a pares invertidos
      uint16_t t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t;
    }
    for (int i = 0; i < cnt; i++) {
      uint16_t raw = buf[i] & 0x0FFF;          // 12 bits de dato; los 4 superiores son el canal
      nRaw++;
      if (raw <= 5 || raw >= 4090) nsat++;
      acc += raw;
      if (++k < decim) continue;               // decimación: promedio de 'decim' muestras
      float x = (float)acc / decim - 2048.0f;  // centrado para no perder precisión
      acc = 0; k = 0;
      float y = x;
      for (auto& q : filtroA) y = q.run(y);
      if (calentamiento) { calentamiento--; continue; }
      sZ += x; sZ2 += (double)x * x; sA2 += (double)y * y;
      if (++n >= fsProc) {
        double m = sZ / n;
        Segundo s;
        s.varZ    = (sZ2 / n - m * m) * mvPorCuenta * mvPorCuenta;
        s.varA    = (sA2 / n) * mvPorCuenta * mvPorCuenta;
        s.satFrac = (float)nsat / nRaw;
        s.ms      = millis();
        xQueueSend(colaSeg, &s, 0);
        sZ = sZ2 = sA2 = 0; n = nRaw = nsat = 0;
      }
    }
  }
}

// ======================= Estado general =======================
RTC_DS3231 rtc;
bool rtcOk = false, sdOk = false;
bool rtcValido = false;            // el RTC tiene una hora confiable (no perdió la alimentación)

// Red y hora
WiFiManager wm;
volatile bool ntpEvento = false;   // lo levanta el callback de SNTP en cada sincronización
bool     ntpOk       = false;
uint32_t ultimoNtpMs = 0;
bool     rtcPerdioHora = false;        // el RTC arrancó con la bandera de pérdida de hora (pila)
bool     hayDeriva     = false;
int32_t  derivaInicial = 0;            // diferencia RTC - NTP en la primera sincronización (s)

static void alSincronizarNtp(struct timeval*) { ntpEvento = true; }

// ---- MQTT ----
char mqttHost[40] = "192.168.1.7";       // IP de la PC con Docker; se cambia desde el portal o con la tecla m
const int MQTT_PORT = 1883;
WiFiClient   netMqtt;
MQTTClient   mqtt(4096);                 // buffer de 4 KB (alcanza para los lotes de la tarea 15)
WiFiManagerParameter paramMqtt("mqtt", "IP del broker MQTT", mqttHost, 39);
char tMedicion[48], tEstado[48], tConexion[48], tLote[48];
uint32_t ultimoIntentoMqtt = 0, ultimoEstadoMs = 0;
bool micOk = true;

static void guardarMqttHost(const char* host) {
  strlcpy(mqttHost, host, sizeof(mqttHost));
  Preferences pr; pr.begin("ruido", false); pr.putString("mqtt", mqttHost); pr.end();
  mqtt.disconnect();
  mqtt.setHost(mqttHost, MQTT_PORT);
  Serial.printf("MQTT: broker configurado en %s:%d\n", mqttHost, MQTT_PORT);
}
static void alGuardarPortal() { guardarMqttHost(paramMqtt.getValue()); }

static const char* fuenteHora();
static void publicarEstado();

// Conecta (o reconecta cada 5 s) al broker; al conectar publica online + estado
static void mqttMantener() {
  if (WiFi.status() != WL_CONNECTED || mqtt.connected()) return;
  static uint32_t espera = 5000;          // 5 s; si falla, se duplica hasta 60 s (no bloquear la medición)
  if (millis() - ultimoIntentoMqtt < espera) return;
  ultimoIntentoMqtt = millis();
  mqtt.setWill(tConexion, "offline", true, 1);   // LWT: el broker publica offline si nos caemos
  if (mqtt.connect(DEVICE_ID)) {
    mqtt.publish(tConexion, "online", true, 1);
    Serial.printf("MQTT: conectado a %s:%d\n", mqttHost, MQTT_PORT);
    espera = 5000;
    publicarEstado();
  } else {
    Serial.printf("MQTT: no se pudo conectar a %s:%d (error %d); reintento en %u s\n",
                  mqttHost, MQTT_PORT, (int)mqtt.lastError(), (unsigned)(min(espera * 2, (uint32_t)60000) / 1000));
    espera = min(espera * 2, (uint32_t)60000);
  }
}

static const char* fuenteHora() {
  if (ntpOk && millis() - ultimoNtpMs < 24UL * 3600UL * 1000UL) return "ntp";
  if (rtcOk && rtcValido) return "rtc";
  return "none";
}
uint32_t bootCount = 0, seq = 0;

// Minuto en curso
uint32_t minutoClave = 0;  // epoch/60 del minuto abierto (0 = ninguno)
double   sumaE = 0;        // suma de energías 10^(L/10) de los segundos válidos
uint16_t segTot = 0, segVal = 0;
float    lmax = -1, lmin = 999;

static float nivel(double var, float ruidoMv, float cal) {
  double sig = var - (double)ruidoMv * ruidoMv;  // resta del piso de ruido eléctrico
  if (sig < 1e-6) return -999;
  return 10.0 * log10(sig) + cal;
}

static uint32_t ahoraUTC() {
  if (rtcOk && rtcValido) return rtc.now().unixtime();
  time_t t = time(nullptr);
  if (t > 1700000000) return (uint32_t)t;  // hora del sistema (NTP) aunque no haya RTC
  return millis() / 1000;                  // sin hora real: tsrc = none
}

// ======================= Cola en la SD (tarea 15) =======================
// /queue.jsonl : una línea JSON por minuto (todas las mediciones, en orden)
// /queue.off   : posición (bytes) de la primera línea todavía NO confirmada por el broker
// Al volver la conexión se envía lo pendiente en lotes de hasta 20 por ruido/v1/{id}/lote (QoS 1)
// y la posición avanza solo después del PUBACK. Si algo se reenvía dos veces, la base lo ignora.
const char* COLA     = "/queue.jsonl";
const char* COLA_OFF = "/queue.off";
uint32_t colaOffset = 0;      // bytes ya confirmados
uint32_t pendientes = 0;      // mediciones guardadas que el broker todavía no confirmó
uint32_t ultimoLoteMs = 0;

static uint32_t tamCola() {
  File f = SD.open(COLA, FILE_READ);
  if (!f) return 0;
  uint32_t t = f.size(); f.close();
  return t;
}

static void guardarOffset() {
  File f = SD.open(COLA_OFF, FILE_WRITE);   // FILE_WRITE reescribe el archivo
  if (f) { f.print(colaOffset); f.close(); }
}

static void iniciarCola() {
  if (!sdOk) return;
  if (!SD.exists(COLA_OFF)) {
    // Primera vez con cola: el archivo viejo (pruebas anteriores) se guarda aparte y no se reenvía
    if (SD.exists(COLA)) {
      char nombre[32];
      snprintf(nombre, sizeof(nombre), "/hist_%u.jsonl", bootCount);
      SD.rename(COLA, nombre);
      Serial.printf("Cola: archivo anterior guardado como %s (no se reenvia)\n", nombre);
    }
    colaOffset = 0;
    guardarOffset();
  } else {
    File f = SD.open(COLA_OFF, FILE_READ);
    colaOffset = f ? (uint32_t)f.parseInt() : 0;
    if (f) f.close();
  }
  pendientes = 0;
  File f = SD.open(COLA, FILE_READ);
  if (f) {
    if (colaOffset > f.size()) colaOffset = f.size();
    f.seek(colaOffset);
    while (f.available()) {
      String l = f.readStringUntil('\n');
      if (l.length() > 2) pendientes++;
    }
    f.close();
  }
  Serial.printf("Cola: %u mediciones pendientes de envio\n", pendientes);
}

static void guardarLinea(const char* linea) {
  if (!sdOk) return;
  File f = SD.open(COLA, FILE_APPEND);
  if (f) { f.println(linea); f.close(); }
  else   { sdOk = false; Serial.println("SD: error de escritura"); }
}

// Cuando todo está confirmado y el archivo creció, se vacía para que no crezca sin límite
static void compactarCola() {
  if (colaOffset > 64UL * 1024UL && colaOffset >= tamCola()) {
    SD.remove(COLA);
    colaOffset = 0;
    guardarOffset();
    Serial.println("Cola: archivo compactado");
  }
}

// Envía un lote por segundo (como máximo) mientras haya pendientes y conexión.
// Lotes de 10 mediciones (~1,8 KB): con señal débil se confirman al primer intento.
static const int LOTE_MAX = 10;
static void vaciarCola() {
  if (!sdOk || pendientes == 0 || !mqtt.connected()) return;
  if (millis() - ultimoLoteMs < 1000) return;
  ultimoLoteMs = millis();

  File f = SD.open(COLA, FILE_READ);
  if (!f) return;
  f.seek(colaOffset);
  static char lote[3800];
  int n = snprintf(lote, sizeof(lote), "{\"v\":1,\"device_id\":\"%s\",\"items\":[", DEVICE_ID);
  int items = 0;
  uint32_t nuevoOffset = colaOffset;
  while (items < LOTE_MAX && f.available()) {
    String l = f.readStringUntil('\n');
    l.trim();
    uint32_t pos = f.position();
    if (l.length() < 2) { nuevoOffset = pos; continue; }
    if (n + (int)l.length() + 4 >= (int)sizeof(lote)) break;   // no entra: queda para el próximo lote
    n += snprintf(lote + n, sizeof(lote) - n, "%s%s", items ? "," : "", l.c_str());
    items++;
    nuevoOffset = pos;
  }
  f.close();

  if (items == 0) {               // solo había líneas vacías: ya está al día
    colaOffset = nuevoOffset; guardarOffset(); pendientes = 0;
    return;
  }
  snprintf(lote + n, sizeof(lote) - n, "]}");
  if (mqtt.publish(tLote, lote, false, 1)) {    // bloquea hasta el PUBACK (máx. 5 s)
    colaOffset = nuevoOffset;
    guardarOffset();
    pendientes = (pendientes > (uint32_t)items) ? pendientes - items : 0;
    Serial.printf("Cola: lote de %d mediciones enviado y confirmado; quedan %u\n", items, pendientes);
    if (pendientes == 0) compactarCola();
  } else {
    Serial.println("Cola: no se pudo enviar el lote; se reintenta");
  }
}

static void publicarEstado() {
  if (!mqtt.connected()) return;
  char js[256];
  snprintf(js, sizeof(js),
           "{\"v\":1,\"device_id\":\"%s\",\"ts\":%u,\"fw\":\"%s\",\"uptime\":%u,\"rssi\":%d,"
           "\"queue\":%u,\"mic_ok\":%s,\"sd_ok\":%s,\"tsrc\":\"%s\"}",
           DEVICE_ID, ahoraUTC(), FW_VERSION, (unsigned)(millis() / 1000), WiFi.RSSI(),
           (unsigned)pendientes, micOk ? "true" : "false", sdOk ? "true" : "false", fuenteHora());
  mqtt.publish(tEstado, js, true, 0);   // QoS 0 + retain, según el contrato
  ultimoEstadoMs = millis();
}

static void cerrarMinuto() {
  if (segTot == 0) return;
  if (segVal == 0) {
    Serial.println(">> Minuto descartado: ningún segundo válido");
  } else {
    float laeq  = 10.0 * log10(sumaE / segVal);
    float valid = (float)segVal / segTot;
    char linea[200];
    snprintf(linea, sizeof(linea),
             "{\"v\":1,\"device_id\":\"%s\",\"boot\":%u,\"seq\":%u,\"ts\":%u,\"tsrc\":\"%s\","
             "\"period\":%u,\"laeq\":%.1f,\"lmax\":%.1f,\"lmin\":%.1f,\"valid\":%.2f}",
             DEVICE_ID, bootCount, seq++, minutoClave * 60, fuenteHora(),
             segTot, laeq, lmax, lmin, valid);
    Serial.print(">> MINUTO "); Serial.println(linea);
    bool alDia = (pendientes == 0);
    guardarLinea(linea);                                   // siempre queda en la SD
    bool enviada = mqtt.connected() && mqtt.publish(tMedicion, linea, false, 1);
    if (sdOk) {
      if (enviada && alDia) { colaOffset = tamCola(); guardarOffset(); }   // confirmada: avanza la cola
      else pendientes++;                                   // queda pendiente (se reenvía por /lote)
    }
    if (enviada) Serial.println("   MQTT: medicion enviada y confirmada (PUBACK)");
    else         Serial.printf("   MQTT: sin conexion -> en cola en la SD (%u pendientes)\n", (unsigned)pendientes);
    if (WiFi.status() == WL_CONNECTED)
      Serial.printf("   WiFi: %s  RSSI=%d dBm  tsrc=%s\n", WiFi.SSID().c_str(), WiFi.RSSI(), fuenteHora());
    else
      Serial.printf("   WiFi: sin conexion  tsrc=%s\n", fuenteHora());
    if (hayDeriva || rtcPerdioHora)
      Serial.printf("   RTC al arrancar: %s, diferencia con NTP = %+d s\n",
                    rtcPerdioHora ? "PERDIO LA HORA (revisar pila)" : "hora conservada", derivaInicial);
  }
  sumaE = 0; segTot = segVal = 0; lmax = -1; lmin = 999;
}

// ======================= setup / loop =======================
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== Monitor de ruido - firmware de medicion ===");

  Preferences prefs;
  prefs.begin("ruido", false);
  bootCount = prefs.getUInt("boot", 0) + 1;
  prefs.putUInt("boot", bootCount);
  prefs.end();
  Serial.printf("boot = %u\n", bootCount);

  Wire.begin(21, 22);
  rtcOk = rtc.begin();
  if (rtcOk) {
#if AJUSTAR_RTC
    rtc.adjust(DateTime(F(__DATE__), F(__TIME__)) - TimeSpan(TZ_OFFSET_S));
    Serial.println("RTC: ajustado a la hora de compilacion (UTC). Volver AJUSTAR_RTC a 0.");
    rtcValido = true;
#else
    rtcValido = !rtc.lostPower();
    rtcPerdioHora = !rtcValido;
    if (!rtcValido) Serial.println("RTC: perdio la hora (pila?) -> se espera NTP; mientras tanto tsrc = none");
#endif
    Serial.println("RTC: OK");
  } else {
    Serial.println("RTC: NO encontrado -> tsrc = none");
  }

  sdOk = SD.begin(SD_CS);
  Serial.println(sdOk ? "SD: OK" : "SD: falla");
  iniciarCola();

  // WiFi: intenta la red guardada (10 s). Si no hay, abre un portal de configuración
  // NO bloqueante: el equipo sigue midiendo mientras tanto.
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  wm.setConfigPortalBlocking(false);
  wm.setConnectTimeout(10);
  {
    Preferences pr; pr.begin("ruido", true);
    String h = pr.getString("mqtt", mqttHost); pr.end();
    strlcpy(mqttHost, h.c_str(), sizeof(mqttHost));
    paramMqtt.setValue(mqttHost, 39);
  }
  wm.addParameter(&paramMqtt);           // aparece en la página "Configure WiFi" del portal
  wm.setSaveParamsCallback(alGuardarPortal);
  wm.setSaveConfigCallback(alGuardarPortal);
  char ap[32];
  snprintf(ap, sizeof(ap), "MonitorRuido-%s", DEVICE_ID);
  if (wm.autoConnect(ap))
    Serial.printf("WiFi: conectado a %s, IP %s\n", WiFi.SSID().c_str(), WiFi.localIP().toString().c_str());
  else
    Serial.printf("WiFi: sin red. Portal abierto: conectate a la red '%s' y entra a 192.168.4.1\n", ap);

  // NTP en UTC; cada sincronización (al conectar y luego cada 1 h) corrige el RTC
  sntp_set_time_sync_notification_cb(alSincronizarNtp);
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  snprintf(tMedicion, sizeof(tMedicion), "ruido/v1/%s/medicion", DEVICE_ID);
  snprintf(tEstado,   sizeof(tEstado),   "ruido/v1/%s/estado",   DEVICE_ID);
  snprintf(tConexion, sizeof(tConexion), "ruido/v1/%s/conexion", DEVICE_ID);
  snprintf(tLote,     sizeof(tLote),     "ruido/v1/%s/lote",     DEVICE_ID);
  mqtt.begin(mqttHost, MQTT_PORT, netMqtt);
  mqtt.setKeepAlive(60);
  mqtt.setTimeout(5000);                  // espera máxima del PUBACK (5 s: tolera WiFi con señal débil)
  Serial.printf("MQTT: broker %s:%d (cambiar con: m<IP> + Enter)\n", mqttHost, MQTT_PORT);

  iniciarADC();
  fsReal = medirFs();
  decim  = fsReal / FS_NOMINAL;               // hacia abajo: fs procesada >= 16 kHz (mejor respuesta en agudos)
  if (decim < 1) decim = 1;
  fsProc = (fsReal + decim / 2) / decim;
  disenarFiltroA(fsProc);
  Serial.printf("ADC: fs medida = %u muestras/s, decimacion x%u -> fs = %u, %.4f mV/cuenta\n",
                fsReal, decim, fsProc, mvPorCuenta);

  colaSeg = xQueueCreate(40, sizeof(Segundo));   // 40 s de margen si la red bloquea el loop
  xTaskCreatePinnedToCore(tareaMuestreo, "muestreo", 4096, NULL, 5, NULL, 1);
  Serial.println("Midiendo... (1 linea por segundo, 1 JSON por minuto). Tecla w = borrar WiFi guardado");
  Serial.setTimeout(15000);
}

void loop() {
  wm.process();  // atiende el portal de configuración si está abierto
  mqtt.loop();
  mqttMantener();
  vaciarCola();      // tarea 15: reenvía lo pendiente de la SD
  if (mqtt.connected() && millis() - ultimoEstadoMs > 5UL * 60UL * 1000UL) publicarEstado();  // heartbeat 5 min

  if (ntpEvento) {
    ntpEvento = false;
    time_t t = time(nullptr);
    if (rtcOk) {
      int32_t d = (int32_t)(rtc.now().unixtime() - (uint32_t)t);
      if (!hayDeriva) { hayDeriva = true; derivaInicial = d; }
      Serial.printf("NTP: el RTC tenia %+d s de diferencia\n", d);
      rtc.adjust(DateTime((uint32_t)t)); rtcValido = true;
    }
    ntpOk = true;
    ultimoNtpMs = millis();
    DateTime loc((uint32_t)t + TZ_OFFSET_S);
    Serial.printf("NTP: hora sincronizada (%02d:%02d:%02d local); RTC actualizado\n",
                  loc.hour(), loc.minute(), loc.second());
  }

  int cmd = Serial.available() ? Serial.read() : -1;
  if (cmd == 'm') {
    String ip = Serial.readStringUntil('\n'); ip.trim();
    if (ip.length() > 6) guardarMqttHost(ip.c_str());
  }
  if (cmd == 'w') {
    Serial.println("Borrando credenciales WiFi y reiniciando...");
    wm.resetSettings();
    delay(500);
    ESP.restart();
  }

  Segundo s;
  if (xQueueReceive(colaSeg, &s, pdMS_TO_TICKS(50)) != pdTRUE) return;

  // Hora en que TERMINÓ ese segundo, no la hora en que se procesa (la red puede demorar el loop)
  uint32_t t = ahoraUTC() - (millis() - s.ms) / 1000;
  uint32_t clave = t / 60;
  if (minutoClave != 0 && (clave != minutoClave || segTot >= 60)) cerrarMinuto();
  minutoClave = clave;

  float rmsZ = sqrt(s.varZ), rmsA = sqrt(s.varA);
  float lz = nivel(s.varZ, NOISE_Z_MV, CAL_Z);
  float la = nivel(s.varA, NOISE_A_MV, CAL_A);
  bool saturado = s.satFrac > 0.01;
  bool plano    = rmsZ < 2.0;  // micrófono desconectado o sin señal
  bool valido   = !saturado && !plano;
  micOk = !plano;
  float laUsado = (la < L_MIN) ? L_MIN : la;

  segTot++;
  if (valido) {
    segVal++;
    sumaE += pow(10.0, laUsado / 10.0);
    if (laUsado > lmax) lmax = laUsado;
    if (laUsado < lmin) lmin = laUsado;
  }

  DateTime loc(t + TZ_OFFSET_S);
  Serial.printf("%02d:%02d:%02d  LA=%5.1f  LZ=%5.1f  rmsZ=%6.2fmV  rmsA=%6.2fmV%s%s%s\n",
                loc.hour(), loc.minute(), loc.second(),
                la < -100 ? 0 : la, lz < -100 ? 0 : lz, rmsZ, rmsA,
                la < L_MIN ? "  (<65)" : "", saturado ? "  SATURADO" : "", plano ? "  PLANO" : "");
}
