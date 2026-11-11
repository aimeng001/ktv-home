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
import com.homektv.tv.databinding.DialogKtvCapabilityDrawerBinding

/** Right-side information drawer for Home KTV controls whose reference-only capability is unavailable. */
class KtvCapabilityDrawerDialog(
    context: Context,
    private val title: String,
    private val status: String,
    private val description: String,
    private val coordinator: KioskModeCoordinator?,
) : Dialog(context) {
    private lateinit var binding: DialogKtvCapabilityDrawerBinding

    var onDismissDrawer: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvCapabilityDrawerBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        binding.txtCapabilityTitle.text = title
        binding.txtCapabilityStatus.text = status
        binding.txtCapabilityDescription.text = description
        binding.btnCapabilityClose.setOnClickListener { dismiss() }
        binding.capabilityDrawerRoot.setOnClickListener { dismiss() }
        binding.capabilityDrawerPanel.setOnClickListener { /* Keep panel clicks from dismissing the drawer. */ }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.53f)
        }
        setOnShowListener {
            coordinator?.setModalActive(true)
            binding.btnCapabilityClose.post { binding.btnCapabilityClose.requestRemoteFocus() }
        }
        setOnDismissListener {
            coordinator?.setModalActive(false)
            onDismissDrawer?.invoke()
        }
    }
}
