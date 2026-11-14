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
import com.homektv.tv.databinding.DialogKtvSystemInfoBinding

/** Centered glass modal replacing system AlertDialogs for TV status and info notices. */
class KtvSystemInfoDialog(
    context: Context,
    private val title: String,
    private val message: String,
    private val secondaryActionLabel: String? = null,
) : Dialog(context) {
    private lateinit var binding: DialogKtvSystemInfoBinding
    var wasSecondaryActionTriggered: Boolean = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvSystemInfoBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        binding.txtSystemInfoTitle.text = title
        binding.txtSystemInfoMessage.text = message

        if (!secondaryActionLabel.isNullOrBlank()) {
            binding.btnSystemInfoSecondary.visibility = View.VISIBLE
            binding.btnSystemInfoSecondary.text = secondaryActionLabel
            binding.btnSystemInfoSecondary.setOnClickListener {
                wasSecondaryActionTriggered = true
                dismiss()
            }
        } else {
            binding.btnSystemInfoSecondary.visibility = View.GONE
        }
        binding.btnSystemInfoConfirm.setOnClickListener { dismiss() }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
        }
        setOnShowListener {
            binding.btnSystemInfoConfirm.post { binding.btnSystemInfoConfirm.requestRemoteFocus() }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
