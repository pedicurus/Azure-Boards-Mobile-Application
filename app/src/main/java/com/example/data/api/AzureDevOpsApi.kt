package com.example.data.api

import android.util.Base64
import com.example.data.model.JsonPatchOperation
import com.example.data.model.ProjectsResponse
import com.example.data.model.RawWorkItem
import com.example.data.model.WiqlQueryRequest
import com.example.data.model.WiqlQueryResult
import com.example.data.model.WorkItemsBatchResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface AzureDevOpsService {

    @POST("{organization}/_apis/wit/wiql")
    suspend fun queryWorkItems(
        @Path("organization") organization: String,
        @Query("api-version") apiVersion: String = "7.0",
        @Body request: WiqlQueryRequest
    ): Response<WiqlQueryResult>

    @GET("{organization}/_apis/wit/workitems")
    suspend fun getWorkItems(
        @Path("organization") organization: String,
        @Query("ids") ids: String,
        @Query("fields") fields: String? = null,
        @Query("\$expand") expand: String? = "relations",
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<WorkItemsBatchResponse>

    @PATCH("{organization}/_apis/wit/workitems/{id}")
    suspend fun updateWorkItem(
        @Path("organization") organization: String,
        @Path("id") id: Int,
        @Query("api-version") apiVersion: String = "7.0",
        @Body patchBody: RequestBody
    ): Response<RawWorkItem>

    @POST("{organization}/{project}/_apis/wit/workitems/\${type}")
    suspend fun createWorkItem(
        @Path("organization") organization: String,
        @Path("project") project: String,
        @Path("type") type: String,
        @Query("api-version") apiVersion: String = "7.0",
        @Body patchBody: RequestBody
    ): Response<RawWorkItem>

    @GET("{organization}/_apis/projects")
    suspend fun getProjects(
        @Path("organization") organization: String,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<ProjectsResponse>

    @GET("{organization}/_apis/projects/{project}")
    suspend fun getProjectDetail(
        @Path("organization") organization: String,
        @Path("project") project: String,
        @Query("includeCapabilities") includeCapabilities: Boolean = true,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.ProjectItem>

    @GET("{organization}/{project}/_apis/wit/workitemtypes")
    suspend fun getProjectWorkItemTypes(
        @Path("organization") organization: String,
        @Path("project") project: String,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.ProjectWorkItemTypesResponse>

    @GET("{organization}/{project}/_apis/wit/workitemtypes/{type}/states")
    suspend fun getProjectWorkItemTypeStates(
        @Path("organization") organization: String,
        @Path("project") project: String,
        @Path("type") type: String,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.WorkItemStatesResponse>

    @GET("{organization}/{project}/_apis/wit/workitemtypecategories")
    suspend fun getWorkItemTypeCategories(
        @Path("organization") organization: String,
        @Path("project") project: String,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.WorkItemTypeCategoriesResponse>

    @GET("{organization}/_apis/work/processes")
    suspend fun getProcesses(
        @Path("organization") organization: String,
        @Query("\$expand") expand: String = "projects",
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.ProcessesResponse>

    @GET("{organization}/_apis/work/processes/{processId}/workitemtypes")
    suspend fun getProcessWorkItemTypes(
        @Path("organization") organization: String,
        @Path("processId") processId: String,
        @Query("\$expand") expand: String = "states",
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.ProcessWorkItemTypesResponse>

    @GET("{organization}/_apis/work/processes/{processId}/workItemTypes/{witRefName}/states")
    suspend fun getProcessWorkItemTypeStates(
        @Path("organization") organization: String,
        @Path("processId") processId: String,
        @Path("witRefName") witRefName: String,
        @Query("api-version") apiVersion: String = "7.0"
    ): Response<com.example.data.model.ProcessStatesResponse>
}

object AzureDevOpsClient {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val moshiConverterFactory = MoshiConverterFactory.create(moshi)

    private val patchListType = Types.newParameterizedType(
        List::class.java,
        JsonPatchOperation::class.java
    )
    private val patchAdapter = moshi.adapter<List<JsonPatchOperation>>(patchListType)

    private val baseOkHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val serviceCache = java.util.concurrent.ConcurrentHashMap<String, AzureDevOpsService>()

    fun createPatchRequestBody(operations: List<JsonPatchOperation>): RequestBody {
        val json = patchAdapter.toJson(operations)
        return json.toRequestBody("application/json-patch+json; charset=utf-8".toMediaType())
    }

    fun createService(pat: String): AzureDevOpsService {
        val cleanPat = pat.trim()
        return serviceCache.getOrPut(cleanPat) {
            val authHeader = "Basic " + Base64.encodeToString(
                ":$cleanPat".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            val authInterceptor = Interceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                val bodyContentType = original.body?.contentType()?.toString()
                if (original.header("Content-Type") == null) {
                    if (bodyContentType != null && bodyContentType.contains("json-patch")) {
                        builder.header("Content-Type", "application/json-patch+json")
                    } else {
                        builder.header("Content-Type", "application/json")
                    }
                }
                chain.proceed(builder.build())
            }

            val okHttpClient = baseOkHttpClient.newBuilder()
                .addInterceptor(authInterceptor)
                .build()

            Retrofit.Builder()
                .baseUrl("https://dev.azure.com/")
                .client(okHttpClient)
                .addConverterFactory(moshiConverterFactory)
                .build()
                .create(AzureDevOpsService::class.java)
        }
    }
}
