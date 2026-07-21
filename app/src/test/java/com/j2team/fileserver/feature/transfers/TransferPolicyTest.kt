package com.j2team.fileserver.feature.transfers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPolicyTest {
    @Test
    fun badgeCountsTasksNeedingAttentionAndCapsAt99() {
        val mixed = listOf(
            task(TransferState.Queued), task(TransferState.Running), task(TransferState.Paused),
            task(TransferState.Failed), task(TransferState.Completed), task(TransferState.Cancelled),
        )

        assertEquals(4, mixed.attentionCount())
        assertEquals("4", mixed.attentionBadge())
        assertEquals("99+", List(100) { task(TransferState.Queued) }.attentionBadge())
    }

    @Test
    fun tabsSeparateDirections() {
        val tasks = listOf(task(TransferState.Queued, TransferDirection.Upload), task(TransferState.Failed, TransferDirection.Download))

        assertTrue(tasks.forTab(TransferTab.Uploads).all { it.direction == TransferDirection.Upload })
        assertTrue(tasks.forTab(TransferTab.Downloads).all { it.direction == TransferDirection.Download })
    }

    private fun task(state: TransferState, direction: TransferDirection = TransferDirection.Download) = TransferTask(
        id = "$state-$direction-${System.nanoTime()}", name = "file", path = "/", direction = direction, state = state,
    )
}
