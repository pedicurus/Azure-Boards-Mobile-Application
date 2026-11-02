package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.OrganizationAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrganizationAccountStorageTest {

    private lateinit var context: Context
    private lateinit var repository: AzureDevOpsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = AzureDevOpsRepository(context)
        repository.clearCredentials()
    }

    @Test
    fun testEmptyStorageInitially() {
        val allOrgs = repository.getSavedOrganizations()
        assertTrue("When no accounts are saved, saved orgs must be empty", allOrgs.isEmpty())
    }

    @Test
    fun testManualAccountStorageAndRetrieval() {
        val testAccount = OrganizationAccount(
            organization = "contoso-dev",
            pat = "token-xyz-123",
            isRemembered = true
        )
        repository.saveOrganizationAccount(testAccount)

        val saved = repository.getSavedOrganizations()
        assertEquals(1, saved.size)
        assertEquals("contoso-dev", saved[0].organization)
        assertEquals("token-xyz-123", saved[0].pat)
    }

    @Test
    fun testMultipleAccountStorageAndRemoval() {
        repository.saveOrganizationAccount(OrganizationAccount("org-1", "pat-1", true))
        repository.saveOrganizationAccount(OrganizationAccount("org-2", "pat-2", true))

        var saved = repository.getSavedOrganizations()
        assertEquals(2, saved.size)

        repository.removeOrganizationAccount("org-1")
        saved = repository.getSavedOrganizations()
        assertEquals(1, saved.size)
        assertEquals("org-2", saved[0].organization)
    }
}
