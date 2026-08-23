package com.sounddelay.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import kotlin.math.max

/**
 * 다른 앱이 재생 중인 오디오를 AudioPlaybackCapture로 잡아 링 버퍼에 쌓았다가,
 * 지정한 시간만큼 늦게 AudioTrack으로 다시 내보낸다.
 *
 * 출력 트랙에는 ALLOW_CAPTURE_BY_NONE을 걸어 자기 출력이 다시 캡처되어
 * 무한 에코가 생기는 것을 막는다.
 */
class DelayEngine(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val outputUsage: Int,
    initialDelayMs: Long,
    private val onStopped: () -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val MAX_DELAY_MS = 30_000L

        // 지연음을 우선 내보낼 출력 기기 순서. 없으면 시스템 기본 라우팅.
        private val PREFERRED_DEVICE_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
        private const val CHUNK_FRAMES = 960 // 20ms
        private const val CHUNK_SAMPLES = CHUNK_FRAMES * CHANNELS
        private const val RING_SAMPLES =
            (MAX_DELAY_MS.toInt() / 1000 + 2) * SAMPLE_RATE * CHANNELS
    }

    @Volatile
    private var delayMs: Long = initialDelayMs.coerceIn(0L, MAX_DELAY_MS)

    @Volatile
    private var running = false

    private val audioManager: AudioManager =
        context.getSystemService(AudioManager::class.java)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    // 이어폰 연결/해제 시 지연음 출력 기기를 다시 지정한다.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            applyPreferredDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            applyPreferredDevice()
        }
    }

    fun setDelayMs(ms: Long) {
        delayMs = ms.coerceIn(0L, MAX_DELAY_MS)
    }

    /**
     * 알람 채널을 스피커로 강제하는 기기가 있어, 이어폰(블루투스/유선)이
     * 연결되어 있으면 지연음 트랙의 출력 기기를 명시적으로 지정한다.
     */
    private fun applyPreferredDevice() {
        val t = track ?: return
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var target: AudioDeviceInfo? = null
        for (type in PREFERRED_DEVICE_TYPES) {
            target = devices.firstOrNull { it.type == type }
            if (target != null) break
        }
        t.preferredDevice = target
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val recordFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minRecordBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(recordFormat)
            .setBufferSizeInBytes(max(minRecordBytes, CHUNK_SAMPLES * 2 * 4))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("오디오 캡처를 초기화하지 못했습니다")
        }

        val outputAttrs = AudioAttributes.Builder()
            .setUsage(outputUsage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val trackFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minTrackBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val out = AudioTrack.Builder()
            .setAudioAttributes(outputAttrs)
            .setAudioFormat(trackFormat)
            .setBufferSizeInBytes(max(minTrackBytes, CHUNK_SAMPLES * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (out.state != AudioTrack.STATE_INITIALIZED) {
            rec.release()
            out.release()
            throw IllegalStateException("오디오 출력을 초기화하지 못했습니다")
        }

        record = rec
        track = out
        applyPreferredDevice()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        running = true
        thread = Thread(::loop, "DelayEngine").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        thread?.join(1_000)
        thread = null
    }

    private fun loop() {
        val rec = record ?: return
        val out = track ?: return
        val ring = ShortArray(RING_SAMPLES)
        val inBuf = ShortArray(CHUNK_SAMPLES)
        val outBuf = ShortArray(CHUNK_SAMPLES)
        var totalWritten = 0L

        try {
            rec.startRecording()
            out.play()
            while (running) {
                val read = rec.read(inBuf, 0, inBuf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR ||
                        read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) break
                    continue
                }
                // 채널(프레임) 경계가 어긋나지 않도록 잘라낸다.
                val n = read - (read % CHANNELS)
                if (n == 0) continue

                var writeIdx = (totalWritten % RING_SAMPLES).toInt()
                for (i in 0 until n) {
                    ring[writeIdx] = inBuf[i]
                    writeIdx++
                    if (writeIdx == RING_SAMPLES) writeIdx = 0
                }
                totalWritten += n

                val delaySamples = delayMs * SAMPLE_RATE / 1_000L * CHANNELS
                var readPos = totalWritten - n - delaySamples
                for (i in 0 until n) {
                    outBuf[i] = if (readPos < 0) 0 else ring[(readPos % RING_SAMPLES).toInt()]
                    readPos++
                }
                out.write(outBuf, 0, n)
            }
        } catch (_: Exception) {
            // 아래 finally에서 정리하고 onStopped로 알린다.
        } finally {
            runCatching { rec.stop() }
            rec.release()
            runCatching {
                out.pause()
                out.flush()
                out.stop()
            }
            out.release()
            record = null
            track = null
            val stoppedUnexpectedly = running
            running = false
            if (stoppedUnexpectedly) onStopped()
        }
    }
}
