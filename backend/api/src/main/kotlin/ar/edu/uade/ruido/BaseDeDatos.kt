package ar.edu.uade.ruido

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object BaseDeDatos {

    private fun env(nombre: String, porDefecto: String) = System.getenv(nombre) ?: porDefecto

    /** Crea el pool de conexiones y aplica las migraciones pendientes. */
    fun conectar(): HikariDataSource {
        val config = HikariConfig().apply {
            // Mismos valores que backend/.env (se pueden pisar con variables de entorno)
            jdbcUrl = env("DB_URL", "jdbc:postgresql://127.0.0.1:15432/ruido")
            username = env("DB_USER", "ruido")
            password = env("DB_PASSWORD", "cambiar_esta_clave")
            maximumPoolSize = 5
            poolName = "ruido-pool"
        }
        val ds = HikariDataSource(config)

        val resultado = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:migration")
            .load()
            .migrate()
        println("Flyway: esquema en version ${resultado.targetSchemaVersion ?: "sin cambios"} " +
                "(${resultado.migrationsExecuted} migraciones aplicadas ahora)")
        return ds
    }

    /** Consulta mínima para saber si la base responde. */
    fun ping(ds: HikariDataSource): Pair<Boolean, String> = try {
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM dispositivo").use { rs ->
                    rs.next()
                    true to "conectada (${rs.getInt(1)} dispositivos)"
                }
            }
        }
    } catch (e: Exception) {
        false to (e.message ?: "error de conexion")
    }
}
