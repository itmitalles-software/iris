package io.wutz.apollo.data.model

import io.wutz.apollo.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The learning graph is built by hermes-agent `agent/learning_graph.py`, which
 * emits camelCase `useCount`/`createdBy` while the app's wire Json applies a
 * snake_case naming strategy to every serial name, including `@SerialName` ones.
 */
class LearningGraphSerializationTest {
    @Test
    fun `decodes the camelCase node fields the server sends`() {
        val body =
            """
            {"nodes": [{"id": "skill:git", "label": "git", "kind": "skill", "category": "dev",
              "useCount": 7, "state": "active", "createdBy": "agent", "pinned": true}],
             "edges": [], "memory": []}
            """.trimIndent()

        val node =
            OkHttpProvider.json
                .decodeFromString<LearningGraphResponse>(body)
                .nodes
                .single()

        assertEquals(7, node.useCount)
        assertEquals("agent", node.createdBy)
        assertEquals(true, node.pinned)
    }
}
