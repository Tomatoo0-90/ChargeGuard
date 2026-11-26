package com.chargeguard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RawRes
import com.chargeguard.R
import com.chargeguard.domain.model.AlarmSound
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the looping siren on the ALARM audio stream.
 *
 * - USAGE_ALARM: routed to the speaker, respected by Do Not Disturb's "alarms" rule, and scaled
 *   by the system alarm volume. ChargeGuard never bypasses Android's volume or DND policies.
 * - Single player: [start] while already playing is a no-op, so duplicate events can never
 *   stack sirens.
 * - Audio focus is requested, but focus loss does not stop the siren: it is an alarm.
 * - The player holds a partial wake lock while playing.
 * - If the player reports an error mid-alarm it is re-created (bounded retries).
 */
@Singleton
class AlarmController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var previewPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var savedAlarmVolume: Int? = null
    private var currentSound: AlarmSound = AlarmSound.SIREN
    private var currentGain: Float = 1f
    private var restartAttempts = 0

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val isActive: Boolean get() = player != null

    /** @return true if the siren is playing. */
    @Synchronized
    fun start(sound: AlarmSound, gain: Float, raiseSystemAlarmVolume: Boolean): Boolean {
        if (player != null) return true
        stopPreview()
        currentSound = sound
        currentGain = gain.coerceIn(0f, 1f)
        restartAttempts = 0
        if (raiseSystemAlarmVolume) raiseAlarmVolume()
        requestFocus()
        player = createPlayer(sound, currentGain, looping = true)
        return player != null
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        player?.let { releaseQuietly(it) }
        player = null
        abandonFocus()
        restoreAlarmVolume()
    }

    /** Plays ~3 s of a sound for the settings preview. Refused while a real alarm is active. */
    @Synchronized
    fun preview(sound: AlarmSound, gain: Float): Boolean {
        if (player != null) return false
        stopPreview()
        val p = createPlayer(sound, gain.coerceIn(0f, 1f), looping = false, wake = false) ?: return false
        previewPlayer = p
        handler.postDelayed({ stopPreview() }, PREVIEW_MILLIS)
        return true
    }

    @Synchronized
    fun stopPreview() {
        previewPlayer?.let { releaseQuietly(it) }
        previewPlayer = null
    }

    /** Current system alarm volume as 0..1, for the settings screen. */
    fun systemAlarmVolumeFraction(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_ALARM) / max.toFloat()
    }

    private fun createPlayer(sound: AlarmSound, gain: Float, looping: Boolean, wake: Boolean = true): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(rawRes(sound)).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.isLooping = looping
            if (wake) mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setVolume(gain, gain)
            if (looping) {
                mp.setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "Siren error what=$what extra=$extra; restarting")
                    scheduleRestart(failed)
                    true
                }
            }
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start alarm audio", e)
            releaseQuietly(mp)
            null
        }
    }

    private fun scheduleRestart(failed: MediaPlayer) {
        handler.postAtTime({
            synchronized(this) {
                if (player !== failed) return@synchronized
                releaseQuietly(failed)
                player = null
                if (restartAttempts++ < MAX_RESTARTS) {
                    player = createPlayer(currentSound, currentGain, looping = true)
                }
            }
        }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + 300)
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // The alarm keeps playing on focus loss; it must not be silenced by another app.
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun raiseAlarmVolume() {
        runCatching {
            if (savedAlarmVolume == null) savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w(TAG, "System refused alarm volume change", it) }
    }

    private fun restoreAlarmVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }

    private fun releaseQuietly(mp: MediaPlayer) {
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.reset() }
        runCatching { mp.release() }
    }

    companion object {
        private const val TAG = "AlarmController"
        private const val PREVIEW_MILLIS = 3_000L
        private const val MAX_RESTARTS = 3
        private val RESTART_TOKEN = Any()

        @RawRes
        fun rawRes(sound: AlarmSound): Int = when (sound) {
            AlarmSound.SIREN -> R.raw.alarm_siren
            AlarmSound.PULSE -> R.raw.alarm_pulse
            AlarmSound.KLAXON -> R.raw.alarm_klaxon
        }
    }
}
