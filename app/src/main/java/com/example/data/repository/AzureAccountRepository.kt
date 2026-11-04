package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.api.AzureDevOpsClient
import com.example.data.local.EncryptedTokenStore
import com.example.data.local.WorkItemLocalStore
import com.example.data.model.OrganizationAccount
import java.util.concurrent.ConcurrentHashMap

/**
 * Domain repository managing organization accounts, encrypted PAT credentials,
 * active defaults, and cached project lists per organization.
 */
class AzureAccountRepository(
    private val context: Context,
    private val tokenStore: EncryptedTokenStore = EncryptedTokenStore(context.applicationContext ?: context),
    private val localStore: WorkItemLocalStore = WorkItemLocalStore(context.applicationContext ?: context)
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("azure_boards_prefs", Context.MODE_PRIVATE)

    private val cachedProjectsByOrgMemory = ConcurrentHashMap<String, List<String>>()

    companion object {
        private const val KEY_DEFAULT_ORG = "pref_azure_user_default_organization"
        private const val KEY_DEFAULT_PROJECT = "pref_azure_user_default_project"
        private const val KEY_DEFAULT_PROJECT_PREFIX = "pref_azure_user_default_project_"
        private const val KEY_CACHED_PROJECTS_PREFIX = "pref_azure_user_cached_projects_"
        private const val KEY_SAVED_ORGS = "pref_azure_user_saved_organizations"

        fun encodeField(value: String): String =
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        fun decodeField(encoded: String): String =
            try {
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                encoded
            }

        fun extractOrganization(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ""
            val withoutProtocol = trimmed.substringAfter("https://").substringAfter("http://")
            return when {
                withoutProtocol.startsWith("dev.azure.com/") -> {
                    withoutProtocol.removePrefix("dev.azure.com/").substringBefore("/").substringBefore("?")
                }
                withoutProtocol.contains(".visualstudio.com") -> {
                    withoutProtocol.substringBefore(".visualstudio.com")
                }
                else -> {
                    withoutProtocol.substringBefore("/").substringBefore("?")
                }
            }.trim()
        }
    }

    private fun persistOrganizationsList(accounts: List<OrganizationAccount>) {
        val distinct = accounts.distinctBy { it.organization.lowercase() }
        val serialized = distinct.mapNotNull { acc ->
            val encryptedPat = tokenStore.encryptToken(acc.pat)
            if (encryptedPat.isNullOrBlank()) {
                null
            } else {
                "${encodeField(acc.organization)}:${encodeField(encryptedPat)}"
            }
        }.joinToString("|")
        prefs.edit().putString(KEY_SAVED_ORGS, serialized).commit()
    }

    fun getManuallySavedOrganizations(): List<OrganizationAccount> {
        val raw = prefs.getString(KEY_SAVED_ORGS, null)
        if (raw.isNullOrBlank()) return emptyList()
        var needsMigration = false
        val accounts = raw.split("|").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val org = extractOrganization(decodeField(parts[0]))
                val decodedPatField = decodeField(parts[1]).trim()
                if (!tokenStore.isEncrypted(decodedPatField) && decodedPatField.isNotBlank()) {
                    needsMigration = true
                }
                val pat = tokenStore.decryptToken(decodedPatField).trim()
                if (org.isNotBlank() && pat.isNotBlank()) {
                    OrganizationAccount(organization = org, pat = pat, isRemembered = true)
                } else {
                    null
                }
            } else {
                null
            }
        }.distinctBy { it.organization.lowercase() }

        if (needsMigration && accounts.isNotEmpty()) {
            persistOrganizationsList(accounts)
        }
        return accounts
    }

    fun isExplicitlyDisconnected(): Boolean {
        return prefs.getBoolean("pref_azure_explicit_disconnect", false)
    }

    fun setExplicitlyDisconnected(disconnected: Boolean) {
        prefs.edit().putBoolean("pref_azure_explicit_disconnect", disconnected).apply()
    }

    fun getSavedOrganizations(): List<OrganizationAccount> {
        return getManuallySavedOrganizations()
    }

    fun saveOrganizationAccount(account: OrganizationAccount) {
        val normOrg = extractOrganization(account.organization)
        val cleanPat = account.pat.trim()
        if (normOrg.isBlank() || cleanPat.isBlank()) return

        setExplicitlyDisconnected(false)

        val existing = getManuallySavedOrganizations().toMutableList()
        val existingIdx = existing.indexOfFirst { it.organization.equals(normOrg, ignoreCase = true) }

        if (account.isRemembered) {
            val normalizedAccount = OrganizationAccount(normOrg, cleanPat, isRemembered = true)
            if (existingIdx >= 0) {
                existing[existingIdx] = normalizedAccount
            } else {
                existing.add(normalizedAccount)
            }
            persistOrganizationsList(existing)
            if (getDefaultOrganization().isNullOrBlank() || existing.size == 1) {
                saveDefaultOrganization(normOrg)
            }
        } else {
            if (existingIdx >= 0) {
                existing.removeAt(existingIdx)
                persistOrganizationsList(existing)
            }
        }
    }

    fun removeOrganizationAccount(organization: String) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        val existing = getManuallySavedOrganizations()
        existing
            .filter { it.organization.equals(normOrg, ignoreCase = true) }
            .forEach { acc ->
                if (acc.pat.isNotBlank()) {
                    AzureDevOpsClient.evictService(acc.pat)
                }
            }
        localStore.clearOrganizationCache(normOrg)
        cachedProjectsByOrgMemory.keys
            .filter { it.equals(normOrg, ignoreCase = true) }
            .forEach { cachedProjectsByOrgMemory.remove(it) }
        prefs.edit()
            .remove("$KEY_CACHED_PROJECTS_PREFIX$normOrg")
            .remove("$KEY_CACHED_PROJECTS_PREFIX${normOrg.lowercase()}")
            .apply()
        val remaining = existing.filterNot {
            it.organization.equals(normOrg, ignoreCase = true)
        }
        persistOrganizationsList(remaining)
        val currentDefaultOrg = getDefaultOrganization()
        if (currentDefaultOrg != null && currentDefaultOrg.equals(normOrg, ignoreCase = true)) {
            saveDefaultOrganization(getSavedOrganizations().firstOrNull()?.organization)
        }
    }

    fun getDefaultOrganization(): String? {
        val savedDefault = prefs.getString(KEY_DEFAULT_ORG, null)?.trim()?.takeIf { it.isNotBlank() }
        val savedOrgs = getSavedOrganizations()
        if (savedDefault != null) {
            val matched = savedOrgs.firstOrNull { it.organization.equals(savedDefault, ignoreCase = true) }
            if (matched != null) return matched.organization
        }
        return savedOrgs.firstOrNull()?.organization
    }

    fun saveDefaultOrganization(organization: String?) {
        val normOrg = organization?.let { extractOrganization(it) }?.takeIf { it.isNotBlank() }
        if (normOrg != null) {
            prefs.edit().putString(KEY_DEFAULT_ORG, normOrg).commit()
        } else {
            prefs.edit().remove(KEY_DEFAULT_ORG).commit()
        }
    }

    fun getConfiguredUrl(): String {
        val defaultOrg = getDefaultOrganization()
        if (!defaultOrg.isNullOrBlank()) return defaultOrg
        return getSavedOrganizations().firstOrNull()?.organization.orEmpty()
    }

    fun getConfiguredPat(): String {
        val defOrg = getDefaultOrganization()
        return if (!defOrg.isNullOrBlank()) {
            getSavedOrganizations().firstOrNull { it.organization.equals(defOrg, ignoreCase = true) }?.pat
                ?: getSavedOrganizations().firstOrNull()?.pat
                ?: ""
        } else {
            getSavedOrganizations().firstOrNull()?.pat ?: ""
        }
    }

    fun getDefaultProject(organization: String? = null): String? {
        val targetOrg = organization?.let { extractOrganization(it) } ?: getDefaultOrganization()
        if (!targetOrg.isNullOrBlank()) {
            val orgSpecificKey = "$KEY_DEFAULT_PROJECT_PREFIX$targetOrg"
            val orgProject = prefs.getString(orgSpecificKey, null)?.takeIf { it.isNotBlank() }
            if (orgProject != null) return orgProject
        }
        return prefs.getString(KEY_DEFAULT_PROJECT, null)?.takeIf { it.isNotBlank() }
    }

    fun saveDefaultProject(project: String, organization: String? = null) {
        val clean = project.trim()
        val targetOrg = organization?.let { extractOrganization(it) } ?: getDefaultOrganization()
        val editor = prefs.edit()
        if (clean.isEmpty()) {
            if (!targetOrg.isNullOrBlank()) {
                editor.remove("$KEY_DEFAULT_PROJECT_PREFIX$targetOrg")
            }
            editor.remove(KEY_DEFAULT_PROJECT)
        } else {
            if (!targetOrg.isNullOrBlank()) {
                editor.putString("$KEY_DEFAULT_PROJECT_PREFIX$targetOrg", clean)
            }
            editor.putString(KEY_DEFAULT_PROJECT, clean)
        }
        editor.apply()
    }

    fun getCachedProjectsForOrg(organization: String): List<String> {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return emptyList()
        cachedProjectsByOrgMemory[normOrg]?.let { return it }
        val raw = prefs.getString("$KEY_CACHED_PROJECTS_PREFIX$normOrg", null)
            ?: prefs.getString("$KEY_CACHED_PROJECTS_PREFIX${normOrg.lowercase()}", null)
            ?: return emptyList()
        val list = raw.split("|||").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        cachedProjectsByOrgMemory[normOrg] = list
        return list
    }

    fun saveCachedProjectsForOrg(organization: String, projects: List<String>) {
        val normOrg = extractOrganization(organization)
        if (normOrg.isBlank()) return
        val distinct = projects.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        cachedProjectsByOrgMemory[normOrg] = distinct
        val joined = distinct.joinToString("|||")
        prefs.edit()
            .putString("$KEY_CACHED_PROJECTS_PREFIX$normOrg", joined)
            .putString("$KEY_CACHED_PROJECTS_PREFIX${normOrg.lowercase()}", joined)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
        cachedProjectsByOrgMemory.clear()
        tokenStore.clearFallbackKey()
        localStore.clearAllCaches()
        AzureDevOpsClient.clearServiceCache()
        AzureIdentityRegistry.clearKnownIdentitiesForTesting()
        com.example.sync.AzureSyncWorker.cancelPeriodicSync(context)
        setExplicitlyDisconnected(true)
    }

    fun extractOrganization(input: String): String = Companion.extractOrganization(input)
}
