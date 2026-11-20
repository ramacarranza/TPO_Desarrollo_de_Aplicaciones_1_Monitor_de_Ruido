package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.concurrent.thread

/**
 * Tarea 14: el backend se suscribe a ruido/v1/+/# (QoS 1, sesión persistente),
 * valida cada mensaje según el Contrato de datos y lo guarda en la base.
 * Los reenvíos no duplican gracias a INSERT ... ON CONFLICT DO NOTHING.
 */
object Ingesta {
    private val log = LoggerFactory.getLogger("Ingesta")
    private val json = Json { ignoreUnknownKeys = true }
    private val TOPIC = Regex("^ruido/v1/([^/]+)/(medicion|lote|estado|conexion|evento)$")
    private var cliente: MqttClient? = null
    private val ultimoEstadoConexion = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ---- Mensajes del contrato ----
    @Serializable
    data class Medicion(
        val v: Int = 1, val device_id: String? = null, val boot: Int, val seq: Int, val ts: Long,
        val tsrc: String = "none", val period: Int = 60, val laeq: Double,
        val lmax: Double? = null, val lmin: Double? = null, val valid: Double? = null
    )
    @Serializable
    data class Lote(val v: Int = 1, val device_id: String? = null, val items: List<Medicion>)
    @Serializable
    data class Estado(
        val v: Int = 1, val device_id: String? = null, val ts: Long? = null, val fw: String? = null,
        val uptime: Long? = null, val rssi: Int? = null, val queue: Int? = null,
        val mic_ok: Boolean? = null, val sd_ok: Boolean? = null, val tsrc: String? = null
    )
    @Serializable
    data class Evento(
        val v: Int = 1, val device_id: String? = null, val boot: Int, val seq: Int, val ts: Long,
        val tsrc: String = "none", val type: String, val code: String? = null, val detail: String? = null
    )

    fun conectado(): Boolean = cliente?.isConnected == true

    fun iniciar(db: HikariDataSource) {
        val url = System.getenv("MQTT_URL") ?: "tcp://127.0.0.1:1883"
        val c = MqttClient(url, "ruido-backend", MemoryPersistence())
        cliente = c
        val opciones = MqttConnectOptions().apply {
            isCleanSession = false          // sesión persistente: el broker guarda lo que llegue si Ktor está caído
            isAutomaticReconnect = true
            keepAliveInterval = 60
            connectionTimeout = 10
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                log.info("MQTT: conectado a $serverURI${if (reconnect) " (reconexion)" else ""}")
                if (reconnect) thread(isDaemon = true) { suscribir(c) }
            }
            override fun connectionLost(cause: Throwable?) {
                log.warn("MQTT: conexion perdida (${cause?.message}); reintentando...")
            }
            override fun messageArrived(topic: String, msg: MqttMessage) {
                // Si esto lanza una excepción, Paho corta la conexión: por eso se atrapa todo
                try {
                    procesar(db, topic, String(msg.payload, Charsets.UTF_8))
                } catch (e: Exception) {
                    log.warn("Descartado [$topic]: ${e.message}")
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        // Primera conexión en segundo plano: si el broker no está, reintenta sin frenar la API
        thread(isDaemon = true, name = "mqtt-conexion") {
            while (!c.isConnected) {
                try {
                    c.connect(opciones)
                    suscribir(c)
                } catch (e: Exception) {
                    log.warn("MQTT: no se pudo conectar a $url (${e.message}); reintento en 5 s")
                    Thread.sleep(5000)
                }
            }
        }
    }

    private fun suscribir(c: MqttClient) {
        c.subscribe("ruido/v1/+/#", 1)
        log.info("MQTT: suscripto a ruido/v1/+/# (QoS 1)")
    }

    // ---- Procesamiento por tipo de mensaje ----
    private fun procesar(db: HikariDataSource, topic: String, payload: String) {
        val m = TOPIC.matchEntire(topic) ?: return
        val (dev, tipo) = m.destructured
        when (tipo) {
            "medicion" -> {
                val x = json.decodeFromString<Medicion>(payload)
                validarDispositivo(db, dev, x.device_id)
                // Tarea 17: con cada medición nueva en vivo se revisa la exposición del turno
                if (guardarMedicion(db, dev, x)) Alertas.evaluarExposicion(db, dev, x.ts)
            }
            "lote" -> {
                val l = json.decodeFromString<Lote>(payload)
                validarDispositivo(db, dev, l.device_id)
                // Cada ítem se valida por separado: uno inválido no hace perder el resto del lote
                var nuevas = 0; var duplicadas = 0; var invalidas = 0
                for (item in l.items) {
                    try { if (guardarMedicion(db, dev, item, silencioso = true)) nuevas++ else duplicadas++ }
                    catch (e: Exception) { invalidas++; log.warn("Lote $dev: item seq=${item.seq} descartado: ${e.message}") }
                }
                log.info("Lote $dev: ${l.items.size} mediciones -> $nuevas nuevas, $duplicadas duplicadas (ignoradas), $invalidas invalidas")
            }
            "estado" -> {
                val e = json.decodeFromString<Estado>(payload)
                validarDispositivo(db, dev, e.device_id)
                guardarEstado(db, dev, e)
                Alertas.resolverPorDispositivo(db, dev, "OFFLINE")   // si manda estado, está vivo
            }
            "conexion" -> {
                validarDispositivo(db, dev, null)
                val online = payload.trim() == "online"
                db.connection.use { cn ->
                    cn.prepareStatement(
                        """INSERT INTO dispositivo_estado (device_id, conectado, ultimo_estado) VALUES (?, ?, now())
                           ON CONFLICT (device_id) DO UPDATE SET conectado = EXCLUDED.conectado, ultimo_estado = now()"""
                    ).use { st -> st.setString(1, dev); st.setBoolean(2, online); st.executeUpdate() }
                }
                // Solo se registra el cambio de estado (tras un corte pueden llegar decenas de LWT repetidos)
                if (ultimoEstadoConexion.put(dev, online) != online)
                    log.info("Conexion $dev: ${if (online) "online" else "OFFLINE"}")
                if (online) Alertas.resolverPorDispositivo(db, dev, "OFFLINE")
                else Alertas.crearPorDispositivo(db, dev, "OFFLINE")
            }
            "evento" -> {
                val e = json.decodeFromString<Evento>(payload)
                validarDispositivo(db, dev, e.device_id)
                db.connection.use { cn ->
                    cn.prepareStatement(
                        """INSERT INTO evento (ts, device_id, boot, seq, tipo, codigo, detalle)
                           VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"""
                    ).use { st ->
                        st.setObject(1, utc(e.ts)); st.setString(2, dev); st.setInt(3, e.boot); st.setInt(4, e.seq)
                        st.setString(5, e.type); st.setString(6, e.code); st.setString(7, e.detail)
                        st.executeUpdate()
                    }
                }
                log.info("Evento $dev: ${e.type} ${e.code ?: ""}")
                when ("MIC_FAULT") { e.type, e.code -> Alertas.crearPorDispositivo(db, dev, "MIC_FAULT") }
                when ("MIC_RECOVERED") { e.type, e.code -> Alertas.resolverPorDispositivo(db, dev, "MIC_FAULT") }
            }
        }
    }

    /** device_id del payload igual al del topic, y dispositivo dado de alta y activo. */
    private fun validarDispositivo(db: HikariDataSource, deTopic: String, dePayload: String?) {
        require(dePayload == null || dePayload == deTopic) { "device_id $dePayload no coincide con el topic ($deTopic)" }
        val activo = db.connection.use { cn ->
            cn.prepareStatement("SELECT activo FROM dispositivo WHERE id = ?").use { st ->
                st.setString(1, deTopic)
                st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
            }
        }
        require(activo != null) { "dispositivo desconocido: $deTopic" }
        require(activo) { "dispositivo inactivo: $deTopic" }
    }

    /** Devuelve true si la medición era nueva, false si era un duplicado. */
    private fun guardarMedicion(db: HikariDataSource, dev: String, x: Medicion, silencioso: Boolean = false): Boolean {
        require(x.v == 1) { "version de mensaje no soportada: ${x.v}" }
        require(x.laeq in 20.0..130.0) { "laeq fuera de rango: ${x.laeq}" }
        require(x.period in 1..60) { "period invalido: ${x.period}" }
        val filas = db.connection.use { cn ->
            cn.prepareStatement(
                """INSERT INTO medicion (ts, device_id, boot, seq, periodo_s, laeq, lmax, lmin, validez, hora_confiable)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"""
            ).use { st ->
                st.setObject(1, utc(x.ts)); st.setString(2, dev); st.setInt(3, x.boot); st.setInt(4, x.seq)
                st.setShort(5, x.period.toShort()); st.setDouble(6, x.laeq)
                numeroONulo(st, 7, x.lmax); numeroONulo(st, 8, x.lmin)
                if (x.valid == null) st.setNull(9, Types.REAL) else st.setFloat(9, x.valid.toFloat())
                st.setBoolean(10, x.tsrc != "none")
                st.executeUpdate()
            }
        }
        if (!silencioso)
            log.info("Medicion $dev boot=${x.boot} seq=${x.seq} LAeq=${x.laeq} -> ${if (filas == 1) "guardada" else "duplicada (ignorada)"}")
        return filas == 1
    }

    private fun guardarEstado(db: HikariDataSource, dev: String, e: Estado) {
        db.connection.use { cn ->
            cn.prepareStatement(
                """INSERT INTO dispositivo_estado
                     (device_id, conectado, ultimo_estado, rssi, cola_pendiente, mic_ok, sd_ok, fuente_hora, uptime_s)
                   VALUES (?, true, now(), ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (device_id) DO UPDATE SET
                     conectado = true, ultimo_estado = now(), rssi = EXCLUDED.rssi,
                     cola_pendiente = EXCLUDED.cola_pendiente, mic_ok = EXCLUDED.mic_ok, sd_ok = EXCLUDED.sd_ok,
                     fuente_hora = EXCLUDED.fuente_hora, uptime_s = EXCLUDED.uptime_s"""
            ).use { st ->
                st.setString(1, dev)
                st.setObject(2, e.rssi, Types.INTEGER); st.setObject(3, e.queue, Types.INTEGER)
                st.setObject(4, e.mic_ok, Types.BOOLEAN); st.setObject(5, e.sd_ok, Types.BOOLEAN)
                st.setString(6, e.tsrc); st.setObject(7, e.uptime, Types.BIGINT)
                st.executeUpdate()
            }
            if (e.fw != null) cn.prepareStatement("UPDATE dispositivo SET fw = ? WHERE id = ?").use { st ->
                st.setString(1, e.fw); st.setString(2, dev); st.executeUpdate()
            }
        }
        log.info("Estado $dev: rssi=${e.rssi} mic_ok=${e.mic_ok} sd_ok=${e.sd_ok} tsrc=${e.tsrc}")
    }

    private fun utc(epoch: Long) = OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC)

    private fun numeroONulo(st: PreparedStatement, i: Int, v: Double?) {
        if (v == null) st.setNull(i, Types.NUMERIC) else st.setDouble(i, v)
    }
}
