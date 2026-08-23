package com.rinaemon.pitchshifter

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rinaemon.pitchshifter.dsp.PitchShifter
import kotlin.concurrent.thread

/**
 * Foreground service that captures the audio mix currently being played by other apps
 * (via [AudioPlaybackCaptureConfiguration], Android 10+), pitch-shifts it in real time and
 * plays the result back. The original audio from the source app keeps playing at the same
 * time — Android provides no way for one app to silence another app's own output — so an
 * optional "mute original" mode is offered that mutes the system music stream and routes our
 * own output through the alarm stream instead, as a best-effort workaround.
 */
class CaptureService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "capture_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_START = "com.rinaemon.pitchshifter.action.START"
        const val ACTION_STOP = "com.rinaemon.pitchshifter.action.STOP"

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    private var originalMusicVolume: Int? = null
    private var trackIsAlarmRouted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureAndSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, resultData)
                return START_STICKY
            }
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (running) return

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            PitchController.statusMessage.value = "画面/音声キャプチャの許可を取得できませんでした"
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val channelInMask = if (CHANNEL_COUNT == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelInMask)
            .build()

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelInMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(minBufBytes * 4)
                .build()
        } catch (e: SecurityException) {
            PitchController.statusMessage.value = "録音の権限がありません"
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            PitchController.statusMessage.value = "音声キャプチャの初期化に失敗しました"
            record.release()
            stopSelf()
            return
        }

        audioRecord = record
        audioTrack = buildAudioTrack(alarmRouted = false)
        trackIsAlarmRouted = false

        running = true
        PitchController.isCapturing.value = true
        PitchController.statusMessage.value = "キャプチャ中"

        record.startRecording()
        audioTrack?.play()

        captureThread = thread(name = "PitchCaptureThread") { captureLoop() }
    }

    private fun buildAudioTrack(alarmRouted: Boolean): AudioTrack {
        val channelOutMask =
            if (CHANNEL_COUNT == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelOutMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val attributes = AudioAttributes.Builder()
            .setUsage(if (alarmRouted) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelOutMask)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun applyMuteState(mute: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (mute && originalMusicVolume == null) {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else if (!mute && originalMusicVolume != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume!!, 0)
            originalMusicVolume = null
        }

        if (mute != trackIsAlarmRouted) {
            val oldTrack = audioTrack
            val newTrack = buildAudioTrack(alarmRouted = mute)
            audioTrack = newTrack
            trackIsAlarmRouted = mute
            newTrack.play()
            oldTrack?.stop()
            oldTrack?.release()
        }
    }

    private fun captureLoop() {
        val record = audioRecord ?: return
        val frameSamples = 2048 * CHANNEL_COUNT
        val shortBuf = ShortArray(frameSamples)
        val left = FloatArray(frameSamples / CHANNEL_COUNT)
        val right = FloatArray(frameSamples / CHANNEL_COUNT)
        val leftOut = FloatArray(frameSamples / CHANNEL_COUNT)
        val rightOut = FloatArray(frameSamples / CHANNEL_COUNT)
        val outShortBuf = ShortArray(frameSamples)

        val leftShifter = PitchShifter(SAMPLE_RATE)
        val rightShifter = PitchShifter(SAMPLE_RATE)

        var lastMute = PitchController.muteOriginal.value
        applyMuteState(lastMute)

        while (running) {
            val read = record.read(shortBuf, 0, shortBuf.size)
            if (read <= 0) continue

            val frames = read / CHANNEL_COUNT
            for (n in 0 until frames) {
                left[n] = shortBuf[n * 2] / 32768f
                right[n] = shortBuf[n * 2 + 1] / 32768f
            }

            val pitchRatio = PitchController.pitchRatio()
            leftShifter.process(left, leftOut, frames, pitchRatio)
            rightShifter.process(right, rightOut, frames, pitchRatio)

            for (n in 0 until frames) {
                outShortBuf[n * 2] = (leftOut[n].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outShortBuf[n * 2 + 1] = (rightOut[n].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }

            val mute = PitchController.muteOriginal.value
            if (mute != lastMute) {
                applyMuteState(mute)
                lastMute = mute
            }

            audioTrack?.write(outShortBuf, 0, frames * CHANNEL_COUNT)
        }
    }

    private fun stopCaptureAndSelf() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        captureThread?.join(500)
        captureThread = null

        audioRecord?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null

        audioTrack?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioTrack = null

        if (originalMusicVolume != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume!!, 0)
            originalMusicVolume = null
        }

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        PitchController.isCapturing.value = false
        PitchController.statusMessage.value = "停止中"

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCaptureAndSelf()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
