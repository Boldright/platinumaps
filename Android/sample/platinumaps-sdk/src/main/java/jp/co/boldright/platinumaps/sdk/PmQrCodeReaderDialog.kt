package jp.co.boldright.platinumaps.sdk

import android.app.Dialog
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Layout
import android.util.Log
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.google.zxing.ResultPoint
import jp.co.boldright.platinumaps.sdk.databinding.LayoutPmQrCodeReaderDialogBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class PmQrCodeReaderDialog : DialogFragment() {
    companion object {
        private const val MESSAGE_KEY = "MessageKey"
        private const val REQUEST_CANCEL_BUTTON_KEY = "RequestCancelButtonKey"
        private const val CANCEL_BUTTON_TEXT_KEY = "CancelButtonTextKey"
        private const val READ_QR_CODE_KEY = "ReadQrCodeKey"
    }

    private var qrCodePreviewView: PmQrCodePreviewView? = null

    private val TAG = "jp.platinumaps.qrcode"

    class Builder(private val fragment: Fragment) {
        private val bundle = Bundle()

        fun setMessage(message: String): Builder {
            return this.apply {
                bundle.putString(MESSAGE_KEY, message)
            }
        }

        fun setCancelButton(buttonText: String?, listener: (() -> Unit)? = null): Builder {
            fragment.childFragmentManager
                .setFragmentResultListener(
                    REQUEST_CANCEL_BUTTON_KEY,
                    fragment.viewLifecycleOwner
                ) { _, _ ->
                    listener?.invoke()
                }
            return this.apply {
                buttonText?.let {
                    bundle.putString(CANCEL_BUTTON_TEXT_KEY, it)
                }
            }
        }

        fun setReadQrCodeListener(listener: ((result: String?) -> Unit)?): Builder {
            fragment.childFragmentManager
                .setFragmentResultListener(
                    READ_QR_CODE_KEY,
                    fragment.viewLifecycleOwner
                ) { _, bundle ->
                    val result = bundle.getString(READ_QR_CODE_KEY)
                    listener?.invoke(result)
                }
            return this
        }

        fun build(): PmQrCodeReaderDialog {
            return PmQrCodeReaderDialog().apply {
                arguments = bundle
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var message: String? = null
        var cancelButtonText: String? = null
        arguments?.let {
            message = it.getString(MESSAGE_KEY)
            cancelButtonText = it.getString(CANCEL_BUTTON_TEXT_KEY)
        }

        val binding = LayoutPmQrCodeReaderDialogBinding.inflate(requireActivity().layoutInflater)
        message?.let {
            if (it.isNotEmpty()) {
                binding.qrCodeReaderMessage.text = it
            }
        }
        cancelButtonText?.let {
            if (it.isNotEmpty()) {
                binding.qrCodeReaderCancelButton.text = it
            }
        }
        binding.qrCodeReaderCancelButton.setOnClickListener {
            dismiss()
            setFragmentResult(
                REQUEST_CANCEL_BUTTON_KEY,
                bundleOf()
            )
        }
        qrCodePreviewView = binding.qrCodePreview
        dialog.setContentView(binding.root)
        return dialog
    }

    override fun onResume() {
        super.onResume()
        startCapture()
    }

    override fun onDestroyView() {
        stopCapture()
        qrCodePreviewView = null
        super.onDestroyView()
    }

    override fun onPause() {
        stopCapture()
        super.onPause()
    }

    private fun startCapture() {
        qrCodePreviewView?.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (result == null) {
                    // no result
                    Log.w(TAG, "No result")
                    return
                }
                Log.i(TAG, "QRCode Result: ${result.text}")
                stopCapture()

                dismiss()
                setFragmentResult(READ_QR_CODE_KEY, bundleOf(READ_QR_CODE_KEY to result.text))
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
        qrCodePreviewView?.resume()
    }

    private fun stopCapture() {
        qrCodePreviewView?.pause()
    }
}
