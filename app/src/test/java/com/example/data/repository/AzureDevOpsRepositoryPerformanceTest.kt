package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.AzureDevOpsClient
import com.example.data.api.AzureDevOpsService
import com.example.data.model.RawWorkItem
import com.example.data.model.RawWorkItemRelation
import com.example.data.model.WiqlQueryResult
import com.example.data.model.WiqlWorkItemReference
import com.example.data.model.WorkItemsBatchResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AzureDevOpsRepositoryPerformanceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testFetchWorkItemsPerformance() = runBlocking {
        val totalItems = 400

        val fakeService = Proxy.newProxyInstance(
            AzureDevOpsService::class.java.classLoader,
            arrayOf(AzureDevOpsService::class.java)
        ) { _, method, args ->
            when (method.name) {
                "queryWorkItems" -> {
                    val wiqlRefs = (1..totalItems).map { WiqlWorkItemReference(id = it) }
                    Response.success(WiqlQueryResult(workItems = wiqlRefs))
                }
                "getWorkItems" -> {
                    Thread.sleep(10)
                    val idsString = args[1] as String
                    val ids = idsString.split(",").map { it.toInt() }
                    val rawItems = ids.map { id ->
                        RawWorkItem(
                            id = id,
                            fields = mapOf(
                                "System.Title" to "Task $id",
                                "System.WorkItemType" to "Task",
                                "System.State" to "Active"
                            ),
                            relations = if (id % 5 == 0) {
                                listOf(RawWorkItemRelation(rel = "System.LinkTypes.Hierarchy-Forward", url = "https://dev.azure.com/org/_apis/wit/workItems/${id + 1000}"))
                            } else {
                                emptyList()
                            }
                        )
                    }
                    Response.success(WorkItemsBatchResponse(count = rawItems.size, value = rawItems))
                }
                else -> null
            }
        } as AzureDevOpsService

        val pat = "test-pat"
        val cleanPat = pat.trim()
        val serviceCacheField = AzureDevOpsClient::class.java.getDeclaredField("serviceCache")
        serviceCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = serviceCacheField.get(AzureDevOpsClient) as java.util.concurrent.ConcurrentHashMap<String, AzureDevOpsService>
        cache[cleanPat] = fakeService

        val repository = AzureDevOpsRepository(context)

        val timeMillis = measureTimeMillis {
            val result = repository.fetchWorkItems("test-org", pat)
            println("Result: $result")
            result.exceptionOrNull()?.printStackTrace()
            assertTrue("fetchWorkItems failed", result.isSuccess)
            val items = result.getOrThrow()
            assertTrue("Expected at least some items", items.isNotEmpty())
        }

        println("Optimized fetchWorkItems execution time: $timeMillis ms")
    }
}
