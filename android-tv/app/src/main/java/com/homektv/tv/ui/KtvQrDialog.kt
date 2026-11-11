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
import android.view.WindowManager
import com.homektv.tv.R
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
    var onRetryQr: (() -> Unit)? = null

    private lateinit var binding: DialogKtvQrBinding
    private var retryableFailure = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogKtvQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(170f / 255f)
        }

        if (portalUrl.isNotBlank()) {
            binding.txtQrAddress.text = context.getString(R.string.qr_lan_address, portalUrl)
        } else {
            binding.txtQrAddress.visibility = View.GONE
        }

        if (qrBitmap != null) {
            binding.imgQrDialog.setImageBitmap(qrBitmap)
            binding.qrDialogProgress.visibility = View.GONE
        } else {
            binding.qrDialogProgress.visibility = View.VISIBLE
        }

        binding.btnQrClose.setOnClickListener {
            if (retryableFailure) onRetryQr?.invoke() ?: dismiss() else dismiss()
        }
        binding.qrDialogRoot.setOnClickListener { dismiss() }
        binding.qrDialogPanel.setOnClickListener { /* 阻止冒泡 */ }

        setOnShowListener {
            coordinator?.setModalActive(true)
            binding.btnQrClose.post { binding.btnQrClose.requestRemoteFocus() }
        }
        setOnDismissListener {
            coordinator?.setModalActive(false)
            onDismissQr?.invoke()
        }
    }

    fun updateQrBitmap(bitmap: Bitmap) {
        if (::binding.isInitialized) {
            binding.imgQrDialog.setImageBitmap(bitmap)
            binding.qrDialogProgress.visibility = View.GONE
            binding.txtQrState.visibility = View.GONE
            binding.btnQrClose.text = context.getString(R.string.common_close)
            retryableFailure = false
        }
    }

    fun showLoadError(message: CharSequence, retryable: Boolean) {
        if (!::binding.isInitialized) return
        binding.qrDialogProgress.visibility = View.GONE
        binding.txtQrState.text = message
        binding.txtQrState.visibility = View.VISIBLE
        binding.btnQrClose.text = if (retryable) "重试" else context.getString(R.string.common_close)
        retryableFailure = retryable
    }

    fun showLoading() {
        if (!::binding.isInitialized) return
        binding.qrDialogProgress.visibility = View.VISIBLE
        binding.txtQrState.visibility = View.GONE
        binding.btnQrClose.text = context.getString(R.string.common_close)
        retryableFailure = false
    }
}
