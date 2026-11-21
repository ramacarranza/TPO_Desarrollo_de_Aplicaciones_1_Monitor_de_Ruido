package com.example.soundfriendlyworkplace.screens.SeguridadEHigiene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.soundfriendlyworkplace.data.model.MedicionDto
import com.example.soundfriendlyworkplace.ui.theme.TextoSecundario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GraficoRuido(mediciones: List<MedicionDto>) {
    if (mediciones.isEmpty()) return

    val datos = mediciones.sortedBy { it.ts }
    val minimo = datos.minOf { it.laeq }
    val maximo = datos.maxOf { it.laeq }

    val nivelMinimo = minimo - 5.0
    val nivelMaximo = maximo + 5.0
    val rango = nivelMaximo - nivelMinimo

    val tiempos = datos.map { Instant.parse(it.ts).epochSecond }
    val inicio = tiempos.first()
    val duracion = (tiempos.last() - inicio).coerceAtLeast(1L)

    val formatoHora = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

    Column {
        Text(
            text = "Nivel sonoro (dBA)",
            fontSize = 12.sp,
            color = TextoSecundario
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Column(
                modifier = Modifier.width(48.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.getDefault(), "%.0f", nivelMaximo),
                    fontSize = 11.sp,
                    color = TextoSecundario
                )

                Spacer(modifier = Modifier.height(130.dp))

                Text(
                    text = String.format(Locale.getDefault(), "%.0f", nivelMinimo),
                    fontSize = 11.sp,
                    color = TextoSecundario
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
            ) {
                val ancho = size.width
                val alto = size.height

                // Líneas de referencia horizontales.
                for (i in 0..4) {
                    val y = alto * i / 4f
                    drawLine(
                        color = Color(0xFFE6E0EB),
                        start = Offset(0f, y),
                        end = Offset(ancho, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val puntos = datos.mapIndexed { indice, medicion ->
                    val x = (
                            (tiempos[indice] - inicio).toFloat() /
                                    duracion.toFloat()
                            ) * ancho

                    val y = alto - (
                            ((medicion.laeq - nivelMinimo) / rango).toFloat() *
                                    alto
                            )

                    Offset(x, y)
                }

                puntos.forEachIndexed { indice, punto ->
                    if (indice > 0) {
                        val diferenciaMinutos =
                            (tiempos[indice] - tiempos[indice - 1]) / 60

                        // No unir mediciones si falta un intervalo completo.
                        if (diferenciaMinutos <= 15) {
                            drawLine(
                                color = Color(0xFF8064A2),
                                start = puntos[indice - 1],
                                end = punto,
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    drawCircle(
                        color = Color(0xFF8064A2),
                        radius = 3.dp.toPx(),
                        center = punto
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatoHora.format(Instant.ofEpochSecond(inicio)),
                fontSize = 11.sp,
                color = TextoSecundario
            )

            Text(
                text = formatoHora.format(Instant.ofEpochSecond(tiempos.last())),
                fontSize = 11.sp,
                color = TextoSecundario
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Hora de medición",
            fontSize = 11.sp,
            color = TextoSecundario
        )
    }
}


