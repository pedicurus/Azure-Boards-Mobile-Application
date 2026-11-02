package com.example.data.repository

import com.example.data.api.AzureDevOpsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Domain repository responsible for fetching Azure DevOps Area and Iteration classification trees.
 */
class AzureClassificationRepository {

    /**
     * Fetches the authoritative Area and Iteration classification trees for [project] from
     * Azure DevOps (`_apis/wit/classificationnodes?$depth=10`) and returns `(areaPaths, iterationPaths)`
     * as canonical backslash-separated paths (e.g. `Project`, `Project\Team A`, `Project\Team A\Backend`).
     */
    suspend fun fetchProjectClassificationPaths(
        organization: String,
        project: String,
        pat: String
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
        val cleanProject = project.trim()
        if (normalizedOrg.isBlank() || cleanProject.isBlank() || pat.isBlank()) {
            return@withContext emptyList<String>() to emptyList()
        }

        val areaPaths = linkedSetOf<String>()
        val iterationPaths = linkedSetOf<String>()

        fun collectFromTree(
            node: com.example.data.model.RawClassificationNode?,
            parentPath: String?,
            isRootStructureNode: Boolean,
            targetSet: MutableSet<String>
        ) {
            if (node == null) return
            val nodeName = node.name?.trim().orEmpty()
            val currentPath = when {
                parentPath == null || isRootStructureNode -> cleanProject
                nodeName.isNotBlank() -> "$parentPath\\$nodeName"
                else -> parentPath
            }
            if (currentPath.isNotBlank()) {
                targetSet.add(currentPath)
            }
            node.children.orEmpty().forEach { child ->
                collectFromTree(
                    node = child,
                    parentPath = currentPath,
                    isRootStructureNode = false,
                    targetSet = targetSet
                )
            }
        }

        try {
            val service = AzureDevOpsClient.createService(pat)
            coroutineScope {
                val combinedDeferred = async {
                    runCatching {
                        service.getProjectClassificationNodes(
                            organization = normalizedOrg,
                            project = cleanProject,
                            depth = 10
                        )
                    }.getOrNull()
                }
                val combinedResp = combinedDeferred.await()
                val rootNodes = combinedResp?.body()?.value.orEmpty()

                if (rootNodes.isNotEmpty()) {
                    for (root in rootNodes) {
                        val structureType = root.structureType?.trim()?.lowercase().orEmpty()
                        when {
                            structureType.contains("area") ->
                                collectFromTree(root, null, isRootStructureNode = true, targetSet = areaPaths)
                            structureType.contains("iteration") ->
                                collectFromTree(root, null, isRootStructureNode = true, targetSet = iterationPaths)
                        }
                    }
                }

                if (areaPaths.isEmpty() || iterationPaths.isEmpty()) {
                    val areasDef = async {
                        if (areaPaths.isEmpty()) {
                            runCatching {
                                service.getProjectClassificationTreeByGroup(
                                    organization = normalizedOrg,
                                    project = cleanProject,
                                    structureGroup = "areas",
                                    depth = 10
                                )
                            }.getOrNull()?.body()
                        } else null
                    }
                    val iterationsDef = async {
                        if (iterationPaths.isEmpty()) {
                            runCatching {
                                service.getProjectClassificationTreeByGroup(
                                    organization = normalizedOrg,
                                    project = cleanProject,
                                    structureGroup = "iterations",
                                    depth = 10
                                )
                            }.getOrNull()?.body()
                        } else null
                    }
                    areasDef.await()?.let { areaRoot ->
                        collectFromTree(areaRoot, null, isRootStructureNode = true, targetSet = areaPaths)
                    }
                    iterationsDef.await()?.let { iterationRoot ->
                        collectFromTree(iterationRoot, null, isRootStructureNode = true, targetSet = iterationPaths)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network errors; fallback paths from work items will still populate the hierarchy
        }

        areaPaths.toList() to iterationPaths.toList()
    }
}
