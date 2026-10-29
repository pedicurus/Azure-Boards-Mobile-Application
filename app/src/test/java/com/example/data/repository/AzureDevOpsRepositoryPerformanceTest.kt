package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
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
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response
import kotlin.system.measureTimeMillis

class AzureDevOpsRepositoryPerformanceTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockService: AzureDevOpsService

    @Before
    fun setup() {
        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        whenever(mockContext.getSharedPreferences(any(), any())).thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        whenever(mockEditor.remove(any())).thenReturn(mockEditor)
        whenever(mockEditor.commit()).thenReturn(true)

        mockService = mock()
    }

    @Test
    fun testFetchWorkItemsPerformance() = runBlocking {
        val totalItems = 400

        val wiqlRefs = (1..totalItems).map { WiqlWorkItemReference(id = it) }
        whenever(mockService.queryWorkItems(any(), any(), any())).thenReturn(
            Response.success(WiqlQueryResult(workItems = wiqlRefs))
        )

        whenever(mockService.getWorkItems(any(), any(), anyOrNull(), anyOrNull(), any())).doAnswer { invocation ->
            Thread.sleep(200) // simulate delay
            val idsString = invocation.getArgument<String>(1)
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

        val pat = "test-pat"
        val cleanPat = pat.trim()
        val serviceCacheField = AzureDevOpsClient::class.java.getDeclaredField("serviceCache")
        serviceCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = serviceCacheField.get(AzureDevOpsClient) as java.util.concurrent.ConcurrentHashMap<String, AzureDevOpsService>
        cache[cleanPat] = mockService

        val repository = AzureDevOpsRepository(mockContext)

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
