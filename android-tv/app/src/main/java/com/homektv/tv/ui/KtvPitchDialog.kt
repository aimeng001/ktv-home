package com.homektv.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.SeekBar
import com.homektv.tv.databinding.DialogKtvPitchBinding
import com.homektv.tv.player.PlaybackPitchPolicy
import com.homektv.tv.player.PlaybackPitchResult
import com.homektv.tv.player.PlaybackPitchStatus

/** Small pitch scale backed by the active Media3 player. */
class KtvPitchDialog(
    context: Context,
    initialSemitones: Int,
    private val setPitch: (Int) -> PlaybackPitchResult,
    private val unavailableStatus: PlaybackPitchStatus? = null,
) : Dialog(context) {
    private lateinit var binding: DialogKtvPitchBinding
    private var currentSemitones = PlaybackPitchPolicy.clamp(initialSemitones)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvPitchBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        val unavailable = unavailableStatus
        if (unavailable != null) {
            listOf(binding.seekPitch, binding.btnPitchDown, binding.btnPitchUp, binding.btnPitchReset).forEach { control ->
                control.isEnabled = false
                control.isFocusable = false
                control.alpha = 0.45f
            }
            binding.seekPitch.progress = -PlaybackPitchPolicy.MIN_SEMITONES
            binding.txtPitchValue.text = "--"
            binding.txtPitchStatus.text = statusMessage(unavailable, currentSemitones)
        } else {
            renderValue()
            binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) applyPitch(progress + PlaybackPitchPolicy.MIN_SEMITONES)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
            binding.btnPitchDown.setOnClickListener { applyPitch(currentSemitones - 1) }
            binding.btnPitchUp.setOnClickListener { applyPitch(currentSemitones + 1) }
            binding.btnPitchReset.setOnClickListener { applyPitch(0) }
        }
        binding.btnPitchClose.setOnClickListener { dismiss() }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
        }
        setOnShowListener {
            if (unavailable != null) {
                binding.btnPitchClose.post { binding.btnPitchClose.requestRemoteFocus() }
            } else {
                binding.seekPitch.post { binding.seekPitch.requestRemoteFocus() }
            }
        }
    }

    private fun applyPitch(requestedSemitones: Int) {
        val result = setPitch(PlaybackPitchPolicy.clamp(requestedSemitones))
        if (result.applied) {
            currentSemitones = result.semitones
            binding.txtPitchStatus.text = statusMessage(PlaybackPitchStatus.APPLIED, currentSemitones)
            renderValue()
            return
        }

        binding.seekPitch.progress = currentSemitones - PlaybackPitchPolicy.MIN_SEMITONES
        binding.txtPitchStatus.text = statusMessage(result.status, currentSemitones)
    }

    private fun statusMessage(status: PlaybackPitchStatus, semitones: Int): String = when (status) {
        PlaybackPitchStatus.APPLIED -> "半音：${formatSemitones(semitones)}"
        PlaybackPitchStatus.NO_MEDIA -> "未在播放"
        PlaybackPitchStatus.NOT_READY -> "播放器尚未就绪"
        PlaybackPitchStatus.UNSUPPORTED_ENGINE -> "当前播放路线不支持变调"
        PlaybackPitchStatus.PARAMETER_REJECTED -> "播放器未接受此调值"
    }

    private fun renderValue() {
        binding.seekPitch.progress = currentSemitones - PlaybackPitchPolicy.MIN_SEMITONES
        binding.txtPitchValue.text = formatSemitones(currentSemitones)
        binding.txtPitchStatus.text = statusMessage(PlaybackPitchStatus.APPLIED, currentSemitones)
    }

    private fun formatSemitones(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
