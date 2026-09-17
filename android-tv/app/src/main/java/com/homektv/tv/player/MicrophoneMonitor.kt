package com.homektv.tv.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 麦克风输入监视器，选择外部输入设备并把采集状态回调给界面。
 *
 * Microphone input monitor that selects external devices and reports capture state to the UI.
 */
class MicrophoneMonitor(
    context: Context,
    private val onStateChanged: (State) -> Unit,
) {
    data class State(
        val active: Boolean,
        val deviceName: String? = null,
        val message: String? = null,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val sessionCoordinator = MicrophoneSessionCoordinator()
    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var communicationDevice: AudioDeviceInfo? = null

    /**
     * 枚举并按优先级返回可用的外部麦克风输入设备。
     *
     * Enumerates available external microphone inputs and sorts them by priority.
     */
    fun externalInputs(): List<AudioDeviceInfo> = runCatching {
        val devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
        val description = devices.joinToString { device ->
            "${device.id}:${device.type}:${device.productName}:source=${device.isSource}:rates=${device.sampleRates.contentToString()}"
        }
        Log.i(TAG, "input devices=$description")
        devices.filter(MicrophoneInputSelector::isSupportedExternalInput)
            .sortedBy(MicrophoneInputSelector::priority)
    }.getOrElse {
        Log.w(TAG, "cannot enumerate microphone inputs", it)
        emptyList()
    }

    /**
     * 启动麦克风监听；权限或设备不可用时返回 false。
     *
     * Starts microphone monitoring and returns false when permission or hardware is unavailable.
     */
    fun start(): Boolean {
        if (sessionCoordinator.running.get() && worker?.isAlive == true) return true
        sessionCoordinator.stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStateChanged(State(false, message = "需要麦克风权限"))
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            externalInputs().any(MicrophoneInputSelector::isBluetoothInput)
        ) {
            onStateChanged(State(false, message = "需要蓝牙连接权限"))
            return false
        }
        val input = externalInputs().firstOrNull() ?: defaultRoutedInput()
        if (input == null) {
            Log.w(TAG, "no supported external microphone found")
            onStateChanged(State(false, message = "未发现外接麦克风"))
            return false
        }
        Log.i(TAG, "starting microphone id=${input.id} type=${input.type} name=${input.productName}")
        return runCatching { startWithDevice(input) }.getOrElse {
            Log.e(TAG, "cannot start microphone", it)
            onStateChanged(State(false, message = it.message ?: "麦克风启动失败"))
            false
        }
    }

    private fun defaultRoutedInput(): AudioDeviceInfo? {
        if (!MicrophoneInputSelector.allowsDefaultRoutedInput(Build.MODEL)) return null
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.also { Log.w(TAG, "using vendor default-routed microphone for model=${Build.MODEL}") }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun startWithDevice(input: AudioDeviceInfo): Boolean {
        stop()
        prepareBluetoothRoute(input)

        val sampleRate = preferredSampleRate(input)
        val inputBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 50 * 2)
        val outputBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(inputBuffer)

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(inputBuffer * 2)
            .build()
        if (input.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            val preferred = record.setPreferredDevice(input)
            Log.i(TAG, "preferred microphone accepted=$preferred id=${input.id} type=${input.type}")
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(outputBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
            record.release()
            track.release()
            clearBluetoothRoute()
            onStateChanged(State(false, message = "麦克风音频通道初始化失败"))
            return false
        }

        audioRecord = record
        audioTrack = track
        val session = sessionCoordinator.beginSession()
        worker = thread(name = "ktv-microphone-monitor", isDaemon = true) {
            val buffer = ShortArray(inputBuffer / 2)
            try {
                track.play()
                record.startRecording()
                Log.i(TAG, "microphone active device=${input.productName} sampleRate=$sampleRate buffer=$inputBuffer")
                if (sessionCoordinator.isCurrent(session)) {
                    onStateChanged(State(true, input.productName?.toString() ?: MicrophoneInputSelector.label(input.type)))
                }
                while (sessionCoordinator.isCurrent(session)) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "microphone monitor failed", error)
                if (sessionCoordinator.isCurrent(session)) {
                    sessionCoordinator.endSession(session)
                    onStateChanged(State(false, message = error.message ?: "麦克风监听失败"))
                }
            } finally {
                releaseAudioObjects(record, track)
                sessionCoordinator.endSession(session)
            }
        }
        return true
    }

    fun stop() {
        sessionCoordinator.stop()
        audioRecord?.stopSafely()
        audioTrack?.pauseSafely()
        worker?.interrupt()
        worker = null
        audioRecord = null
        audioTrack = null
        clearBluetoothRoute()
    }

    fun release() = stop()

    @SuppressLint("MissingPermission")
    private fun prepareBluetoothRoute(input: AudioDeviceInfo) {
        if (!MicrophoneInputSelector.isBluetoothInput(input)) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (audioManager.setCommunicationDevice(input)) communicationDevice = input
        } else {
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                throw IllegalStateException("当前设备不支持蓝牙麦克风通话音频")
            }
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun clearBluetoothRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communicationDevice != null) audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            run { audioManager.isBluetoothScoOn = false }
        }
        communicationDevice = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun preferredSampleRate(input: AudioDeviceInfo): Int {
        val rates = input.sampleRates
        return when {
            rates.contains(48_000) -> 48_000
            rates.contains(44_100) -> 44_100
            rates.contains(16_000) -> 16_000
            rates.isNotEmpty() -> rates.first()
            MicrophoneInputSelector.isBluetoothInput(input) -> 16_000
            else -> 48_000
        }
    }

    private fun releaseAudioObjects(record: AudioRecord, track: AudioTrack) {
        record.stopSafely()
        track.pauseSafely()
        record.release()
        track.release()
        if (audioRecord === record) audioRecord = null
        if (audioTrack === track) audioTrack = null
    }

    private fun AudioRecord.stopSafely() = runCatching { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop() }
    private fun AudioTrack.pauseSafely() = runCatching { if (playState == AudioTrack.PLAYSTATE_PLAYING) pause() }

    companion object {
        private const val TAG = "MicrophoneMonitor"
    }
}

object MicrophoneInputSelector {
    private val defaultRoutedInputModels = setOf("Q601F")
    private val externalInputTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    fun isSupportedExternalInput(device: AudioDeviceInfo): Boolean =
        device.isSource && isSupportedType(device.type, Build.VERSION.SDK_INT)

    fun isBluetoothInput(device: AudioDeviceInfo): Boolean =
        isBluetoothType(device.type, Build.VERSION.SDK_INT)

    internal fun isSupportedType(type: Int, sdkInt: Int): Boolean =
        type in externalInputTypes || isBleHeadset(type, sdkInt)

    internal fun isBluetoothType(type: Int, sdkInt: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBleHeadset(type, sdkInt)

    @SuppressLint("InlinedApi")
    private fun isBleHeadset(type: Int, sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    fun allowsDefaultRoutedInput(model: String): Boolean = model.uppercase() in defaultRoutedInputModels

    fun priority(device: AudioDeviceInfo): Int = priority(device.type)

    @SuppressLint("InlinedApi")
    fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
        else -> 100
    }

    @SuppressLint("InlinedApi")
    fun label(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线麦克风"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 麦克风"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "系统默认麦克风"
        else -> "外接麦克风"
    }
}
