package com.example

import com.example.data.model.WorkItem
import com.example.data.repository.AzureAccountRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testOrganizationExtraction() {
        assertEquals("contoso", AzureAccountRepository.extractOrganization("https://dev.azure.com/contoso"))
        assertEquals("contoso", AzureAccountRepository.extractOrganization("https://dev.azure.com/contoso/"))
        assertEquals("contoso", AzureAccountRepository.extractOrganization("dev.azure.com/contoso"))
        assertEquals("mycompany", AzureAccountRepository.extractOrganization("https://mycompany.visualstudio.com/"))
        assertEquals("plain-org", AzureAccountRepository.extractOrganization("plain-org"))
    }

    @Test
    fun testWorkItemFiltering() {
        val items = listOf(
            WorkItem(
                id = 101,
                title = "Fix login crash",
                workItemType = "Bug",
                state = "Active"
            ),
            WorkItem(
                id = 102,
                title = "Implement biometric unlock",
                workItemType = "User Story",
                state = "New"
            )
        )

        val bugs = items.filter { it.workItemType.equals("Bug", ignoreCase = true) }
        assertEquals(1, bugs.size)
        assertEquals(101, bugs[0].id)

        val active = items.filter { it.state.equals("Active", ignoreCase = true) }
        assertEquals(1, active.size)
        assertEquals("Fix login crash", active[0].title)
    }
}
