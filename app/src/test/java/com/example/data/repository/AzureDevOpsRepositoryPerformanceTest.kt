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

    @Test
    fun testFetchProjectProcessHierarchyExcludesDisabledStates() = runBlocking {
        val processId = "proc-guid-123"
        val projectId = "proj-guid-456"
        val projectName = "TestProject"

        val fakeService = Proxy.newProxyInstance(
            AzureDevOpsService::class.java.classLoader,
            arrayOf(AzureDevOpsService::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getProjectDetail" -> {
                    Response.success(
                        com.example.data.model.ProjectItem(
                            id = projectId,
                            name = projectName,
                            capabilities = com.example.data.model.ProjectCapabilities(
                                processTemplate = com.example.data.model.ProcessTemplateCapability(
                                    templateName = "CustomAgile",
                                    templateTypeId = processId
                                )
                            )
                        )
                    )
                }
                "getProcesses" -> {
                    Response.success(
                        com.example.data.model.ProcessesResponse(
                            count = 1,
                            value = listOf(
                                com.example.data.model.ProcessDefinition(
                                    typeId = processId,
                                    name = "CustomAgile",
                                    isEnabled = true
                                )
                            )
                        )
                    )
                }
                "getProcessWorkItemTypes" -> {
                    Response.success(
                        com.example.data.model.ProcessWorkItemTypesResponse(
                            count = 3,
                            value = listOf(
                                com.example.data.model.ProcessWorkItemType(
                                    referenceName = "Custom.Goal",
                                    name = "هدف",
                                    customization = "custom",
                                    isDisabled = false,
                                    states = listOf(
                                        com.example.data.model.ProcessStateDefinition(name = "New", order = 1, hidden = false),
                                        com.example.data.model.ProcessStateDefinition(name = "Active", order = 2, hidden = false),
                                        com.example.data.model.ProcessStateDefinition(name = "Closed", order = 3, hidden = false)
                                    )
                                ),
                                com.example.data.model.ProcessWorkItemType(
                                    referenceName = "Custom.Project",
                                    name = "پروژه",
                                    customization = "inherited",
                                    isDisabled = false,
                                    // Inline states returns base system state with hidden=false alongside derived hidden=true override
                                    states = listOf(
                                        com.example.data.model.ProcessStateDefinition(id = "s1", name = "New", order = 1, hidden = false, customizationType = "system"),
                                        com.example.data.model.ProcessStateDefinition(id = "s2", name = "Active", order = 2, hidden = false, customizationType = "system"),
                                        com.example.data.model.ProcessStateDefinition(id = "s3", name = "Resolved", order = 3, hidden = false, customizationType = "system"),
                                        com.example.data.model.ProcessStateDefinition(id = "s3", name = "Resolved", order = 3, hidden = true, customizationType = "inherited"),
                                        com.example.data.model.ProcessStateDefinition(id = "s4", name = "Closed", order = 4, hidden = false, customizationType = "system")
                                    )
                                ),
                                com.example.data.model.ProcessWorkItemType(
                                    referenceName = "Custom.Work",
                                    name = "کار",
                                    customization = "inherited",
                                    isDisabled = false,
                                    // Inline list endpoint didn't populate hidden=true, but dedicated getProcessWorkItemTypeStates & transitions do
                                    states = listOf(
                                        com.example.data.model.ProcessStateDefinition(id = "w1", name = "To Do", order = 1, hidden = false),
                                        com.example.data.model.ProcessStateDefinition(id = "w2", name = "In Progress", order = 2, hidden = false),
                                        com.example.data.model.ProcessStateDefinition(id = "w3", name = "Removed", order = 3, hidden = false),
                                        com.example.data.model.ProcessStateDefinition(id = "w4", name = "Done", order = 4, hidden = false)
                                    )
                                )
                            )
                        )
                    )
                }
                "getProcessWorkItemTypeStates" -> {
                    val witRef = args[2] as String
                    val states = when (witRef) {
                        "Custom.Project" -> listOf(
                            com.example.data.model.ProcessStateDefinition(id = "s1", name = "New", order = 1, hidden = false),
                            com.example.data.model.ProcessStateDefinition(id = "s2", name = "Active", order = 2, hidden = false),
                            com.example.data.model.ProcessStateDefinition(id = "s3", name = "Resolved", order = 3, hidden = true),
                            com.example.data.model.ProcessStateDefinition(id = "s4", name = "Closed", order = 4, hidden = false)
                        )
                        "Custom.Work" -> listOf(
                            com.example.data.model.ProcessStateDefinition(id = "w1", name = "To Do", order = 1, hidden = false),
                            com.example.data.model.ProcessStateDefinition(id = "w2", name = "In Progress", order = 2, hidden = false),
                            com.example.data.model.ProcessStateDefinition(id = "w3", name = "Removed", order = 3, hidden = true),
                            com.example.data.model.ProcessStateDefinition(id = "w4", name = "Done", order = 4, hidden = false)
                        )
                        else -> listOf(
                            com.example.data.model.ProcessStateDefinition(name = "New", order = 1, hidden = false),
                            com.example.data.model.ProcessStateDefinition(name = "Active", order = 2, hidden = false),
                            com.example.data.model.ProcessStateDefinition(name = "Closed", order = 3, hidden = false)
                        )
                    }
                    Response.success(com.example.data.model.ProcessStatesResponse(count = states.size, value = states))
                }
                "getProjectWorkItemTypes" -> {
                    Response.success(
                        com.example.data.model.ProjectWorkItemTypesResponse(
                            count = 3,
                            value = listOf(
                                com.example.data.model.ProjectWorkItemTypeDefinition(
                                    name = "هدف",
                                    referenceName = "Custom.Goal",
                                    isDisabled = false
                                ),
                                com.example.data.model.ProjectWorkItemTypeDefinition(
                                    name = "پروژه",
                                    referenceName = "Custom.Project",
                                    isDisabled = false,
                                    states = listOf(
                                        com.example.data.model.WorkItemStateDefinition(name = "New"),
                                        com.example.data.model.WorkItemStateDefinition(name = "Active"),
                                        com.example.data.model.WorkItemStateDefinition(name = "Resolved"),
                                        com.example.data.model.WorkItemStateDefinition(name = "Closed")
                                    ),
                                    transitions = mapOf(
                                        "" to listOf(com.example.data.model.WorkItemStateTransition(to = "New")),
                                        "New" to listOf(
                                            com.example.data.model.WorkItemStateTransition(to = "Active"),
                                            com.example.data.model.WorkItemStateTransition(to = "Closed")
                                        ),
                                        "Active" to listOf(
                                            com.example.data.model.WorkItemStateTransition(to = "New"),
                                            com.example.data.model.WorkItemStateTransition(to = "Closed")
                                        )
                                    )
                                ),
                                com.example.data.model.ProjectWorkItemTypeDefinition(
                                    name = "کار",
                                    referenceName = "Custom.Work",
                                    isDisabled = false,
                                    states = listOf(
                                        com.example.data.model.WorkItemStateDefinition(name = "To Do"),
                                        com.example.data.model.WorkItemStateDefinition(name = "In Progress"),
                                        com.example.data.model.WorkItemStateDefinition(name = "Removed"),
                                        com.example.data.model.WorkItemStateDefinition(name = "Done")
                                    )
                                )
                            )
                        )
                    )
                }
                "getProjectWorkItemTypeStates" -> {
                    val typeName = args[2] as String
                    // Simulate WIT endpoint returning all states without hidden=true
                    val states = when (typeName) {
                        "پروژه" -> listOf(
                            com.example.data.model.WorkItemStateDefinition(name = "New"),
                            com.example.data.model.WorkItemStateDefinition(name = "Active"),
                            com.example.data.model.WorkItemStateDefinition(name = "Resolved"),
                            com.example.data.model.WorkItemStateDefinition(name = "Closed")
                        )
                        "کار" -> listOf(
                            com.example.data.model.WorkItemStateDefinition(name = "To Do"),
                            com.example.data.model.WorkItemStateDefinition(name = "In Progress"),
                            com.example.data.model.WorkItemStateDefinition(name = "Removed"),
                            com.example.data.model.WorkItemStateDefinition(name = "Done")
                        )
                        else -> listOf(
                            com.example.data.model.WorkItemStateDefinition(name = "New"),
                            com.example.data.model.WorkItemStateDefinition(name = "Active"),
                            com.example.data.model.WorkItemStateDefinition(name = "Closed")
                        )
                    }
                    Response.success(com.example.data.model.WorkItemStatesResponse(count = states.size, value = states))
                }
                else -> null
            }
        } as AzureDevOpsService

        val pat = "test-pat-states"
        val serviceCacheField = AzureDevOpsClient::class.java.getDeclaredField("serviceCache")
        serviceCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = serviceCacheField.get(AzureDevOpsClient) as java.util.concurrent.ConcurrentHashMap<String, AzureDevOpsService>
        cache[pat] = fakeService

        val repository = AzureDevOpsRepository(context)
        val hierarchy = repository.fetchProjectProcessHierarchy("test-org", projectName, pat)

        val projectStates = hierarchy["پروژه"].orEmpty()
        val workStates = hierarchy["کار"].orEmpty()
        assertTrue("Expected پروژه states to exclude disabled 'Resolved', got: $projectStates", "Resolved" !in projectStates)
        assertTrue("Expected پروژه states to contain New, Active, Closed", projectStates == listOf("New", "Active", "Closed"))
        assertTrue("Expected کار states to exclude disabled 'Removed', got: $workStates", "Removed" !in workStates)
        assertTrue("Expected کار states to contain To Do, In Progress, Done", workStates == listOf("To Do", "In Progress", "Done"))
    }
}
