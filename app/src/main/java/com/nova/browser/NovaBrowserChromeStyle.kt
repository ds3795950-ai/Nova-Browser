package com.nova.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.nova.browser.engine.BrowserEngineType

/** Shared Nova browser-chrome measurements. These intentionally do not vary by page engine. */
internal data class NovaBrowserChromeMetrics(
    val addressHeightDp: Int = 48,
    val addressTextSizeSp: Float = 15f,
    val controlMinSizeDp: Int = 48,
    val toolbarMinHeightDp: Int = 48,
    val addressCornerRadiusDp: Int = 12,
    val statusTextSizeSp: Float = 11f,
    val tabLabelTextSizeSp: Float = 12f
)

/**
 * The browser chrome belongs to Nova, not to Cefrium or GeckoView. Both engines are mounted
 * below the same host chrome and use the same metrics, palette and blank-page surface.
 */
internal object NovaBrowserChromeStyle {
    val sharedMetrics = NovaBrowserChromeMetrics()

    /** Kept explicit so future engine adapters cannot accidentally introduce a second chrome spec. */
    fun metricsFor(@Suppress("UNUSED_PARAMETER") engine: BrowserEngineType): NovaBrowserChromeMetrics = sharedMetrics

    fun applyAddressField(context: Context, field: EditText) {
        val metrics = sharedMetrics
        field.isSingleLine = true
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.imeOptions = EditorInfo.IME_ACTION_GO
        field.setSelectAllOnFocus(true)
        field.textSize = metrics.addressTextSizeSp
        field.setTextColor(context.getColor(R.color.nova_text_primary))
        field.setHintTextColor(context.getColor(R.color.nova_text_secondary))
        field.minimumHeight = dp(context, metrics.addressHeightDp)
        field.setPadding(dp(context, 12), 0, dp(context, 12), 0)
        field.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, metrics.addressCornerRadiusDp).toFloat()
            setColor(context.getColor(R.color.nova_surface_elevated))
            setStroke(dp(context, 1), context.getColor(R.color.nova_outline))
        }
    }

    fun applyChromeSurface(context: Context, view: View, minimumHeight: Boolean = false) {
        view.setBackgroundColor(context.getColor(R.color.nova_surface))
        if (minimumHeight) view.minimumHeight = dp(context, sharedMetrics.toolbarMinHeightDp)
    }

    /** Gives both engine surfaces the same Nova-owned background before/after navigation. */
    fun applyEngineSurface(context: Context, view: View) {
        view.setBackgroundColor(context.getColor(R.color.nova_background))
    }

    fun applyMetaLabel(context: Context, view: TextView, textSizeSp: Float, topPaddingDp: Int, bottomPaddingDp: Int) {
        view.textSize = textSizeSp
        view.setTextColor(context.getColor(R.color.nova_text_secondary))
        view.setPadding(dp(context, 4), dp(context, topPaddingDp), dp(context, 4), dp(context, bottomPaddingDp))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
