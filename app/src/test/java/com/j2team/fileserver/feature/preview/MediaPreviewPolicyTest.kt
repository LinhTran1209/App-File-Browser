package com.j2team.fileserver.feature.preview

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreviewPolicyTest {
    @Test
    fun requiresDownloadedFileOnlyForLocalPreviewKinds() {
        assertTrue(requiresDownloadedFile(PreviewKind.Pdf))
        assertTrue(requiresDownloadedFile(PreviewKind.Image))
        assertFalse(requiresDownloadedFile(PreviewKind.Video))
        assertFalse(requiresDownloadedFile(PreviewKind.Audio))
    }

    @Test
    fun retriesAnUnauthorizedStreamOnlyOnceBeforeShowingLocalError() {
        assertEquals(StreamFailureAction.RefreshAndReprepare, streamFailureAction(isAuthenticationFailure = true, retryUsed = false, refreshInFlight = false))
        assertEquals(StreamFailureAction.ShowLocalError, streamFailureAction(isAuthenticationFailure = true, retryUsed = true, refreshInFlight = false))
        assertEquals(StreamFailureAction.IgnoreWhileRefreshing, streamFailureAction(isAuthenticationFailure = true, retryUsed = true, refreshInFlight = true))
        assertEquals(StreamFailureAction.ShowLocalError, streamFailureAction(isAuthenticationFailure = false, retryUsed = false, refreshInFlight = false))
    }

    @Test
    fun identicalRenewedTokenStillAdvancesReprepareGenerationOnlyOnce() {
        var state = StreamRetryState()
        assertEquals(StreamFailureAction.RefreshAndReprepare, streamFailureAction(true, state.retryUsed, state.refreshInFlight))

        state = state.beginRefresh().renewedSuccessfully()
        assertTrue(state.retryUsed)
        assertFalse(state.refreshInFlight)
        assertEquals(1, state.reprepareGeneration)

        assertEquals(StreamFailureAction.ShowLocalError, streamFailureAction(true, state.retryUsed, state.refreshInFlight))
    }

    @Test
    fun cleanupIncludesBothFinalAndPartialFallbackFiles() {
        val destination = File("cache/media/episode.mp4")

        assertEquals(
            listOf(destination, File("cache/media/.episode.mp4.part")),
            fallbackCleanupFiles(destination),
        )
    }

    @Test
    fun tsUsesTextEvidenceButDefaultsGenericBinaryToTransportVideo() {
        assertEquals(PreviewKind.Text, PreviewRouter.kind("client.ts", "const n = 1\n".toByteArray(), "text/plain"))
        assertEquals(PreviewKind.Text, PreviewRouter.kind("client.ts", "const n = 1\n".toByteArray(), null))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("stream.ts", byteArrayOf(0, 1, 2), "application/octet-stream"))
        assertEquals(PreviewKind.Video, PreviewRouter.kind("stream.ts", byteArrayOf(0, 1, 2), null))
    }
}
