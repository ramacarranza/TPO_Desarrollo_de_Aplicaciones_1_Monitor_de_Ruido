
package com.example.soundfriendlyworkplace.data.remote

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// Configuración de la conexión con el backend.
object RetrofitClient {

    // Dirección del backend:
    // - http://localhost:8080/ con un túnel adb (emulador o celular por USB). Antes de abrir la app, en la PC:
    //       adb reverse tcp:8080 tcp:8080
    //   Funciona en cualquier red y no depende de la IP de la PC.
    // - Sin túnel: emulador -> http://10.0.2.2:8080/ ; celular en la misma red WiFi -> http://<IP de la PC>:8080/
    private const val BASE_URL = "http://localhost:8080/"
    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}


