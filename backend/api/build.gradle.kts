plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "ar.edu.uade.ruido"
version = "0.1.0"

application {
    mainClass.set("ar.edu.uade.ruido.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"
val flywayVersion = "10.20.1"

dependencies {
    // Servidor HTTP
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Base de datos: pool de conexiones + driver + migraciones
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // MQTT (suscripción del backend, tarea 14)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Logs
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
}

// Las migraciones viven en ../db/migration (las mismas que aplica Docker).
// Se agregan al classpath como "migration/V1__...sql" -> Flyway las busca en classpath:migration
sourceSets {
    main {
        resources {
            srcDir("../db")
        }
    }
}
