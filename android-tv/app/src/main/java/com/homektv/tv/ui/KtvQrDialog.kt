package com.homektv.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.homektv.tv.databinding.DialogKtvQrBinding

/**
 * 居中全屏半透明扫码点歌弹窗。
 */
class KtvQrDialog(
    context: Context,
    private val portalUrl: String,
    private val qrBitmap: Bitmap?,
    private val coordinator: KioskModeCoordinator?,
) : Dialog(context) {

    var onDismissQr: (() -> Unit)? = null

    private lateinit var binding: DialogKtvQrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
        }

        if (portalUrl.isNotBlank()) {
            binding.txtQrAddress.text = "局域网访问地址：$portalUrl"
        } else {
            binding.txtQrAddress.visibility = View.GONE
        }

        if (qrBitmap != null) {
            binding.imgQrDialog.setImageBitmap(qrBitmap)
            binding.qrDialogProgress.visibility = View.GONE
        } else {
            binding.qrDialogProgress.visibility = View.VISIBLE
        }

        binding.btnQrClose.setOnClickListener { dismiss() }
        binding.qrDialogRoot.setOnClickListener { dismiss() }
        binding.qrDialogPanel.setOnClickListener { /* 阻止冒泡 */ }

        binding.btnQrClose.requestFocus()

        setOnShowListener { coordinator?.setModalActive(true) }
        setOnDismissListener {
            coordinator?.setModalActive(false)
            onDismissQr?.invoke()
        }
    }

    fun updateQrBitmap(bitmap: Bitmap) {
        if (::binding.isInitialized) {
            binding.imgQrDialog.setImageBitmap(bitmap)
            binding.qrDialogProgress.visibility = View.GONE
        }
    }
}
