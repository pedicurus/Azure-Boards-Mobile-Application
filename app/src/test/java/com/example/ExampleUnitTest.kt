package com.example

import com.example.data.model.WorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private fun extractOrganization(input: String): String {
        var clean = input.trim()
        clean = clean.removePrefix("https://").removePrefix("http://")
        clean = clean.trimEnd('/')

        if (clean.contains("dev.azure.com/")) {
            val parts = clean.split("dev.azure.com/")
            if (parts.size > 1) {
                return parts[1].split("/").firstOrNull()?.trim() ?: clean
            }
        }
        if (clean.contains(".visualstudio.com")) {
            val parts = clean.split(".visualstudio.com")
            if (parts.isNotEmpty()) {
                return parts[0].substringAfterLast("/").trim()
            }
        }
        return clean.substringAfterLast("/").trim()
    }

    @Test
    fun testOrganizationExtraction() {
        assertEquals("contoso", extractOrganization("https://dev.azure.com/contoso"))
        assertEquals("contoso", extractOrganization("https://dev.azure.com/contoso/"))
        assertEquals("contoso", extractOrganization("dev.azure.com/contoso"))
        assertEquals("mycompany", extractOrganization("https://mycompany.visualstudio.com/"))
        assertEquals("plain-org", extractOrganization("plain-org"))
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
