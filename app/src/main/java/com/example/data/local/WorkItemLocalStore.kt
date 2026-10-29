package com.example.data.local

import android.content.Context
import com.example.data.model.CachedOrganizationMetadata
import com.example.data.model.CachedOrganizationSnapshot
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides fast, atomic on-device persistence for work items, progressive sync queues,
 * and project process/category metadata per Azure DevOps organization.
 */
class WorkItemLocalStore(context: Context) {

    private val cacheDir: File = File(context.filesDir, "azure_work_items_cache").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val snapshotAdapter = moshi.adapter(CachedOrganizationSnapshot::class.java)
    private val metadataAdapter = moshi.adapter(CachedOrganizationMetadata::class.java)

    private val writeMutex = Mutex()
    private val inMemorySnapshots = ConcurrentHashMap<String, CachedOrganizationSnapshot>()
    private val inMemoryMetadata = ConcurrentHashMap<String, CachedOrganizationMetadata>()

    private fun sanitizeOrgKey(organization: String): String {
        return organization.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
    }

    private fun snapshotFile(orgKey: String): File =
        File(cacheDir, "snapshot_${orgKey}.json")

    private fun metadataFile(orgKey: String): File =
        File(cacheDir, "metadata_${orgKey}.json")

    /**
     * Loads the cached work item snapshot for [organization] from memory or local storage.
     */
    fun loadSnapshotSync(organization: String): CachedOrganizationSnapshot? {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return null
        inMemorySnapshots[orgKey]?.let { return it }

        val file = snapshotFile(orgKey)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val rawJson = file.readText(Charsets.UTF_8)
            val parsed = snapshotAdapter.fromJson(rawJson)
            if (parsed != null) {
                inMemorySnapshots[orgKey] = parsed
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadSnapshot(organization: String): CachedOrganizationSnapshot? =
        withContext(Dispatchers.IO) {
            loadSnapshotSync(organization)
        }

    /**
     * Atomically persists the work item snapshot for [organization] to device storage.
     */
    suspend fun saveSnapshot(snapshot: CachedOrganizationSnapshot) = withContext(Dispatchers.IO) {
        val orgKey = sanitizeOrgKey(snapshot.organization)
        if (orgKey.isBlank()) return@withContext
        inMemorySnapshots[orgKey] = snapshot

        writeMutex.withLock {
            try {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val targetFile = snapshotFile(orgKey)
                val tempFile = File(cacheDir, "snapshot_${orgKey}.tmp")
                val json = snapshotAdapter.toJson(snapshot)
                tempFile.writeText(json, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.writeText(json, Charsets.UTF_8)
                    tempFile.delete()
                }
            } catch (_: Exception) {
                // Ignore disk write failure gracefully
            }
        }
    }

    /**
     * Loads cached project process hierarchy & category metadata for [organization].
     */
    fun loadMetadataSync(organization: String): CachedOrganizationMetadata? {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return null
        inMemoryMetadata[orgKey]?.let { return it }

        val file = metadataFile(orgKey)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val rawJson = file.readText(Charsets.UTF_8)
            val parsed = metadataAdapter.fromJson(rawJson)
            if (parsed != null) {
                inMemoryMetadata[orgKey] = parsed
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadMetadata(organization: String): CachedOrganizationMetadata? =
        withContext(Dispatchers.IO) {
            loadMetadataSync(organization)
        }

    /**
     * Atomically persists project process hierarchy & category metadata for [organization].
     */
    suspend fun saveMetadata(metadata: CachedOrganizationMetadata) = withContext(Dispatchers.IO) {
        val orgKey = sanitizeOrgKey(metadata.organization)
        if (orgKey.isBlank()) return@withContext
        inMemoryMetadata[orgKey] = metadata

        writeMutex.withLock {
            try {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val targetFile = metadataFile(orgKey)
                val tempFile = File(cacheDir, "metadata_${orgKey}.tmp")
                val json = metadataAdapter.toJson(metadata)
                tempFile.writeText(json, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.writeText(json, Charsets.UTF_8)
                    tempFile.delete()
                }
            } catch (_: Exception) {
                // Ignore disk write failure gracefully
            }
        }
    }

    fun clearOrganizationCache(organization: String) {
        val orgKey = sanitizeOrgKey(organization)
        if (orgKey.isBlank()) return
        inMemorySnapshots.remove(orgKey)
        inMemoryMetadata.remove(orgKey)
        runCatching { snapshotFile(orgKey).delete() }
        runCatching { metadataFile(orgKey).delete() }
    }

    fun clearAllCaches() {
        inMemorySnapshots.clear()
        inMemoryMetadata.clear()
        runCatching {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}
