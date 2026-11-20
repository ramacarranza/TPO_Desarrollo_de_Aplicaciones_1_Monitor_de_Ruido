package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.round

/**
 * Tarea 16: API de lectura del Contrato de datos v2 (base /api/v1).
 * Sin login ni roles (corrección del docente): todos los endpoints son públicos dentro de la red local.
 *
 * Exposición del turno (Res. SRT 295/03): D% = 100 · Σ (min_i / 480) · 2^((LAeq_i − 85) / 3)
 * Los turnos están en hora argentina y no cruzan la medianoche.
 */

val ZONA: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")

// ---------- DTOs (se pueden copiar tal cual en la app) ----------

@Serializable data class ErrorDto(val error: String, val mensaje: String)

@Serializable data class SensorResumenDto(val id: String, val estado: String)

@Serializable data class SectorDto(
    val id: Int, val nombre: String, val planta: String, val estado: String,
    val nivelDba: Double?, val exposicionPct: Double, val ultimaActualizacion: String?,
    val sensor: SensorResumenDto?, val alertasPendientes: Int
)

@Serializable data class TurnoDto(val inicio: String, val fin: String, val duracionMin: Int)

@Serializable data class SensorDetalleDto(
    val id: String, val estado: String, val ultimaComunicacion: String?, val micOk: Boolean?, val sdOk: Boolean?
)

@Serializable data class SectorDetalleDto(
    val id: Int, val nombre: String, val planta: String, val estado: String,
    val nivelDba: Double?, val exposicionPct: Double, val ultimaActualizacion: String?,
    val turno: TurnoDto, val sensor: SensorDetalleDto?, val alertasPendientes: Int
)

@Serializable data class MedicionDto(val ts: String, val laeq: Double, val lmax: Double?, val lmin: Double?, val minutos: Int)

@Serializable data class PuntoCurvaDto(val ts: String, val exposicionPct: Double)

@Serializable data class ExposicionDto(
    val sectorId: Int, val fecha: String, val turnoInicio: String, val turnoFin: String,
    val exposicionPct: Double, val laeqTurno: Double?, val maxLaeq: Double?,
    val minutosMedidos: Int, val coberturaPct: Double, val estado: String, val curva: List<PuntoCurvaDto>
)

@Serializable data class HistorialDto(
    val fecha: String, val exposicionPct: Double, val laeqTurno: Double?, val maxLaeq: Double?,
    val coberturaPct: Double, val alertas: Int, val intervenciones: Int
)

@Serializable data class IntervencionDto(
    val id: String, val alertaId: Long, val sectorId: Int, val usuario: String?, val descripcion: String,
    val realizadaEn: String, val creadaEn: String
)

@Serializable data class AlertaDto(
    val id: Long, val sectorId: Int, val sector: String, val deviceId: String?, val tipo: String, val valor: Double?,
    val mensaje: String, val estado: String, val creadaEn: String, val reconocidaPor: String?, val reconocidaEn: String?,
    val resueltaPor: String?, val resueltaEn: String?, val actualizadoEn: String
)

@Serializable data class AlertaDetalleDto(
    val id: Long, val sectorId: Int, val sector: String, val deviceId: String?, val tipo: String, val valor: Double?,
    val mensaje: String, val estado: String, val creadaEn: String, val reconocidaPor: String?, val reconocidaEn: String?,
    val resueltaPor: String?, val resueltaEn: String?, val actualizadoEn: String, val intervenciones: List<IntervencionDto>
)

// ---------- Errores con el formato del contrato ----------

class ApiError(val status: HttpStatusCode, val codigo: String, override val message: String) : Exception(message)

fun invalido(msg: String) = ApiError(HttpStatusCode.BadRequest, "parametros_invalidos", msg)
fun noEncontrado(msg: String) = ApiError(HttpStatusCode.NotFound, "no_encontrado", msg)

val logApi = LoggerFactory.getLogger("Api")

suspend inline fun ApplicationCall.conErrores(bloque: () -> Unit) {
    try {
        bloque()
    } catch (e: ApiError) {
        respond(e.status, ErrorDto(e.codigo, e.message))
    } catch (e: Exception) {
        logApi.error("Error en ${request.local.uri}", e)
        respond(HttpStatusCode.InternalServerError, ErrorDto("error_interno", "Error inesperado en el servidor"))
    }
}

fun ApplicationCall.idEntero(): Int =
    parameters["id"]?.toIntOrNull() ?: throw invalido("El id debe ser un número")

// ---------- Rutas ----------

fun Route.rutasLectura(db: HikariDataSource) {
    val l = Lectura(db)
    route("/api/v1") {
        get("/sectores") { call.conErrores { call.respond(l.listarSectores()) } }
        get("/sectores/{id}") { call.conErrores { call.respond(l.sector(call.idEntero())) } }
        get("/sectores/{id}/mediciones") {
            call.conErrores {
                val q = call.request.queryParameters
                call.respond(l.mediciones(call.idEntero(), q["desde"], q["hasta"], q["agrupar"]))
            }
        }
        get("/sectores/{id}/exposicion") {
            call.conErrores { call.respond(l.exposicion(call.idEntero(), call.request.queryParameters["fecha"])) }
        }
        get("/sectores/{id}/historial") {
            call.conErrores {
                val q = call.request.queryParameters
                call.respond(l.historial(call.idEntero(), q["desde"], q["hasta"]))
            }
        }
        get("/alertas") {
            call.conErrores {
                val q = call.request.queryParameters
                call.respond(l.alertas(q["estado"], q["sectorId"], q["actualizadoDesde"]))
            }
        }
        get("/alertas/{id}") {
            call.conErrores {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw invalido("El id debe ser un número")
                call.respond(l.alerta(id))
            }
        }
    }
}

// ---------- Utilidades ----------

internal fun iso(i: Instant?): String? = i?.truncatedTo(ChronoUnit.SECONDS)?.toString()
internal fun r1(x: Double): Double = round(x * 10) / 10.0
internal fun ResultSet.dbl(c: String): Double? { val v = getDouble(c); return if (wasNull()) null else v }
internal fun ResultSet.entero(c: String): Int? { val v = getInt(c); return if (wasNull()) null else v }
internal fun ResultSet.booleano(c: String): Boolean? { val v = getBoolean(c); return if (wasNull()) null else v }
internal fun ResultSet.instante(c: String): Instant? = getObject(c, OffsetDateTime::class.java)?.toInstant()
internal fun PreparedStatement.instante(i: Int, v: Instant) = setObject(i, OffsetDateTime.ofInstant(v, ZoneOffset.UTC))

internal fun parseInstante(valor: String, nombre: String): Instant =
    try { Instant.parse(valor) } catch (e: Exception) { throw invalido("$nombre debe ser fecha y hora ISO 8601 en UTC, por ejemplo 2026-11-05T11:00:00Z") }

internal fun parseFecha(valor: String, nombre: String): LocalDate =
    try { LocalDate.parse(valor) } catch (e: Exception) { throw invalido("$nombre debe ser una fecha AAAA-MM-DD") }

// ---------- Lógica de lectura ----------

class Lectura(private val db: HikariDataSource) {

    private data class Sector(
        val id: Int, val nombre: String, val planta: String, val empresaId: Int?,
        val turnoInicio: LocalTime, val jornadaMin: Int,
        val deviceId: String?, val conectado: Boolean?, val ultimoEstado: Instant?,
        val micOk: Boolean?, val sdOk: Boolean?
    )

    private data class Turno(val fecha: LocalDate, val inicio: Instant, val fin: Instant)
    private data class Stats(val pct: Double, val laeq8h: Double?, val maxL: Double?, val minutos: Int)

    private fun <T> con(f: (Connection) -> T): T = db.connection.use(f)

    // Un sensor por sector (MVP): se toma el primer dispositivo activo del sector.
    private val sqlSector = """
        SELECT s.id, s.nombre, si.nombre AS planta, si.empresa_id, s.turno_inicio, s.jornada_min,
               d.id AS device_id, de.conectado, de.ultimo_estado, de.mic_ok, de.sd_ok
          FROM sector s
          JOIN sitio si ON si.id = s.sitio_id
          LEFT JOIN LATERAL (SELECT id FROM dispositivo WHERE sector_id = s.id AND activo ORDER BY id LIMIT 1) d ON true
          LEFT JOIN dispositivo_estado de ON de.device_id = d.id
    """.trimIndent()

    private fun leerSectores(c: Connection, id: Int?): List<Sector> {
        val sql = sqlSector + (if (id != null) " WHERE s.id = ?" else "") + " ORDER BY s.id"
        return c.prepareStatement(sql).use { ps ->
            if (id != null) ps.setInt(1, id)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Sector(
                            id = rs.getInt("id"), nombre = rs.getString("nombre"), planta = rs.getString("planta"),
                            empresaId = rs.entero("empresa_id"),
                            turnoInicio = rs.getObject("turno_inicio", LocalTime::class.java),
                            jornadaMin = rs.getInt("jornada_min"),
                            deviceId = rs.getString("device_id"), conectado = rs.booleano("conectado"),
                            ultimoEstado = rs.instante("ultimo_estado"),
                            micOk = rs.booleano("mic_ok"), sdOk = rs.booleano("sd_ok")
                        )
                    )
                }
            }
        }
    }

    private fun unSector(c: Connection, id: Int): Sector =
        leerSectores(c, id).firstOrNull() ?: throw noEncontrado("No existe el sector $id")

    private fun turno(s: Sector, fecha: LocalDate): Turno {
        val inicio = ZonedDateTime.of(fecha, s.turnoInicio, ZONA).toInstant()
        return Turno(fecha, inicio, inicio.plusSeconds(s.jornadaMin * 60L))
    }

    private fun hoy(): LocalDate = LocalDate.now(ZONA)

    /** Exposición, LAeq,8h, máximo y minutos medidos de un sensor en [desde, hasta). */
    private fun stats(c: Connection, deviceId: String?, desde: Instant, hasta: Instant): Stats {
        if (deviceId == null) return Stats(0.0, null, null, 0)
        val sql = """
            SELECT COALESCE(sum(periodo_s), 0) AS seg,
                   COALESCE(sum(periodo_s::float8 / 60.0 / 480.0 * power(2.0::float8, (laeq::float8 - 85.0) / 3.0)), 0) * 100 AS pct,
                   max(laeq)::float8 AS maxl,
                   CASE WHEN count(*) > 0
                        THEN 10 * log(sum(periodo_s::float8 / 60.0 * power(10.0::float8, laeq::float8 / 10.0)) / 480.0)
                   END AS l8h
              FROM medicion
             WHERE device_id = ? AND ts >= ? AND ts < ?
        """.trimIndent()
        return c.prepareStatement(sql).use { ps ->
            ps.setString(1, deviceId); ps.instante(2, desde); ps.instante(3, hasta)
            ps.executeQuery().use { rs ->
                rs.next()
                Stats(rs.getDouble("pct"), rs.dbl("l8h"), rs.dbl("maxl"), (rs.getLong("seg") / 60).toInt())
            }
        }
    }

    private fun ultimaMedicion(c: Connection, deviceId: String?): Pair<Instant, Double>? {
        if (deviceId == null) return null
        return c.prepareStatement("SELECT ts, laeq::float8 AS laeq FROM medicion WHERE device_id = ? ORDER BY ts DESC LIMIT 1").use { ps ->
            ps.setString(1, deviceId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.instante("ts")!! to rs.getDouble("laeq") else null }
        }
    }

    private fun umbralesEmpresa(c: Connection, empresaId: Int?): Pair<Double, Double> {
        if (empresaId == null) return 80.0 to 100.0
        return c.prepareStatement("SELECT atencion_pct::float8 AS a, critico_pct::float8 AS c FROM configuracion_alertas WHERE empresa_id = ?").use { ps ->
            ps.setInt(1, empresaId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getDouble("a") to rs.getDouble("c") else 80.0 to 100.0 }
        }
    }

    // "Pendientes" = todavía no resueltas (una CRITICO reconocida sigue esperando la intervención)
    private fun alertasPendientes(c: Connection, sectorId: Int): Int =
        c.prepareStatement("SELECT count(*) FROM alerta WHERE sector_id = ? AND estado <> 'RESUELTA'").use { ps ->
            ps.setInt(1, sectorId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun estadoSensor(s: Sector): String? = when {
        s.deviceId == null -> null
        s.conectado != true -> "DESCONECTADO"
        s.micOk == false || s.sdOk == false -> "FALLA"
        else -> "CONECTADO"
    }

    private fun estadoPorPct(pct: Double, umbrales: Pair<Double, Double>): String = when {
        pct >= umbrales.second -> "CRITICO"
        pct >= umbrales.first -> "ATENCION"
        else -> "NORMAL"
    }

    /** SIN_INFO se evalúa primero: nunca se muestra un dato viejo como actual. */
    private fun estadoSector(s: Sector, ultima: Instant?, pct: Double, umbrales: Pair<Double, Double>): String {
        val sensor = estadoSensor(s)
        val sinDatosRecientes = ultima == null || Duration.between(ultima, Instant.now()) > Duration.ofMinutes(5)
        return if (sinDatosRecientes || sensor == null || sensor == "DESCONECTADO") "SIN_INFO"
        else estadoPorPct(pct, umbrales)
    }

    // ---- /sectores y /sectores/{id} ----

    private data class Resumen(val s: Sector, val estado: String, val nivel: Double?, val pct: Double,
                               val ultima: Instant?, val pendientes: Int)

    private fun resumir(c: Connection, s: Sector): Resumen {
        val t = turno(s, hoy())
        val st = stats(c, s.deviceId, t.inicio, t.fin)
        val ultima = ultimaMedicion(c, s.deviceId)
        val estado = estadoSector(s, ultima?.first, st.pct, umbralesEmpresa(c, s.empresaId))
        return Resumen(s, estado, ultima?.second?.let(::r1), r1(st.pct), ultima?.first, alertasPendientes(c, s.id))
    }

    fun listarSectores(): List<SectorDto> = con { c ->
        leerSectores(c, null).map { s ->
            val r = resumir(c, s)
            SectorDto(
                s.id, s.nombre, s.planta, r.estado, r.nivel, r.pct, iso(r.ultima),
                estadoSensor(s)?.let { SensorResumenDto(s.deviceId!!, it) }, r.pendientes
            )
        }
    }

    fun sector(id: Int): SectorDetalleDto = con { c ->
        val s = unSector(c, id)
        val r = resumir(c, s)
        val fin = s.turnoInicio.plusMinutes(s.jornadaMin.toLong())
        SectorDetalleDto(
            s.id, s.nombre, s.planta, r.estado, r.nivel, r.pct, iso(r.ultima),
            TurnoDto(s.turnoInicio.toString(), fin.toString(), s.jornadaMin),
            estadoSensor(s)?.let { SensorDetalleDto(s.deviceId!!, it, iso(s.ultimoEstado), s.micOk, s.sdOk) },
            r.pendientes
        )
    }

    // ---- /sectores/{id}/mediciones ----

    fun mediciones(id: Int, desdeP: String?, hastaP: String?, agruparP: String?): List<MedicionDto> = con { c ->
        val intervalo = when (agruparP ?: "1m") {
            "1m" -> "1 minute"
            "15m" -> "15 minutes"
            "1h" -> "1 hour"
            else -> throw invalido("agrupar debe ser 1m, 15m o 1h")
        }
        val s = unSector(c, id)
        val hasta = hastaP?.let { parseInstante(it, "hasta") } ?: Instant.now()
        val desde = desdeP?.let { parseInstante(it, "desde") } ?: turno(s, hoy()).inicio
        if (!desde.isBefore(hasta)) {
            if (desdeP != null || hastaP != null) throw invalido("desde debe ser anterior a hasta")
            return@con emptyList()   // todavía no empezó el turno de hoy
        }
        if (Duration.between(desde, hasta) > Duration.ofDays(31)) throw invalido("El rango máximo es de 31 días")
        if (s.deviceId == null) return@con emptyList()

        // LAeq agrupado = promedio energético, ponderado por la duración de cada medición
        val sql = """
            SELECT date_bin(?::interval, ts, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS b,
                   10 * log(sum(periodo_s::float8 * power(10.0::float8, laeq::float8 / 10.0)) / sum(periodo_s::float8)) AS laeq,
                   max(lmax)::float8 AS lmax, min(lmin)::float8 AS lmin,
                   round(sum(periodo_s) / 60.0)::int AS minutos
              FROM medicion
             WHERE device_id = ? AND ts >= ? AND ts < ?
             GROUP BY b ORDER BY b
        """.trimIndent()
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, intervalo); ps.setString(2, s.deviceId); ps.instante(3, desde); ps.instante(4, hasta)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        MedicionDto(
                            iso(rs.instante("b"))!!, r1(rs.getDouble("laeq")),
                            rs.dbl("lmax")?.let(::r1), rs.dbl("lmin")?.let(::r1), rs.getInt("minutos")
                        )
                    )
                }
            }
        }
    }

    // ---- /sectores/{id}/exposicion ----

    fun exposicion(id: Int, fechaP: String?): ExposicionDto = con { c ->
        val s = unSector(c, id)
        val fecha = fechaP?.let { parseFecha(it, "fecha") } ?: hoy()
        val t = turno(s, fecha)
        val st = stats(c, s.deviceId, t.inicio, t.fin)
        val umbrales = umbralesEmpresa(c, s.empresaId)
        val estado = if (fecha == hoy()) estadoSector(s, ultimaMedicion(c, s.deviceId)?.first, st.pct, umbrales)
                     else estadoPorPct(st.pct, umbrales)

        // Curva: exposición acumulada cada 15 minutos desde el inicio del turno
        val porBloque = HashMap<Instant, Double>()
        if (s.deviceId != null) {
            val sql = """
                SELECT date_bin('15 minutes'::interval, ts, ?) AS b,
                       sum(periodo_s::float8 / 60.0 / 480.0 * power(2.0::float8, (laeq::float8 - 85.0) / 3.0)) * 100 AS pct
                  FROM medicion
                 WHERE device_id = ? AND ts >= ? AND ts < ?
                 GROUP BY b
            """.trimIndent()
            c.prepareStatement(sql).use { ps ->
                ps.instante(1, t.inicio); ps.setString(2, s.deviceId); ps.instante(3, t.inicio); ps.instante(4, t.fin)
                ps.executeQuery().use { rs -> while (rs.next()) porBloque[rs.instante("b")!!] = rs.getDouble("pct") }
            }
        }
        val limite = minOf(t.fin, Instant.now())
        val curva = mutableListOf<PuntoCurvaDto>()
        var acumulado = 0.0
        var bloque = t.inicio
        while (bloque.isBefore(limite)) {
            acumulado += porBloque[bloque] ?: 0.0
            bloque = bloque.plusSeconds(15 * 60)
            curva.add(PuntoCurvaDto(iso(bloque)!!, r1(acumulado)))
        }

        ExposicionDto(
            s.id, fecha.toString(), iso(t.inicio)!!, iso(t.fin)!!, r1(st.pct),
            st.laeq8h?.let(::r1), st.maxL?.let(::r1), st.minutos,
            r1(st.minutos * 100.0 / s.jornadaMin), estado, curva
        )
    }

    // ---- /sectores/{id}/historial ----

    fun historial(id: Int, desdeP: String?, hastaP: String?): List<HistorialDto> = con { c ->
        val s = unSector(c, id)
        val hasta = hastaP?.let { parseFecha(it, "hasta") } ?: hoy()
        val desde = desdeP?.let { parseFecha(it, "desde") } ?: hasta.minusDays(6)
        if (desde.isAfter(hasta)) throw invalido("desde debe ser anterior o igual a hasta")
        if (ChronoUnit.DAYS.between(desde, hasta) > 31) throw invalido("El rango máximo es de 31 días")

        generateSequence(hasta) { it.minusDays(1) }.takeWhile { !it.isBefore(desde) }.map { fecha ->
            val t = turno(s, fecha)
            val st = stats(c, s.deviceId, t.inicio, t.fin)
            val diaIni = fecha.atStartOfDay(ZONA).toInstant()
            val diaFin = fecha.plusDays(1).atStartOfDay(ZONA).toInstant()
            val alertas = c.prepareStatement(
                "SELECT count(*) FROM alerta WHERE sector_id = ? AND creada_en >= ? AND creada_en < ?"
            ).use { ps ->
                ps.setInt(1, s.id); ps.instante(2, diaIni); ps.instante(3, diaFin)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            val intervenciones = c.prepareStatement(
                """SELECT count(*) FROM intervencion i JOIN alerta a ON a.id = i.alerta_id
                    WHERE a.sector_id = ? AND i.realizada_en >= ? AND i.realizada_en < ?"""
            ).use { ps ->
                ps.setInt(1, s.id); ps.instante(2, diaIni); ps.instante(3, diaFin)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            HistorialDto(
                fecha.toString(), r1(st.pct), st.laeq8h?.let(::r1), st.maxL?.let(::r1),
                r1(st.minutos * 100.0 / s.jornadaMin), alertas, intervenciones
            )
        }.toList()
    }

    // ---- /alertas y /alertas/{id} ----

    private val sqlAlerta = """
        SELECT a.id, a.sector_id, s.nombre AS sector, a.device_id, a.tipo, a.valor::float8 AS valor, a.mensaje, a.estado,
               a.creada_en, COALESCE(ur.nombre, a.reconocida_por_nombre) AS reconocida_por, a.reconocida_en,
               COALESCE(uz.nombre, a.resuelta_por_nombre) AS resuelta_por, a.resuelta_en,
               a.actualizado_en
          FROM alerta a
          JOIN sector s ON s.id = a.sector_id
          LEFT JOIN usuario ur ON ur.id = a.reconocida_por
          LEFT JOIN usuario uz ON uz.id = a.resuelta_por
    """.trimIndent()

    private fun leerAlerta(rs: ResultSet) = AlertaDto(
        rs.getLong("id"), rs.getInt("sector_id"), rs.getString("sector"), rs.getString("device_id"),
        rs.getString("tipo"), rs.dbl("valor"), rs.getString("mensaje"), rs.getString("estado"),
        iso(rs.instante("creada_en"))!!, rs.getString("reconocida_por"), iso(rs.instante("reconocida_en")),
        rs.getString("resuelta_por"), iso(rs.instante("resuelta_en")), iso(rs.instante("actualizado_en"))!!
    )

    fun alertas(estadoP: String?, sectorP: String?, actualizadoDesdeP: String?): List<AlertaDto> = con { c ->
        if (estadoP != null && estadoP !in setOf("PENDIENTE", "RECONOCIDA", "RESUELTA"))
            throw invalido("estado debe ser PENDIENTE, RECONOCIDA o RESUELTA")
        val sectorId = sectorP?.let { it.toIntOrNull() ?: throw invalido("sectorId debe ser un número") }
        val desde = actualizadoDesdeP?.let { parseInstante(it, "actualizadoDesde") }

        val condiciones = mutableListOf<String>()
        if (estadoP != null) condiciones += "a.estado = ?"
        if (sectorId != null) condiciones += "a.sector_id = ?"
        if (desde != null) condiciones += "a.actualizado_en >= ?"
        val sql = sqlAlerta +
            (if (condiciones.isEmpty()) "" else " WHERE " + condiciones.joinToString(" AND ")) +
            " ORDER BY a.creada_en DESC LIMIT 500"
        c.prepareStatement(sql).use { ps ->
            var i = 1
            if (estadoP != null) ps.setString(i++, estadoP)
            if (sectorId != null) ps.setInt(i++, sectorId)
            if (desde != null) ps.instante(i, desde)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(leerAlerta(rs)) } }
        }
    }

    fun alerta(id: Long): AlertaDetalleDto = con { c ->
        val a = c.prepareStatement("$sqlAlerta WHERE a.id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) leerAlerta(rs) else throw noEncontrado("No existe la alerta $id") }
        }
        val intervenciones = c.prepareStatement(
            """SELECT i.id::text AS id, i.alerta_id, i.descripcion, i.realizada_en, i.creada_en,
                      COALESCE(u.nombre, i.responsable) AS usuario
                 FROM intervencion i LEFT JOIN usuario u ON u.id = i.usuario_id
                WHERE i.alerta_id = ? ORDER BY i.realizada_en"""
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        IntervencionDto(
                            rs.getString("id"), rs.getLong("alerta_id"), a.sectorId, rs.getString("usuario"),
                            rs.getString("descripcion"), iso(rs.instante("realizada_en"))!!, iso(rs.instante("creada_en"))!!
                        )
                    )
                }
            }
        }
        AlertaDetalleDto(
            a.id, a.sectorId, a.sector, a.deviceId, a.tipo, a.valor, a.mensaje, a.estado, a.creadaEn,
            a.reconocidaPor, a.reconocidaEn, a.resueltaPor, a.resueltaEn, a.actualizadoEn, intervenciones
        )
    }
}
