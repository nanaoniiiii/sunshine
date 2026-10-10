package com.alkaidlab.sunshine.api

import com.alkaidlab.sunshine.data.*
import retrofit2.Response
import retrofit2.http.*

interface SunshineApiService {

    @GET("/api/config")
    suspend fun getConfig(): Response<ConfigResponse>

    @POST("/api/config")
    suspend fun saveConfig(@Body config: Map<String, String>): Response<SimpleStatusResponse>

    @GET("/api/apps")
    suspend fun getApps(): Response<AppsResponse>

    @POST("/api/apps")
    suspend fun saveApp(@Body app: AppItem): Response<SimpleStatusResponse>

    @DELETE("/api/apps/{id}")
    suspend fun deleteApp(@Path("id") id: String): Response<SimpleStatusResponse>

    @GET("/api/clients/list")
    suspend fun getClients(): Response<ClientsResponse>

    @POST("/api/clients/rename")
    suspend fun renameClient(@Body req: RenameClientRequest): Response<SimpleStatusResponse>

    @POST("/api/clients/unpair")
    suspend fun unpairClient(@Body req: UnpairClientRequest): Response<SimpleStatusResponse>

    @POST("/api/clients/unpair-all")
    suspend fun unpairAll(): Response<SimpleStatusResponse>

    @POST("/api/pin")
    suspend fun submitPin(@Body req: PinRequest): Response<SimpleStatusResponse>

    @GET("/api/runtime/sessions")
    suspend fun getRuntimeSessions(): Response<RuntimeSessionsResponse>

    @GET("/api/perf/current")
    suspend fun getPerfCurrent(): Response<PerfResponse>

    @GET("/api/logs")
    suspend fun getLogs(): Response<LogsResponse>

    @POST("/api/restart")
    suspend fun restartServer(): Response<SimpleStatusResponse>
}
