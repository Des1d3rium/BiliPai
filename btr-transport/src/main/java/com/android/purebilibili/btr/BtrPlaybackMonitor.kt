package com.android.purebilibili.btr

import android.os.Handler
import androidx.media3.common.Player
import java.lang.ref.WeakReference

/**
 * What BTR's player reported to the downloader: the buffer ahead a few times a second, a stall,
 * and a seek. Samples on the player's application looper; at most one player is followed.
 */
object BtrPlaybackMonitor {
    private const val SAMPLE_MS = 250L

    private var followed: WeakReference<Player>? = null
    private var handler: Handler? = null
    private var hasBeenReady = false
    // Buffer trace for field measurements: one line a second for a while after each start.
    private var traceStartedAt = 0L
    private var traceTicks = 0
    private const val TRACE_MS = 90_000L

    private fun restartTrace() {
        traceStartedAt = android.os.SystemClock.elapsedRealtime()
        traceTicks = 0
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = followed?.get() ?: return
            when (playbackState) {
                Player.STATE_READY -> hasBeenReady = true
                // Waiting for data after playback started, not for a seek: the download is behind.
                Player.STATE_BUFFERING -> if (hasBeenReady && player.playWhenReady) BtrTransport.stall()
                else -> Unit
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                hasBeenReady = false
                restartTrace()
                BtrTransport.seek()
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            hasBeenReady = false
            restartTrace()
        }
    }

    private val sample = object : Runnable {
        override fun run() {
            val player = followed?.get() ?: return
            val aheadMs = player.bufferedPosition - player.currentPosition
            BtrTransport.playback(aheadMs, player.isPlaying, player.playbackParameters.speed)
            val sinceStart = android.os.SystemClock.elapsedRealtime() - traceStartedAt
            if (sinceStart < TRACE_MS && traceTicks++ % 4 == 0) {
                BtrTransport.log(
                    "buf t=${sinceStart / 1000.0}s ahead=${aheadMs / 1000.0}s pos=${player.currentPosition / 1000.0}s " +
                        "state=${player.playbackState} playing=${player.isPlaying} btr=${BtrTransport.isActive()}"
                )
            }
            handler?.postDelayed(this, SAMPLE_MS)
        }
    }

    /** Follows [player]; call from its application thread. Attaching the same player again is a no-op. */
    fun attach(player: Player) {
        if (followed?.get() === player) return
        detach()
        followed = WeakReference(player)
        hasBeenReady = player.playbackState == Player.STATE_READY
        restartTrace()
        player.addListener(listener)
        handler = Handler(player.applicationLooper).also { it.post(sample) }
    }

    fun detach() {
        handler?.removeCallbacks(sample)
        handler = null
        followed?.get()?.removeListener(listener)
        followed = null
    }
}
