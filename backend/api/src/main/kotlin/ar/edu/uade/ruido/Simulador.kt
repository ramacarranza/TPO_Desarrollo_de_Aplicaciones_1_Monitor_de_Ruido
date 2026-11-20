package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariDataSource
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.ln

/**
 * Simulador de sensores para la demo: nm-002 (Prensas) y nm-003 (Depósito).
 *
 * Cada sensor simulado se conecta al broker como un ESP32 real (con su LWT "offline") y publica
 * una medición por minuto y su estado cada 5 minutos, así que pasa por la misma ingesta y las
 * mismas reglas de alertas que el sensor físico nm-001.
 *
 * Al arrancar precarga los minutos del turno de hoy que falten, para que cada sector quede
 * en un estado distinto: Prensas ~110 % (CRITICO) y Depósito ~85 % (ATENCION).
 * Si la hora actual cae fuera del turno de esos sectores, mueve el turno para que la demo tenga datos.
 *
 * Se desactiva con la variable de entorno SIMULADOR=0.
 */
object Simulador {
    private val log = LoggerFactory.getLogger("Simulador")
    private val azar = Random()

    private data class Perfil(val deviceId: String, val objetivoPct: Double, val nivelVivo: Double)

    private val perfiles = listOf(
        Perfil("nm-002", objetivoPct = 110.0, nivelVivo = 92.0),   // Prensas: CRITICO
        Perfil("nm-003", objetivoPct = 85.0, nivelVivo = 76.0)     // Depósito: ATENCION
    )

    private data class Turno(val sectorId: Int, val sector: String, val inicio: LocalTime, val jornadaMin: Int)

    fun iniciar(db: HikariDataSource) {
        if ((System.getenv("SIMULADOR") ?: "1") == "0") {
            log.info("Simulador desactivado (SIMULADOR=0)")
            return
        }
        val url = System.getenv("MQTT_URL") ?: "tcp://127.0.0.1:1883"
        for (p in perfiles) {
            thread(isDaemon = true, name = "sim-${p.deviceId}") {
                try {
                    correr(db, url, p)
                } catch (e: Exception) {
                    log.warn("Simulador ${p.deviceId} detenido: ${e.message}")
                }
            }
        }
    }

    private fun correr(db: HikariDataSource, url: String, p: Perfil) {
        val turno = prepararTurno(db, p) ?: return
        precargar(db, p, turno)

        val base = "ruido/v1/${p.deviceId}"
        val c = MqttClient(url, "sim-${p.deviceId}", MemoryPersistence())
        val opciones = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 30
            setWill("$base/conexion", "offline".toByteArray(), 1, true)
        }
        while (!c.isConnected) {
            try {
                c.connect(opciones)
            } catch (e: Exception) {
                log.warn("Simulador ${p.deviceId}: sin broker en $url (${e.message}); reintento en 5 s")
                Thread.sleep(5000)
            }
        }
        publicar(c, "$base/conexion", "online", retener = true)
        log.info("Simulador ${p.deviceId} (${turno.sector}) conectado; publica una medición por minuto a ~${p.nivelVivo} dBA")

        val arranque = System.currentTimeMillis()
        val boot = (arranque / 60_000).toInt()   // distinto en cada arranque del simulador
        var seq = 0
        var ultimoEstado = 0L
        while (true) {
            if (c.isConnected && System.currentTimeMillis() - ultimoEstado > 5 * 60_000) {
                val uptime = (System.currentTimeMillis() - arranque) / 1000
                publicar(c, "$base/estado",
                    """{"v":1,"device_id":"${p.deviceId}","ts":${Instant.now().epochSecond},"fw":"sim-1.0","uptime":$uptime,""" +
                    """"rssi":${-45 - azar.nextInt(15)},"queue":0,"mic_ok":true,"sd_ok":true,"tsrc":"ntp"}""")
                ultimoEstado = System.currentTimeMillis()
            }
            // Espera al cambio de minuto (+2 s) y publica el minuto que terminó, igual que el ESP32
            val ahora = System.currentTimeMillis()
            val proximo = (ahora / 60_000 + 1) * 60_000
            Thread.sleep(proximo - ahora + 2000)
            if (!c.isConnected) continue
            val ts = proximo / 1000 - 60
            val laeq = r1(p.nivelVivo + azar.nextGaussian() * 1.5)
            val lmax = r1(laeq + 5 + azar.nextDouble() * 2)
            val lmin = r1(laeq - 7 - azar.nextDouble() * 2)
            publicar(c, "$base/medicion",
                """{"v":1,"device_id":"${p.deviceId}","boot":$boot,"seq":$seq,"ts":$ts,"tsrc":"ntp","period":60,""" +
                """"laeq":$laeq,"lmax":$lmax,"lmin":$lmin,"valid":1.0}""")
            seq++
        }
    }

    private fun publicar(c: MqttClient, topic: String, texto: String, retener: Boolean = false) {
        try {
            c.publish(topic, MqttMessage(texto.toByteArray()).apply { qos = 1; isRetained = retener })
        } catch (e: Exception) {
            log.warn("Simulador: no se pudo publicar en $topic (${e.message})")
        }
    }

    /** Devuelve el turno del sector del sensor; si ahora cae fuera de ese turno, lo mueve para incluir la hora actual. */
    private fun prepararTurno(db: HikariDataSource, p: Perfil): Turno? = db.connection.use { cn ->
        val t = cn.prepareStatement(
            """SELECT s.id, s.nombre, s.turno_inicio, s.jornada_min
                 FROM dispositivo d JOIN sector s ON s.id = d.sector_id WHERE d.id = ? AND d.activo"""
        ).use { ps ->
            ps.setString(1, p.deviceId)
            ps.executeQuery().use { rs ->
                if (rs.next()) Turno(rs.getInt("id"), rs.getString("nombre"),
                                     rs.getObject("turno_inicio", LocalTime::class.java), rs.getInt("jornada_min"))
                else null
            }
        }
        if (t == null) {
            log.warn("Simulador: ${p.deviceId} no existe, está inactivo o no tiene sector; no se simula")
            return@use null
        }
        val ahoraMin = LocalTime.now(ZONA).toSecondOfDay() / 60
        val inicioMin = t.inicio.toSecondOfDay() / 60
        if (ahoraMin >= inicioMin && ahoraMin < inicioMin + t.jornadaMin) return@use t

        // Turno nuevo: empieza unas 4 horas antes de ahora (en hora entera) y no cruza la medianoche
        val nuevoMin = (Math.floorDiv(ahoraMin - 240, 60) * 60).coerceIn(0, maxOf(0, 1440 - t.jornadaMin))
        val nuevo = LocalTime.ofSecondOfDay(nuevoMin * 60L)
        cn.prepareStatement("UPDATE sector SET turno_inicio = ?, actualizado_en = now() WHERE id = ?").use { ps ->
            ps.setObject(1, nuevo); ps.setInt(2, t.sectorId); ps.executeUpdate()
        }
        log.info("Simulador: turno de ${t.sector} movido a $nuevo–${nuevo.plusMinutes(t.jornadaMin.toLong())} para que la demo tenga datos")
        t.copy(inicio = nuevo)
    }

    /** Completa los minutos del turno de hoy que no tienen medición, con el nivel justo para llegar al objetivo. */
    private fun precargar(db: HikariDataSource, p: Perfil, t: Turno) {
        val inicio = ZonedDateTime.of(LocalDate.now(ZONA), t.inicio, ZONA).toInstant()
        val hasta = Instant.now().truncatedTo(ChronoUnit.MINUTES)   // el minuto en curso lo publica el simulador
        val minutosTurno = ChronoUnit.MINUTES.between(inicio, hasta)
        if (minutosTurno <= 0) return

        db.connection.use { cn ->
            val (pctActual, medidos) = cn.prepareStatement(
                """SELECT COALESCE(sum(periodo_s::float8 / 60.0 / 480.0 * power(2.0::float8, (laeq::float8 - 85.0) / 3.0)), 0) * 100,
                          count(*)
                     FROM medicion WHERE device_id = ? AND ts >= ? AND ts < ?"""
            ).use { ps ->
                ps.setString(1, p.deviceId); ps.instante(2, inicio); ps.instante(3, hasta)
                ps.executeQuery().use { rs -> rs.next(); rs.getDouble(1) to rs.getLong(2) }
            }
            val faltan = minutosTurno - medidos
            if (faltan <= 0) {
                log.info("Simulador: ${t.sector} ya tiene el turno completo (${r1(pctActual)} %)")
                return
            }
            // Nivel que hace falta en los minutos que faltan: D% = 100 · (min/480) · 2^((L − 85)/3)
            val falta = maxOf(p.objetivoPct - pctActual, 0.5)
            val nivel = (85.0 + 3.0 * ln(falta * 480.0 / (100.0 * faltan)) / ln(2.0)).coerceIn(60.0, 105.0)

            val filas = cn.prepareStatement(
                """INSERT INTO medicion (ts, device_id, boot, seq, periodo_s, laeq, lmax, lmin, validez, hora_confiable)
                   SELECT g, ?, 0, (extract(epoch FROM g) / 60)::int, 60,
                          round((?::float8 + (random() - 0.5) * 3)::numeric, 1),
                          round((?::float8 + 5 + random() * 2)::numeric, 1),
                          round((?::float8 - 7 - random() * 2)::numeric, 1),
                          1.0, true
                     FROM generate_series(?::timestamptz, ?::timestamptz - interval '1 minute', interval '1 minute') g
                    WHERE NOT EXISTS (SELECT 1 FROM medicion m WHERE m.device_id = ? AND m.ts = g)
                   ON CONFLICT DO NOTHING"""
            ).use { ps ->
                ps.setString(1, p.deviceId)
                ps.setDouble(2, nivel); ps.setDouble(3, nivel); ps.setDouble(4, nivel)
                ps.instante(5, inicio); ps.instante(6, hasta)
                ps.setString(7, p.deviceId)
                ps.executeUpdate()
            }
            log.info("Simulador: ${t.sector} precargado con $filas minutos a ~${r1(nivel)} dBA (objetivo ${p.objetivoPct} % del turno)")
        }
    }
}
