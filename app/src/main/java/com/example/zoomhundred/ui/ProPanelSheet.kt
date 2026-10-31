package com.example.zoomhundred.ui

import android.content.Context
import android.widget.ScrollView
import com.example.zoomhundred.camera.DeviceCapabilities
import com.example.zoomhundred.camera.FeatureGate
import com.example.zoomhundred.camera.LensCapabilities
import com.example.zoomhundred.model.AppSettings
import com.example.zoomhundred.model.FocusMode
import com.example.zoomhundred.model.ProSettings
import com.example.zoomhundred.model.WbMode
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * Independent Pro panel.
 *
 * Everything on this sheet edits [ProSettings] and is pushed straight into the live
 * session; sliders only force a re-bind of the still-capture use case on touch-up, so a
 * drag stays smooth while the saved photo still matches what the preview showed.
 */
class ProPanelSheet(
    private val host: Context,
    initial: ProSettings,
    private val settings: AppSettings,
    private val lens: LensCapabilities,
    private val capabilities: DeviceCapabilities,
    private val callbacks: Callbacks
) : BottomSheetDialog(host) {

    interface Callbacks {
        /** [commit] is true on touch-up, when the still capture request may be rebuilt. */
        fun onProChanged(updated: ProSettings, commit: Boolean)
        fun onMonitoringChanged(histogram: Boolean, peaking: Boolean, zebra: Boolean, level: Boolean)
    }

    private val pro: ProSettings = initial
    private val content = SheetUi.column(host)
    private val scroll = ScrollView(host).apply {
        isFillViewport = true
        addView(content)
    }

    private val exposureState = FeatureGate.manualExposure(lens)
    private val focusState = FeatureGate.manualFocus(lens)

    init {
        setContentView(scroll)
        rebuild()
    }

    private fun rebuild() {
        val scrollY = scroll.scrollY
        content.removeAllViews()

        content.addView(
            SheetUi.header(
                context = host,
                title = "Pro",
                subtitle = "${lens.label} · ${capabilities.sdkInt} · manual controls live on the sensor",
                actionLabel = "Done"
            ) { dismiss() }
        )

        addExposureSection()
        addFocusSection()
        addWhiteBalanceSection()
        addMonitoringSection()
        addFooter()

        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    // ── Exposure ──────────────────────────────────────────────────────────────

    private fun addExposureSection() {
        content.addView(SheetUi.section(host, "Exposure"))
        content.addView(
            SheetUi.switchRow(
                host,
                "Manual exposure",
                if (exposureState.usable) "Freeze ISO and shutter instead of letting AE decide"
                else "This sensor does not expose manual exposure controls",
                pro.manualMode,
                enabled = exposureState.usable
            ) { checked ->
                pro.manualMode = checked
                push(commit = true, rebuildAfter = true)
            }
        )

        val isoEnabled = pro.manualMode && exposureState.usable
        content.addView(
            SheetUi.sliderRow(
                context = host,
                label = "ISO",
                valueText = pro.isoValue().toString(),
                from = 0f,
                to = ProSettings.ISO_VALUES.lastIndex.toFloat(),
                step = 1f,
                initial = pro.isoIndex.toFloat(),
                enabled = isoEnabled,
                onValue = { value ->
                    pro.isoIndex = value.toInt().coerceIn(0, ProSettings.ISO_VALUES.lastIndex)
                    push(commit = false)
                },
                onCommit = { value ->
                    pro.isoIndex = value.toInt().coerceIn(0, ProSettings.ISO_VALUES.lastIndex)
                    push(commit = true, rebuildAfter = true)
                }
            )
        )
        content.addView(
            SheetUi.sliderRow(
                context = host,
                label = "Shutter",
                valueText = ProSettings.SHUTTER_LABELS[pro.shutterIndex.coerceIn(0, ProSettings.SHUTTER_LABELS.lastIndex)],
                from = 0f,
                to = ProSettings.SHUTTER_VALUES.lastIndex.toFloat(),
                step = 1f,
                initial = pro.shutterIndex.toFloat(),
                enabled = isoEnabled,
                onValue = { value ->
                    pro.shutterIndex = value.toInt().coerceIn(0, ProSettings.SHUTTER_VALUES.lastIndex)
                    push(commit = false)
                },
                onCommit = { value ->
                    pro.shutterIndex = value.toInt().coerceIn(0, ProSettings.SHUTTER_VALUES.lastIndex)
                    push(commit = true, rebuildAfter = true)
                }
            )
        )
        content.addView(
            SheetUi.sliderRow(
                context = host,
                label = "Exposure compensation",
                valueText = String.format(Locale.US, "%+.1f EV", pro.evValue()),
                from = 0f,
                to = ProSettings.EV_VALUES.lastIndex.toFloat(),
                step = 1f,
                initial = pro.evIndex.toFloat(),
                enabled = !pro.manualMode,
                onValue = { value ->
                    pro.evIndex = value.toInt().coerceIn(0, ProSettings.EV_VALUES.lastIndex)
                    push(commit = false)
                },
                onCommit = { value ->
                    pro.evIndex = value.toInt().coerceIn(0, ProSettings.EV_VALUES.lastIndex)
                    push(commit = false, rebuildAfter = true)
                }
            )
        )
        content.addView(
            SheetUi.switchRow(
                host,
                "AE lock",
                "Hold the exposure the meter just chose",
                pro.exposureLocked,
                enabled = !pro.manualMode
            ) { checked ->
                pro.exposureLocked = checked
                push(commit = true, rebuildAfter = true)
            }
        )
        content.addView(
            SheetUi.note(
                host,
                "Available range: ISO ${lens.isoRange?.lower ?: "—"}–${lens.isoRange?.upper ?: "—"} · " +
                    "shutter ${shutterRangeLabel()}"
            )
        )
    }

    private fun shutterRangeLabel(): String {
        val range = lens.exposureTimeRange ?: return "—"
        fun format(nanos: Long): String = when {
            nanos >= 1_000_000_000L -> "${nanos / 1_000_000_000.0}s"
            nanos >= 1_000_000L -> "1/${(1_000_000_000L / nanos.coerceAtLeast(1))}s"
            else -> "${nanos / 1000}µs"
        }
        return "${format(range.lower)}–${format(range.upper)}"
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    private fun addFocusSection() {
        content.addView(SheetUi.section(host, "Focus"))
        val modes = listOf(FocusMode.AUTO, FocusMode.MANUAL)
        content.addView(
            SheetUi.chipBlock(host, modes.map { focusMode ->
                val usable = focusMode == FocusMode.AUTO || focusState.usable
                SheetUi.ChipOption(
                    label = when (focusMode) {
                        FocusMode.AUTO -> "Autofocus"
                        FocusMode.MANUAL -> "Manual focus" + if (usable) "" else " · no"
                    },
                    selected = pro.focusMode == focusMode,
                    enabled = usable
                )
            }) { index ->
                pro.focusMode = modes[index]
                push(commit = true, rebuildAfter = true)
            }
        )
        content.addView(
            SheetUi.sliderRow(
                context = host,
                label = "Focus distance",
                valueText = if (pro.focusMode == FocusMode.MANUAL) {
                    "${(pro.focusPosition * 100).toInt()}% (0 = ∞)"
                } else "auto",
                from = 0f,
                to = 1f,
                step = 0.01f,
                initial = pro.focusPosition,
                enabled = pro.focusMode == FocusMode.MANUAL && focusState.usable,
                onValue = { value ->
                    pro.focusPosition = value
                    push(commit = false)
                },
                onCommit = { value ->
                    pro.focusPosition = value
                    push(commit = false, rebuildAfter = true)
                }
            )
        )
        content.addView(
            SheetUi.switchRow(
                host,
                "Focus lock",
                "Keep the current focus distance while reframing",
                pro.focusLocked,
                enabled = pro.focusMode == FocusMode.AUTO
            ) { checked ->
                pro.focusLocked = checked
                push(commit = true, rebuildAfter = true)
            }
        )
    }

    // ── White balance ─────────────────────────────────────────────────────────

    private fun addWhiteBalanceSection() {
        content.addView(SheetUi.section(host, "White balance"))
        val modes = WbMode.entries
        content.addView(
            SheetUi.chipBlock(host, modes.map { wbMode ->
                SheetUi.ChipOption(
                    label = when (wbMode) {
                        WbMode.AUTO -> "AWB"
                        WbMode.DAYLIGHT -> "Daylight"
                        WbMode.CLOUDY -> "Cloudy"
                        WbMode.SHADE -> "Shade"
                        WbMode.TUNGSTEN -> "Tungsten"
                        WbMode.FLUORESCENT -> "Fluorescent"
                        WbMode.CUSTOM -> "Kelvin"
                    },
                    selected = pro.wbMode == wbMode
                )
            }) { index ->
                pro.wbMode = modes[index]
                push(commit = true, rebuildAfter = true)
            }
        )
        content.addView(
            SheetUi.sliderRow(
                context = host,
                label = "Colour temperature",
                valueText = "${pro.wbKelvin}K",
                from = ProSettings.KELVIN_MIN.toFloat(),
                to = ProSettings.KELVIN_MAX.toFloat(),
                step = 100f,
                initial = pro.wbKelvin.toFloat(),
                enabled = pro.wbMode == WbMode.CUSTOM,
                onValue = { value ->
                    pro.wbKelvin = value.toInt()
                    push(commit = false)
                },
                onCommit = { value ->
                    pro.wbKelvin = value.toInt()
                    push(commit = false, rebuildAfter = true)
                }
            )
        )
        content.addView(
            SheetUi.switchRow(
                host,
                "WB lock",
                "Freeze the white balance for a sequence",
                pro.wbLocked
            ) { checked ->
                pro.wbLocked = checked
                push(commit = true, rebuildAfter = true)
            }
        )
    }

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun addMonitoringSection() {
        content.addView(SheetUi.section(host, "Monitoring"))
        content.addView(
            SheetUi.switchRow(host, "Histogram", null, settings.histogramEnabled) { checked ->
                settings.histogramEnabled = checked
                callbacks.onMonitoringChanged(
                    checked, settings.peakingEnabled, settings.zebraEnabled, settings.levelEnabled
                )
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Focus peaking", null, settings.peakingEnabled) { checked ->
                settings.peakingEnabled = checked
                callbacks.onMonitoringChanged(
                    settings.histogramEnabled, checked, settings.zebraEnabled, settings.levelEnabled
                )
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Zebra", null, settings.zebraEnabled) { checked ->
                settings.zebraEnabled = checked
                callbacks.onMonitoringChanged(
                    settings.histogramEnabled, settings.peakingEnabled, checked, settings.levelEnabled
                )
            }
        )
        content.addView(
            SheetUi.switchRow(host, "Spirit level", null, settings.levelEnabled) { checked ->
                settings.levelEnabled = checked
                callbacks.onMonitoringChanged(
                    settings.histogramEnabled, settings.peakingEnabled, settings.zebraEnabled, checked
                )
            }
        )
    }

    private fun addFooter() {
        content.addView(SheetUi.divider(host))
        content.addView(
            SheetUi.buttonStrip(
                host,
                listOf(
                    "Reset manual" to {
                        val defaults = ProSettings()
                        pro.manualMode = defaults.manualMode
                        pro.isoIndex = defaults.isoIndex
                        pro.shutterIndex = defaults.shutterIndex
                        pro.evIndex = defaults.evIndex
                        pro.exposureLocked = defaults.exposureLocked
                        pro.focusMode = defaults.focusMode
                        pro.focusPosition = defaults.focusPosition
                        pro.focusLocked = defaults.focusLocked
                        pro.wbMode = defaults.wbMode
                        pro.wbKelvin = defaults.wbKelvin
                        pro.wbLocked = defaults.wbLocked
                        push(commit = true, rebuildAfter = true)
                    },
                    "Close" to { dismiss() }
                ),
                primaryFirst = false
            )
        )
        content.addView(
            SheetUi.note(
                host,
                "Manual settings travel into the still capture request, so the saved file " +
                    "matches the preview. Unsupported controls are disabled, never faked."
            )
        )
    }

    private fun push(commit: Boolean, rebuildAfter: Boolean = false) {
        callbacks.onProChanged(pro.copyOf(), commit)
        if (rebuildAfter) rebuild()
    }
}
