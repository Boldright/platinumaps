package jp.co.boldright.platinumaps.sdk

import android.content.Context
import android.util.AttributeSet
import com.journeyapps.barcodescanner.BarcodeView

class PmQrCodePreviewView : BarcodeView {

    private var mAspectRatio = 0.0F

    constructor(context: Context) : super(context) {
        init(null, 0)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init(attrs, defStyle)
    }

    private fun init(attrs: AttributeSet?, defStyle: Int) {
        // Load attributes
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.PmQrCodePreviewView, defStyle, 0
        )

        try {
            mAspectRatio = a.getFloat(R.styleable.PmQrCodePreviewView_aspectRatio, 1F);
        } finally {
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = (widthSize * mAspectRatio).toInt()
        setMeasuredDimension(widthSize, heightSize)

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(widthSize, widthMode)
        val heightSpec = MeasureSpec.makeMeasureSpec(heightSize, heightMode)

        super.onMeasure(widthSpec, heightSpec)
    }
}
