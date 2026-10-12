package io.wutz.apollo.ui.kanban

import io.wutz.apollo.data.model.KanbanTask

data class KanbanRunningGroup(
    val assignee: String?,
    val tasks: List<KanbanTask>,
)

object KanbanLaneGrouping {
    fun groupRunningTasks(tasks: List<KanbanTask>): List<KanbanRunningGroup> {
        val grouped = tasks.groupBy { it.assignee?.takeIf { a -> a.isNotBlank() } }
        val namedGroups =
            grouped
                .filterKeys { it != null }
                .entries
                .sortedBy { it.key!!.lowercase() }
                .map { KanbanRunningGroup(assignee = it.key, tasks = it.value) }
        val unassignedTasks = grouped[null]
        return if (!unassignedTasks.isNullOrEmpty()) {
            namedGroups + KanbanRunningGroup(assignee = null, tasks = unassignedTasks)
        } else {
            namedGroups
        }
    }
}
