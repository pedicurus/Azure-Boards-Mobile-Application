package com.example.data.repository

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.EncryptedTokenStore
import com.example.data.model.OrganizationAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private lateinit var tokenStore: EncryptedTokenStore
    private lateinit var repository: AzureDevOpsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tokenStore = EncryptedTokenStore(context)
        repository = AzureDevOpsRepository(context, tokenStore)
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

        // Verify that the underlying SharedPreferences value is encrypted with enc:v1: and not plaintext
        val prefs = context.getSharedPreferences("azure_boards_prefs", Context.MODE_PRIVATE)
        val rawPrefs = prefs.getString("pref_azure_user_saved_organizations", "").orEmpty()
        val encodedPatPart = rawPrefs.substringAfter(":")
        val decodedPatPart = String(Base64.decode(encodedPatPart, Base64.NO_WRAP), Charsets.UTF_8)
        assertTrue("Stored PAT field must be encrypted with enc:v1: prefix", tokenStore.isEncrypted(decodedPatPart))
        assertNotEquals("token-xyz-123", decodedPatPart)
    }

    @Test
    fun testLegacyPlaintextPatMigrationOnRead() {
        val prefs = context.getSharedPreferences("azure_boards_prefs", Context.MODE_PRIVATE)
        val legacyOrgEncoded = Base64.encodeToString("legacy-org".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val legacyPlainPatEncoded = Base64.encodeToString("plain-secret-pat-999".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        prefs.edit().putString("pref_azure_user_saved_organizations", "$legacyOrgEncoded:$legacyPlainPatEncoded").commit()

        val accounts = repository.getSavedOrganizations()
        assertEquals(1, accounts.size)
        assertEquals("legacy-org", accounts[0].organization)
        assertEquals("plain-secret-pat-999", accounts[0].pat)

        // Confirm it was automatically re-saved in encrypted format
        val migratedRaw = prefs.getString("pref_azure_user_saved_organizations", "").orEmpty()
        val migratedPatPart = String(Base64.decode(migratedRaw.substringAfter(":"), Base64.NO_WRAP), Charsets.UTF_8)
        assertTrue("Legacy plaintext PAT should be migrated to encrypted token on read", tokenStore.isEncrypted(migratedPatPart))
        assertFalse(migratedPatPart.contains("plain-secret-pat-999"))
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
