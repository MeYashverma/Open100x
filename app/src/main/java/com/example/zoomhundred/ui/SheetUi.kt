package com.example.zoomhundred.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.zoomhundred.R
import com.google.android.material.slider.Slider
import java.util.Locale

/**
 * Small programmatic-UI toolkit shared by the tool sheet, the Pro panel and the settings
 * screen. Everything is built from the same type scale, the same chip shape and the same
 * 44 dp minimum touch target so the surfaces added later cannot drift away from the
 * viewfinder chrome.
 */
object SheetUi {

    data class ChipOption(
        val label: String,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val hint: String? = null
    )

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun column(context: Context, horizontalPaddingDp: Float = 18f, topPaddingDp: Float = 8f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, horizontalPaddingDp), dp(context, topPaddingDp), dp(context, horizontalPaddingDp), dp(context, 26f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    fun header(context: Context, title: String, subtitle: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(context, title, 18f, R.color.text_primary, serif = true))
        if (subtitle != null) {
            texts.addView(caption(context, subtitle).apply { setPadding(0, dp(context, 2f), 0, 0) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && onAction != null) {
            row.addView(pill(context, actionLabel, primary = false, onAction))
        }
        return row
    }

    fun section(context: Context, title: String): TextView {
        val view = TextView(context).apply {
            text = title.uppercase(Locale.US)
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            textSize = 10.5f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(context, 20f), 0, dp(context, 8f))
        }
        return view
    }

    fun label(context: Context, text: String, size: Float = 14f, colorRes: Int = R.color.text_primary, serif: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, colorRes))
            textSize = size
            letterSpacing = if (serif) 0.02f else 0.01f
            typeface = if (serif) Typeface.create("sans-serif-light", Typeface.NORMAL)
            else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    fun caption(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 11.5f
            letterSpacing = 0.01f
            setLineSpacing(dp(context, 2f).toFloat(), 1f)
        }

    fun note(context: Context, text: String): TextView =
        caption(context, text).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setPadding(0, dp(context, 6f), 0, dp(context, 2f))
        }

    /** A tappable row: label on the left, current value on the right. */
    fun actionRow(
        context: Context,
        label: String,
        value: String,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 10f), 0, dp(context, 10f))
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 44f)
            setOnClickListener { onClick() }
        }
        row.addView(SheetUi.label(context, label, 14.5f, R.color.text_primary), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(SheetUi.label(context, value, 13f, R.color.accent))
        return row
    }

    fun switchRow(
        context: Context,
        label: String,
        description: String?,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8f), 0, dp(context, 8f))
            minimumHeight = dp(context, 44f)
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(SheetUi.label(context, label, 14.5f, if (enabled) R.color.text_primary else R.color.text_tertiary))
        if (description != null) texts.addView(caption(context, description))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = SwitchCompat(context).apply {
            isChecked = checked
            this.isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(context, 48f), dp(context, 32f)))
        return row
    }

    fun chipBlock(
        context: Context,
        options: List<ChipOption>,
        wrap: Boolean = true,
        onSelect: (Int) -> Unit
    ): View {
        val block = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        var usedWidth = 0
        val maxWidth = context.resources.displayMetrics.widthPixels - dp(context, 36f)
        options.forEachIndexed { index, option ->
            val chip = chip(context, option, index, onSelect)
            if (!wrap) {
                if (line == null) {
                    line = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(context, 4f), 0, dp(context, 4f))
                    }
                    block.addView(line)
                }
                line!!.addView(chip)
                return@forEachIndexed
            }
            val chipWidth = dp(context, 16f) + option.label.length * dp(context, 7.5f)
            if (line == null || usedWidth + chipWidth > maxWidth) {
                line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(context, 4f), 0, dp(context, 4f))
                }
                block.addView(line)
                usedWidth = 0
            }
            line!!.addView(chip)
            usedWidth += chipWidth + dp(context, 6f)
        }
        return block
    }

    private fun chip(context: Context, option: ChipOption, index: Int, onSelect: (Int) -> Unit): TextView =
        TextView(context).apply {
            text = option.label
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
            isSelected = option.selected
            isEnabled = option.enabled
            alpha = if (option.enabled) 1f else 0.35f
            setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        !option.enabled -> R.color.text_tertiary
                        option.selected -> R.color.text_on_accent
                        else -> R.color.text_secondary
                    }
                )
            )
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip_selector)
            minimumHeight = dp(context, 34f)
            minWidth = dp(context, 34f)
            isClickable = true
            isFocusable = true
            if (option.hint != null) contentDescription = option.hint
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 6f) }
            setOnClickListener { if (option.enabled) onSelect(index) }
        }

    fun pill(context: Context, text: String, primary: Boolean = true, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12.5f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(dp(context, 16f), dp(context, 9f), dp(context, 16f), dp(context, 9f))
            setTextColor(
                ContextCompat.getColor(context, if (primary) R.color.text_on_accent else R.color.text_primary)
            )
            background = ContextCompat.getDrawable(
                context,
                if (primary) R.drawable.bg_pill_accent else R.drawable.bg_pill
            )
            minimumHeight = dp(context, 38f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 8f) }
        }

    fun buttonStrip(context: Context, actions: List<Pair<String, () -> Unit>>, primaryFirst: Boolean = true): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 10f), 0, 0)
            actions.forEachIndexed { index, (text, action) ->
                addView(pill(context, text, primary = primaryFirst && index == 0, action))
            }
        }

    /** Slider with a live value readout; [onCommit] fires on touch-up so heavy work can wait. */
    fun sliderRow(
        context: Context,
        label: String,
        valueText: String,
        from: Float,
        to: Float,
        step: Float,
        initial: Float,
        enabled: Boolean = true,
        onValue: (Float) -> Unit,
        onCommit: ((Float) -> Unit)? = null
    ): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8f), 0, 0)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(context, label, 13f, if (enabled) R.color.text_secondary else R.color.text_tertiary), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val readout = TextView(context).apply {
            text = valueText
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 13f
            letterSpacing = 0.04f
        }
        head.addView(readout)
        holder.addView(head)
        val slider = Slider(context).apply {
            valueFrom = from
            valueTo = if (to <= from) from + 1f else to
            this.stepSize = if (step <= 0f) 0f else step
            value = initial.coerceIn(this.valueFrom, this.valueTo)
            this.isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) onValue(value)
            }
            if (onCommit != null) {
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = onCommit(slider.value)
                })
            }
        }
        holder.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 44f)))
        return holder
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.hairline))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = dp(context, 10f) }
    }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }
}
