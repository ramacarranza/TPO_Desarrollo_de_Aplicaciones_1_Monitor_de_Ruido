package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

const val VERSION = "0.4.0"

fun main() {
    val db = BaseDeDatos.conectar()   // conecta y aplica migraciones (Flyway)
    Ingesta.iniciar(db)               // tarea 14: suscripción MQTT -> base de datos
    Alertas.iniciarVigilancia(db)     // tarea 17: OFFLINE si un sensor no da señal por 10 min
    Simulador.iniciar(db)             // demo: sensores simulados nm-002 y nm-003 (SIMULADOR=0 lo desactiva)
    val puerto = (System.getenv("PORT") ?: "8080").toInt()
    embeddedServer(Netty, port = puerto, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
}

fun Application.module(db: HikariDataSource) {
    install(ContentNegotiation) { json() }
    install(CallLogging)

    routing {
        // Tarea 8: responde 200 si la base está conectada, 503 si no
        get("/health") {
            val (ok, detalle) = BaseDeDatos.ping(db)
            call.respond(
                if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                Health(
                    status = if (ok) "ok" else "error", db = detalle,
                    mqtt = if (Ingesta.conectado()) "conectado" else "desconectado", version = VERSION
                )
            )
        }
        // Tarea 16: API de lectura del Contrato v2 (/api/v1/...), sin JWT por ahora
        rutasLectura(db)
        // Tarea 17: reconocer alertas e intervenciones
        rutasAlertas(db)
    }
}

@Serializable
data class Health(val status: String, val db: String, val mqtt: String, val version: String)
