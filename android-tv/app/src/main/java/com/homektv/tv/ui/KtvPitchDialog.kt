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
        binding.btnPitchClose.setOnClickListener { dismiss() }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
        }
        setOnShowListener {
            binding.seekPitch.post { binding.seekPitch.requestRemoteFocus() }
        }
    }

    private fun applyPitch(requestedSemitones: Int) {
        val result = setPitch(PlaybackPitchPolicy.clamp(requestedSemitones))
        if (result.applied) {
            currentSemitones = result.semitones
            binding.txtPitchStatus.text = "半音：${formatSemitones(currentSemitones)}"
            renderValue()
            return
        }

        binding.seekPitch.progress = currentSemitones - PlaybackPitchPolicy.MIN_SEMITONES
        binding.txtPitchStatus.text = when (result.status) {
            PlaybackPitchStatus.NO_MEDIA -> "未在播放"
            PlaybackPitchStatus.NOT_READY -> "播放器尚未就绪"
            PlaybackPitchStatus.UNSUPPORTED_ENGINE -> "当前播放路线不支持变调"
            PlaybackPitchStatus.PARAMETER_REJECTED -> "播放器未接受此调值"
            PlaybackPitchStatus.APPLIED -> "半音：${formatSemitones(currentSemitones)}"
        }
    }

    private fun renderValue() {
        binding.seekPitch.progress = currentSemitones - PlaybackPitchPolicy.MIN_SEMITONES
        binding.txtPitchValue.text = formatSemitones(currentSemitones)
        binding.txtPitchStatus.text = "半音：${formatSemitones(currentSemitones)}"
    }

    private fun formatSemitones(value: Int): String = if (value > 0) "+$value" else value.toString()

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
