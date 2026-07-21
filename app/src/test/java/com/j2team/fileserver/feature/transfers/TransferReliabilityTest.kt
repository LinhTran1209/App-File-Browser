package com.j2team.fileserver.feature.transfers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit

class TransferReliabilityTest {
    @Test fun failedRestoreKeepsOwnedBackupAndRetryDoesNotDeleteIt() {
        val first = BackupFinalizer.recoverAfterCopyFailure(partialDeleted = false, restored = false, backupName = ".old.attempt.backup")
        assertEquals(".old.attempt.backup", first.recoveryBackup)
        assertTrue(!BackupFinalizer.shouldDeleteBackup(ownedByAttempt = false, finalizationSucceeded = true))
    }
    @Test fun recoveryMakesInterruptedTasksActionableWithoutReplaying() {
        val recovered = recoverInterruptedTransfers(listOf(task("queued", TransferState.Queued), task("running", TransferState.Running), task("paused", TransferState.Paused), task("done", TransferState.Completed)))
        assertEquals(TransferState.Failed, recovered[0].state)
        assertEquals(TransferState.Failed, recovered[1].state)
        assertEquals(TransferState.Failed, recovered[2].state)
        assertTrue(recovered.take(3).all { it.error == TransferErrors.Interrupted })
        assertEquals(TransferState.Completed, recovered[3].state)
    }

    @Test fun removalCannotResurrectAfterLaterSave() {
        val remaining = removeTransfer(listOf(task("one", TransferState.Failed), task("two", TransferState.Completed)), "one")
        assertEquals(listOf("two"), remaining.map { it.id })
        assertTrue(remaining.none { it.id == "one" })
    }

    @Test fun processWideGateNeverAllowsMoreThanTwoStreams() = runBlocking {
        var active = 0
        var maximum = 0
        coroutineScope {
            List(6) {
                async {
                    TransferRuntime.streamSemaphore.withPermit {
                        active += 1
                        maximum = maxOf(maximum, active)
                        delay(20)
                        active -= 1
                    }
                }
            }.awaitAll()
        }
        assertTrue(maximum <= 2)
    }

    private fun task(id: String, state: TransferState) = TransferTask(id, id, "/", TransferDirection.Upload, state = state)
}
