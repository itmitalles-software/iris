package io.wutz.apollo.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Data structures for the Hermes agent self-improvement / learning graph endpoint
 * GET /api/learning/graph
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LearningGraphNode(
    val id: String,
    val label: String,
    val kind: String, // "skill" | "memory"
    val timestamp: Long? = null,
    val category: String? = null,
    // hermes-agent agent/learning_graph.py sends camelCase here. The wire Json's
    // snake_case strategy also rewrites @SerialName values (to use_count), so
    // only @JsonNames, which it leaves alone, matches the real key.
    @JsonNames("useCount") val useCount: Int = 0,
    val state: String? = null,
    @JsonNames("createdBy") val createdBy: String? = null,
    val pinned: Boolean = false,
)

@Serializable
data class LearningGraphEdge(
    val source: String,
    val target: String,
)

@Serializable
data class LearningGraphMemoryCard(
    val source: String,
    val timestamp: Long? = null,
    val title: String,
    val body: String,
)

@Serializable
data class LearningGraphResponse(
    val nodes: List<LearningGraphNode> = emptyList(),
    val edges: List<LearningGraphEdge> = emptyList(),
    val memory: List<LearningGraphMemoryCard> = emptyList(),
)

/** GET /api/learning/node: the node's current content (full SKILL.md or the raw memory entry). */
@Serializable
data class LearningNodeDetail(
    val ok: Boolean = false,
    val kind: String = "",
    val id: String = "",
    val label: String = "",
    val content: String = "",
)

/** DELETE /api/learning/node body. The active profile travels as the `?profile=` query. */
@Serializable
data class LearningNodeRef(
    val id: String,
)

/** PUT /api/learning/node body. */
@Serializable
data class LearningNodeEdit(
    val id: String,
    val content: String,
)

/** Result of a node edit or delete: `ok` plus the server's message (e.g. how to restore an archived skill). */
@Serializable
data class LearningNodeMutationResponse(
    val ok: Boolean = false,
    val message: String? = null,
)
