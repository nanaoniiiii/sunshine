package com.alkaidlab.sunshine.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object SunshineApiClient {
    private const val BASE_URL = "http://127.0.0.1:47990"

    internal val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // This is the app's own loopback control plane, never an Internet request.
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    val service: SunshineApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SunshineApiService::class.java)
    }
}
