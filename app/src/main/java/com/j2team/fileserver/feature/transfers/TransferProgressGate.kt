package com.j2team.fileserver.feature.transfers

internal data class ProgressDecision(val publish: Boolean, val persist: Boolean)

/** Keeps UI progress smooth while avoiding hundreds of preference writes per second. */
internal class TransferProgressGate(
    private val uiIntervalNanos: Long = 200_000_000L,
    private val persistIntervalNanos: Long = 2_000_000_000L,
    private val clock: () -> Long = System::nanoTime,
) {
    private var lastUi = Long.MIN_VALUE
    private var lastPersist = Long.MIN_VALUE

    fun next(force: Boolean = false): ProgressDecision {
        val now = clock()
        val publish = force || lastUi == Long.MIN_VALUE || now - lastUi >= uiIntervalNanos
        val persist = force || lastPersist == Long.MIN_VALUE || now - lastPersist >= persistIntervalNanos
        if (publish) lastUi = now
        if (persist) lastPersist = now
        return ProgressDecision(publish || persist, persist)
    }
}
