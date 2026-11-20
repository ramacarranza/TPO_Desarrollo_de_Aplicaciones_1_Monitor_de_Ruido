package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Tarea 17: reglas y acciones del TPO.
 *  - Generación automática: ATENCION y CRITICO (exposición del turno), OFFLINE (LWT o sin señal por 10 min)
 *    y MIC_FAULT (evento del dispositivo).
 *  - Acciones desde la app: reconocer una alerta y registrar intervenciones (idempotentes).
 * Sin login (corrección del docente): las acciones guardan un nombre libre opcional.
 */

// ---------- Cuerpos que recibe la API ----------

@Serializable data class ReconocerDto(val reconocidaEn: String? = null, val reconocidaPor: String? = null)

@Serializable data class NuevaIntervencionDto(
    val id: String, val alertaId: Long, val descripcion: String,
    val realizadaEn: String? = null, val responsable: String? = null
)

val jsonEntrada = Json { ignoreUnknownKeys = true }

inline fun <reified T> leerCuerpo(texto: String): T? {
    if (texto.isBlank()) return null
    return try {
        jsonEntrada.decodeFromString<T>(texto)
    } catch (e: Exception) {
        throw invalido("JSON inválido: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
    }
}

private fun nombreLibre(valor: String?): String? = valor?.trim()?.takeIf { it.isNotEmpty() }?.take(100)

// ---------- Rutas ----------

fun Route.rutasAlertas(db: HikariDataSource) {
    val lectura = Lectura(db)
    val acciones = AccionesAlertas(db)
    route("/api/v1") {
        post("/alertas/{id}/reconocer") {
            call.conErrores {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw invalido("El id debe ser un número")
                val cuerpo = leerCuerpo<ReconocerDto>(call.receiveText()) ?: ReconocerDto()
                acciones.reconocer(id, cuerpo)
                call.respond(lectura.alerta(id))
            }
        }
        post("/intervenciones") {
            call.conErrores {
                val cuerpo = leerCuerpo<NuevaIntervencionDto>(call.receiveText())
                    ?: throw invalido("Falta el cuerpo de la intervención")
                val (dto, nueva) = acciones.registrarIntervencion(cuerpo)
                call.respond(if (nueva) HttpStatusCode.Created else HttpStatusCode.OK, dto)
            }
        }
        get("/intervenciones") {
            call.conErrores {
                val q = call.request.queryParameters
                call.respond(acciones.listarIntervenciones(q["sectorId"], q["desde"], q["hasta"], q["actualizadoDesde"]))
            }
        }
    }
}

// ---------- Acciones de la app ----------

class AccionesAlertas(private val db: HikariDataSource) {

    private fun <T> transaccion(f: (Connection) -> T): T = db.connection.use { cn ->
        cn.autoCommit = false
        try {
            val r = f(cn)
            cn.commit()
            r
        } catch (e: Exception) {
            cn.rollback()
            throw e
        } finally {
            cn.autoCommit = true
        }
    }

    private fun bloquearAlerta(cn: Connection, id: Long): Pair<String, String> =
        cn.prepareStatement("SELECT tipo, estado FROM alerta WHERE id = ? FOR UPDATE").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("tipo") to rs.getString("estado") else throw noEncontrado("No existe la alerta $id")
            }
        }

    /**
     * ATENCION pendiente -> RESUELTA. CRITICO, OFFLINE o MIC_FAULT pendiente -> RECONOCIDA.
     * Si ya estaba reconocida o resuelta, no cambia nada (la app puede reenviar sin problema).
     */
    fun reconocer(id: Long, b: ReconocerDto) {
        val cuando = b.reconocidaEn?.let { minOf(parseInstante(it, "reconocidaEn"), Instant.now()) } ?: Instant.now()
        val quien = nombreLibre(b.reconocidaPor)
        transaccion<Unit> { cn ->
            val (tipo, estado) = bloquearAlerta(cn, id)
            if (estado != "PENDIENTE") return@transaccion
            val sql = if (tipo == "ATENCION")
                """UPDATE alerta SET estado = 'RESUELTA', reconocida_en = ?, reconocida_por_nombre = ?,
                          resuelta_en = ?, resuelta_por_nombre = ?, actualizado_en = now() WHERE id = ?"""
            else
                """UPDATE alerta SET estado = 'RECONOCIDA', reconocida_en = ?, reconocida_por_nombre = ?,
                          actualizado_en = now() WHERE id = ?"""
            cn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.instante(i++, cuando); ps.setString(i++, quien)
                if (tipo == "ATENCION") { ps.instante(i++, cuando); ps.setString(i++, quien) }
                ps.setLong(i, id)
                ps.executeUpdate()
            }
        }
    }

    private val sqlIntervencion = """
        SELECT i.id::text AS id, i.alerta_id, a.sector_id, COALESCE(u.nombre, i.responsable) AS usuario,
               i.descripcion, i.realizada_en, i.creada_en
          FROM intervencion i
          JOIN alerta a ON a.id = i.alerta_id
          LEFT JOIN usuario u ON u.id = i.usuario_id
    """.trimIndent()

    private fun leerIntervencion(rs: java.sql.ResultSet) = IntervencionDto(
        rs.getString("id"), rs.getLong("alerta_id"), rs.getInt("sector_id"), rs.getString("usuario"),
        rs.getString("descripcion"), iso(rs.instante("realizada_en"))!!, iso(rs.instante("creada_en"))!!
    )

    private fun buscarIntervencion(cn: Connection, id: UUID): IntervencionDto? =
        cn.prepareStatement("$sqlIntervencion WHERE i.id = ?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) leerIntervencion(rs) else null }
        }

    /**
     * Devuelve la intervención y si es nueva (201) o un reenvío (200).
     * El id lo genera la app: reenviar la misma intervención no la duplica.
     * Una intervención sobre una CRITICO la deja RESUELTA; sobre otros tipos no cambia el estado.
     */
    fun registrarIntervencion(b: NuevaIntervencionDto): Pair<IntervencionDto, Boolean> {
        val id = try { UUID.fromString(b.id) } catch (e: Exception) { throw invalido("id debe ser un UUID generado por la app") }
        val descripcion = b.descripcion.trim()
        if (descripcion.length !in 5..2000) throw invalido("descripcion debe tener entre 5 y 2000 caracteres")
        val realizada = b.realizadaEn?.let { minOf(parseInstante(it, "realizadaEn"), Instant.now()) } ?: Instant.now()
        val responsable = nombreLibre(b.responsable)

        return transaccion { cn ->
            buscarIntervencion(cn, id)?.let { return@transaccion it to false }
            val (tipo, estado) = bloquearAlerta(cn, b.alertaId)
            val filas = cn.prepareStatement(
                """INSERT INTO intervencion (id, alerta_id, usuario_id, descripcion, realizada_en, responsable)
                   VALUES (?, ?, NULL, ?, ?, ?) ON CONFLICT (id) DO NOTHING"""
            ).use { ps ->
                ps.setObject(1, id); ps.setLong(2, b.alertaId); ps.setString(3, descripcion)
                ps.instante(4, realizada); ps.setString(5, responsable)
                ps.executeUpdate()
            }
            if (filas == 0) return@transaccion buscarIntervencion(cn, id)!! to false

            if (tipo == "CRITICO" && estado != "RESUELTA") {
                cn.prepareStatement(
                    """UPDATE alerta SET estado = 'RESUELTA', resuelta_en = ?, resuelta_por_nombre = ?,
                              actualizado_en = now() WHERE id = ?"""
                ).use { ps -> ps.instante(1, realizada); ps.setString(2, responsable); ps.setLong(3, b.alertaId); ps.executeUpdate() }
            } else {
                // Sin cambio de estado, pero la app ve que la alerta tiene una intervención nueva
                cn.prepareStatement("UPDATE alerta SET actualizado_en = now() WHERE id = ?").use { ps ->
                    ps.setLong(1, b.alertaId); ps.executeUpdate()
                }
            }
            buscarIntervencion(cn, id)!! to true
        }
    }

    fun listarIntervenciones(sectorP: String?, desdeP: String?, hastaP: String?, actualizadoP: String?): List<IntervencionDto> {
        val sectorId = sectorP?.let { it.toIntOrNull() ?: throw invalido("sectorId debe ser un número") }
        val desde = desdeP?.let { parseInstante(it, "desde") }
        val hasta = hastaP?.let { parseInstante(it, "hasta") }
        val actualizado = actualizadoP?.let { parseInstante(it, "actualizadoDesde") }
        if (desde != null && hasta != null && !desde.isBefore(hasta)) throw invalido("desde debe ser anterior a hasta")

        val condiciones = mutableListOf<String>()
        if (sectorId != null) condiciones += "a.sector_id = ?"
        if (desde != null) condiciones += "i.realizada_en >= ?"
        if (hasta != null) condiciones += "i.realizada_en < ?"
        if (actualizado != null) condiciones += "i.creada_en >= ?"
        val sql = sqlIntervencion +
            (if (condiciones.isEmpty()) "" else " WHERE " + condiciones.joinToString(" AND ")) +
            " ORDER BY i.realizada_en DESC LIMIT 500"
        return db.connection.use { cn ->
            cn.prepareStatement(sql).use { ps ->
                var i = 1
                if (sectorId != null) ps.setInt(i++, sectorId)
                if (desde != null) ps.instante(i++, desde)
                if (hasta != null) ps.instante(i++, hasta)
                if (actualizado != null) ps.instante(i, actualizado)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(leerIntervencion(rs)) } }
            }
        }
    }
}

// ---------- Generación automática ----------

object Alertas {
    private val log = LoggerFactory.getLogger("Alertas")

    /** Una medición con más de 3 minutos de atraso viene de la cola de la SD: suma a la exposición pero no dispara alertas. */
    private const val ATRASO_MAX_S = 180L

    private data class InfoSector(val id: Int, val nombre: String, val turnoInicio: LocalTime, val jornadaMin: Int, val empresaId: Int?)

    // Las alertas nunca deben cortar la ingesta: cualquier error se registra y se sigue
    private inline fun seguro(que: String, f: () -> Unit) {
        try { f() } catch (e: Exception) { log.warn("Alertas ($que): ${e.message}") }
    }

    private fun infoSector(cn: Connection, dev: String): InfoSector? = cn.prepareStatement(
        """SELECT s.id, s.nombre, s.turno_inicio, s.jornada_min, si.empresa_id
             FROM dispositivo d JOIN sector s ON s.id = d.sector_id JOIN sitio si ON si.id = s.sitio_id
            WHERE d.id = ?"""
    ).use { ps ->
        ps.setString(1, dev)
        ps.executeQuery().use { rs ->
            if (rs.next()) InfoSector(
                rs.getInt("id"), rs.getString("nombre"), rs.getObject("turno_inicio", LocalTime::class.java),
                rs.getInt("jornada_min"), rs.entero("empresa_id")
            ) else null
        }
    }

    private fun umbrales(cn: Connection, empresaId: Int?): Pair<Double, Double> {
        if (empresaId == null) return 80.0 to 100.0
        return cn.prepareStatement("SELECT atencion_pct::float8 AS a, critico_pct::float8 AS c FROM configuracion_alertas WHERE empresa_id = ?").use { ps ->
            ps.setInt(1, empresaId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble("a") to rs.getDouble("c") else 80.0 to 100.0 }
        }
    }

    private fun texto(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    /** Se llama con cada medición nueva en vivo: crea ATENCION y CRITICO al cruzar los umbrales (una de cada una por turno). */
    fun evaluarExposicion(db: HikariDataSource, dev: String, tsEpoch: Long) = seguro("exposicion $dev") {
        val ts = Instant.ofEpochSecond(tsEpoch)
        if (Duration.between(ts, Instant.now()).seconds > ATRASO_MAX_S) return@seguro
        db.connection.use { cn ->
            val s = infoSector(cn, dev) ?: return@use
            val fecha = LocalDate.ofInstant(ts, ZONA)
            val inicio = ZonedDateTime.of(fecha, s.turnoInicio, ZONA).toInstant()
            val fin = inicio.plusSeconds(s.jornadaMin * 60L)
            if (ts.isBefore(inicio) || !ts.isBefore(fin)) return@use   // fuera del turno

            val pct = cn.prepareStatement(
                """SELECT COALESCE(sum(periodo_s::float8 / 60.0 / 480.0 * power(2.0::float8, (laeq::float8 - 85.0) / 3.0)), 0) * 100
                     FROM medicion WHERE device_id = ? AND ts >= ? AND ts < ?"""
            ).use { ps ->
                ps.setString(1, dev); ps.instante(2, inicio); ps.instante(3, fin)
                ps.executeQuery().use { rs -> rs.next(); rs.getDouble(1) }
            }
            val (atencion, critico) = umbrales(cn, s.empresaId)
            if (pct >= atencion) crearDeTurno(cn, s, dev, "ATENCION", fecha, pct,
                "${s.nombre}: la exposición del turno llegó al ${texto(atencion)} % del límite diario. " +
                "Revisá el sector y reforzá el uso de protección auditiva.")
            if (pct >= critico) crearDeTurno(cn, s, dev, "CRITICO", fecha, pct,
                "${s.nombre}: la exposición del turno llegó al ${texto(critico)} % del límite diario. Registrá una intervención.")
        }
    }

    private fun crearDeTurno(cn: Connection, s: InfoSector, dev: String, tipo: String, fecha: LocalDate, pct: Double, mensaje: String) {
        val filas = cn.prepareStatement(
            """INSERT INTO alerta (sector_id, device_id, tipo, fecha_turno, valor, mensaje) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (sector_id, tipo, fecha_turno) WHERE tipo IN ('ATENCION', 'CRITICO') DO NOTHING"""
        ).use { ps ->
            ps.setInt(1, s.id); ps.setString(2, dev); ps.setString(3, tipo); ps.setObject(4, fecha)
            ps.setDouble(5, r1(pct)); ps.setString(6, mensaje)
            ps.executeUpdate()
        }
        if (filas == 1) log.info("Alerta $tipo creada (${r1(pct)} %): $mensaje")
    }

    /** OFFLINE o MIC_FAULT: como máximo una abierta por sector. */
    fun crearPorDispositivo(db: HikariDataSource, dev: String, tipo: String) = seguro("$tipo $dev") {
        db.connection.use { cn ->
            val s = infoSector(cn, dev) ?: return@use
            val mensaje = if (tipo == "OFFLINE")
                "${s.nombre}: el sensor $dev dejó de comunicarse. Los datos del sector no están actualizados."
            else
                "${s.nombre}: el micrófono del sensor $dev informa una falla. Revisá el equipo."
            val filas = cn.prepareStatement(
                """INSERT INTO alerta (sector_id, device_id, tipo, mensaje) VALUES (?, ?, ?, ?)
                   ON CONFLICT (sector_id, tipo) WHERE tipo IN ('OFFLINE', 'MIC_FAULT') AND estado <> 'RESUELTA' DO NOTHING"""
            ).use { ps ->
                ps.setInt(1, s.id); ps.setString(2, dev); ps.setString(3, tipo); ps.setString(4, mensaje)
                ps.executeUpdate()
            }
            if (filas == 1) log.info("Alerta $tipo creada: $mensaje")
        }
    }

    /** OFFLINE se resuelve sola al volver la conexión; MIC_FAULT, con el evento MIC_RECOVERED. */
    fun resolverPorDispositivo(db: HikariDataSource, dev: String, tipo: String) = seguro("resolver $tipo $dev") {
        val filas = db.connection.use { cn ->
            cn.prepareStatement(
                """UPDATE alerta SET estado = 'RESUELTA', resuelta_en = now(), actualizado_en = now()
                    WHERE device_id = ? AND tipo = ? AND estado <> 'RESUELTA'"""
            ).use { ps -> ps.setString(1, dev); ps.setString(2, tipo); ps.executeUpdate() }
        }
        if (filas > 0) log.info("Alerta $tipo de $dev resuelta automáticamente")
    }

    /**
     * Cada minuto revisa los sensores que ya se comunicaron alguna vez: si están desconectados,
     * o no mandaron estado ni mediciones en los últimos 10 minutos, se marcan desconectados y se crea OFFLINE.
     */
    fun iniciarVigilancia(db: HikariDataSource) {
        thread(isDaemon = true, name = "vigilancia-sensores") {
            while (true) {
                Thread.sleep(60_000)
                seguro("vigilancia") { revisarSensores(db) }
            }
        }
    }

    private fun revisarSensores(db: HikariDataSource) {
        val caidos = db.connection.use { cn ->
            cn.prepareStatement(
                """SELECT d.id
                     FROM dispositivo d JOIN dispositivo_estado de ON de.device_id = d.id
                    WHERE d.activo AND d.sector_id IS NOT NULL AND de.ultimo_estado IS NOT NULL
                      AND (de.conectado = false
                           OR (de.ultimo_estado < now() - interval '10 minutes'
                               AND NOT EXISTS (SELECT 1 FROM medicion m
                                                WHERE m.device_id = d.id
                                                  AND m.ts > now() - interval '1 day'
                                                  AND m.recibido_en > now() - interval '10 minutes')))"""
            ).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        }
        for (dev in caidos) {
            db.connection.use { cn ->
                cn.prepareStatement("UPDATE dispositivo_estado SET conectado = false WHERE device_id = ? AND conectado").use { ps ->
                    ps.setString(1, dev); ps.executeUpdate()
                }
            }
            crearPorDispositivo(db, dev, "OFFLINE")
        }
    }
}
