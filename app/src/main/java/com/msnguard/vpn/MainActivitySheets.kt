package com.msnguard.vpn

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import com.msnguard.vpn.MainActivity.EndpointDiscovery
import com.msnguard.vpn.MainActivity.ObfuscationProfile
import com.msnguard.vpn.MainActivity.TlsCurvePreset
import com.msnguard.vpn.MainActivity.PerfProfile
import com.msnguard.vpn.MainActivity.H2Fragmentation
import com.msnguard.vpn.MainActivity.SelectionOption
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.ENDPOINT_DISCOVERY
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_PROFILE
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JC
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JMIN
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_JMAX
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_I1
import com.msnguard.vpn.MainActivity.Companion.OBFUSCATION_I2
import com.msnguard.vpn.MainActivity.Companion.MANUAL_ENDPOINT
import com.msnguard.vpn.MainActivity.Companion.TLS_CURVE_PRESET
import com.msnguard.vpn.MainActivity.Companion.PERF_PROFILE
import com.msnguard.vpn.MainActivity.Companion.H2_FRAGMENTATION

/**
 * Bottom sheets and their shared building blocks: single-choice sheets, text
 * entry sheets, and the selected/unselected styling of an option row.
 */

internal fun MainActivity.chooseObfuscation(after: (() -> Unit)? = null) {
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = roundedBackground(SURFACE, 28, SURFACE)
    }
    sheet.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Obfuscation", 22f, INK, TypefaceStyle.MEDIUM))
    })
    sheet.addView(label("Adjust traffic-shape padding for filtered networks", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-4); bottomMargin = dp(20) })
    val options = mutableMapOf<ObfuscationProfile, SelectionOption>()
    ObfuscationProfile.entries.forEachIndexed { index, profile ->
        val title = label(profile.label, 16f, INK, TypefaceStyle.MEDIUM)
        val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(title)
            labels.addView(label(profile.description, 13f, MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(indicator)
            setOnClickListener {
                preferences().edit().putString(OBFUSCATION_PROFILE, profile.coreName).apply()
                options.forEach { (item, option) -> setSelectionState(option, item == profile, animate = true) }
                after?.invoke()
            }
        }
        val option = SelectionOption(row, title, indicator, 18)
        options[profile] = option
        setSelectionState(option, profile == obfuscationProfile(), animate = false)
        sheet.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72),
        ).apply { topMargin = if (index == 0) 0 else dp(8) })
    }
    dialog.setContentView(FrameLayout(this).apply {
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(sheet)
    })
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.62f)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}

internal fun MainActivity.chooseTlsCurvePreset(after: (() -> Unit)? = null) = showChoiceSheet(
    title = "TLS fingerprint",
    subtitle = "Choose TLS curve ordering for QUIC connections",
    options = TlsCurvePreset.entries.toList(),
    selected = tlsCurvePreset(),
    labelOf = { it.label },
    description = { it.description },
) { chosen ->
    preferences().edit().putString(TLS_CURVE_PRESET, chosen.coreName).apply()
    after?.invoke()
}

internal fun MainActivity.choosePerfProfile(after: (() -> Unit)? = null) = showChoiceSheet(
    title = "Performance",
    subtitle = "Scale scan concurrency and buffers to match your hardware",
    options = PerfProfile.entries.toList(),
    selected = perfProfile(),
    labelOf = { it.label },
    description = { it.description },
) { chosen ->
    preferences().edit().putString(PERF_PROFILE, chosen.coreName).apply()
    after?.invoke()
}

internal fun MainActivity.chooseH2Fragmentation(after: (() -> Unit)? = null) = showChoiceSheet(
    title = "TLS fragmentation",
    subtitle = "Fragment the TLS ClientHello to look like ordinary HTTPS traffic",
    options = H2Fragmentation.entries.toList(),
    selected = h2Fragmentation(),
    labelOf = { it.label },
    description = { it.description },
) { chosen ->
    preferences().edit().putString(H2_FRAGMENTATION, chosen.coreName).apply()
    after?.invoke()
}

internal fun MainActivity.manageGatewayCache() = showChoiceSheet(
    title = "Gateway cache",
    subtitle = "Control saved MASQUE gateway discovery data",
    options = listOf("Cache & refresh", "Fresh scan next time", "Clear saved gateways"),
    selected = if (defaultEndpointDiscovery() == EndpointDiscovery.CACHE) "Cache & refresh" else "Fresh scan next time",
    labelOf = { it },
    description = {
        when (it) {
            "Cache & refresh" -> "Try saved gateways first"
            "Fresh scan next time" -> "Ignore saved gateways once"
            else -> "Remove saved gateway latency data"
        }
    },
    onSelected = { chosen ->
        when (chosen) {
            "Cache & refresh" -> preferences().edit().putString(ENDPOINT_DISCOVERY, EndpointDiscovery.CACHE.coreName).apply()
            "Fresh scan next time" -> preferences().edit().putString(ENDPOINT_DISCOVERY, EndpointDiscovery.FRESH.coreName).apply()
            else -> File(filesDir, "masque-gateway-cache.json").delete()
        }
    }
)

internal fun MainActivity.editManualEndpoint() {
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val field = EditText(this).apply {
        setText(manualEndpoint().orEmpty())
        hint = "IP:port, blank for automatic"
        setTextColor(INK)
        setHintTextColor(MUTED)
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(18), 0, dp(18), 0)
        background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
    }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = roundedBackground(SURFACE, 28, SURFACE)
    }
    sheet.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label("Manual endpoint", 22f, INK, TypefaceStyle.MEDIUM))
    })
    sheet.addView(label("Numeric IPv4 or bracketed IPv6 address with port. Bypasses discovery.", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-4); bottomMargin = dp(20) })
    sheet.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
    val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    buttons.addView(createSettingsButton("Clear") {
        preferences().edit().remove(MANUAL_ENDPOINT).apply()
        field.setText("")
    }, LinearLayout.LayoutParams(0, dp(52), 1f))
    buttons.addView(createSettingsButton("Save") {
        val endpoint = field.text.toString().trim()
        val validEndpoint = endpoint.isBlank() || Regex("^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+]):([1-9]\\d{0,4})$")
            .matchEntire(endpoint)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 1..65535 } == true
        if (!validEndpoint) {
            field.error = "Use numeric IP:port"
            return@createSettingsButton
        }
        preferences().edit().apply {
            if (endpoint.isBlank()) remove(MANUAL_ENDPOINT) else putString(MANUAL_ENDPOINT, endpoint)
        }.apply()
        dialog.dismiss()
    }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(10) })
    sheet.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
    dialog.setContentView(FrameLayout(this).apply {
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(sheet)
    })
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.62f)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}

internal fun MainActivity.settingsField(
    value: String,
    hintText: String,
    secure: Boolean = false,
    multiline: Boolean = false,
) = EditText(this).apply {
    setText(value)
    hint = hintText
    setTextColor(INK)
    setHintTextColor(MUTED)
    textSize = 15f
    inputType = when {
        secure -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else -> InputType.TYPE_CLASS_TEXT
    }
    setSingleLine(!multiline)
    gravity = if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL
    setPadding(dp(18), if (multiline) dp(14) else 0, dp(18), if (multiline) dp(14) else 0)
    background = roundedBackground(SURFACE_VARIANT, 16, SURFACE_VARIANT)
}

internal fun MainActivity.showTextSettingsSheet(
    title: String,
    subtitle: String,
    fields: List<Pair<String, EditText>>,
    validator: ((List<String>) -> Pair<Int, String>?)? = null,
    onSave: (List<String>) -> Unit,
) {
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = roundedBackground(SURFACE, 28, SURFACE)
    }
    sheet.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(title, 22f, INK, TypefaceStyle.MEDIUM))
    })
    sheet.addView(label(subtitle, 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-4); bottomMargin = dp(16) })
    fields.forEach { (name, field) ->
        sheet.addView(label(name, 11f, MUTED).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10); bottomMargin = dp(6) })
        sheet.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (field.maxLines > 1) dp(104) else dp(56),
        ))
    }
    sheet.addView(createSettingsButton("Save", backgroundOverride = primary, textColorOverride = primaryContainer) {
        val values = fields.map { it.second.text.toString().trim() }
        validator?.invoke(values)?.let { (index, message) ->
            fields[index].second.error = message
            return@createSettingsButton
        }
        onSave(values)
        dialog.dismiss()
        closeTunnelControlsScreen(false)
        openTunnelControlsScreen()
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
    dialog.setContentView(ScrollView(this).apply {
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(sheet)
    })
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.62f)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}

internal fun MainActivity.editAdvancedObfuscation() {
    val jc = settingsField(preferences().getString(OBFUSCATION_JC, "").orEmpty(), "0–10").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    val jmin = settingsField(preferences().getString(OBFUSCATION_JMIN, "").orEmpty(), "0–1024 bytes").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    val jmax = settingsField(preferences().getString(OBFUSCATION_JMAX, "").orEmpty(), "0–1024 bytes").apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }
    val i1 = settingsField(preferences().getString(OBFUSCATION_I1, "").orEmpty(), "<r 64>")
    val i2 = settingsField(preferences().getString(OBFUSCATION_I2, "").orEmpty(), "<r 32>")
    showTextSettingsSheet(
        "Advanced obfuscation",
        "WireGuard only. Jc/Jmin/Jmax tune junk packets; I1/I2 use Aether CPS packet patterns.",
        listOf("JUNK COUNT (JC)" to jc, "JUNK MIN (JMIN)" to jmin, "JUNK MAX (JMAX)" to jmax, "INIT PACKET I1" to i1, "INIT PACKET I2" to i2),
        validator = { values ->
            val numbers = values.take(3).map { it.toIntOrNull() }
            when {
                values.take(3).withIndex().any { (index, value) -> value.isNotBlank() && numbers[index] == null } -> 0 to "Use whole numbers"
                numbers[0]?.let { it !in 0..10 } == true -> 0 to "Jc must be 0–10"
                numbers[1]?.let { it !in 0..1024 } == true -> 1 to "Jmin must be 0–1024"
                numbers[2]?.let { it !in 0..1024 } == true -> 2 to "Jmax must be 0–1024"
                numbers[1] != null && numbers[2] != null && numbers[2]!! < numbers[1]!! -> 2 to "Jmax must be at least Jmin"
                values.drop(3).any { it.length > 2048 } -> 3 to "Packet pattern is too long"
                else -> null
            }
        },
    ) { values ->
        preferences().edit().apply {
            listOf(OBFUSCATION_JC, OBFUSCATION_JMIN, OBFUSCATION_JMAX, OBFUSCATION_I1, OBFUSCATION_I2)
                .zip(values)
                .forEach { (key, value) -> if (value.isBlank()) remove(key) else putString(key, value) }
        }.apply()
    }
}

internal fun <T> MainActivity.showChoiceSheet(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    description: (T) -> String,
    onSelected: (T) -> Unit,
) {
    // `after` is invoked by the caller's onSelected lambda; see chooseObfuscation
    // and friends, which pass a refresh for the row that opened the sheet.
    val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = roundedBackground(SURFACE, 28, SURFACE)
    }
    sheet.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(title, 22f, INK, TypefaceStyle.MEDIUM))
    })
    sheet.addView(label(subtitle, 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-4); bottomMargin = dp(20) })
    val rows = mutableMapOf<T, SelectionOption>()
    options.forEachIndexed { index, item ->
        val optionTitle = label(labelOf(item), 16f, INK, TypefaceStyle.MEDIUM)
        val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            val labels = LinearLayout(self).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(optionTitle)
            labels.addView(label(description(item), 13f, MUTED), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(indicator)
            setOnClickListener {
                onSelected(item)
                rows.forEach { (value, option) -> setSelectionState(option, value == item, animate = true) }
            }
        }
        val option = SelectionOption(row, optionTitle, indicator, 18)
        rows[item] = option
        setSelectionState(option, item == selected, animate = false)
        sheet.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
            topMargin = if (index == 0) 0 else dp(8)
        })
    }
    dialog.setContentView(FrameLayout(this).apply {
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(sheet)
    })
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setDimAmount(0.62f)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}

internal fun MainActivity.setSelectionState(option: SelectionOption, selected: Boolean, animate: Boolean) {
    option.row.background = roundedBackground(
        if (selected) primaryContainer else SURFACE_VARIANT,
        option.radius,
        if (selected) primary else SURFACE_VARIANT,
    )
    option.title.typeface = android.graphics.Typeface.create(
        if (selected) "sans-serif-medium" else "sans",
        android.graphics.Typeface.NORMAL,
    )
    option.indicator.animate().cancel()
    if (selected) {
        option.indicator.visibility = View.VISIBLE
        option.indicator.alpha = if (animate) 0f else 1f
        if (animate) option.indicator.animate().alpha(1f).setDuration(160).start()
    } else if (animate) {
        option.indicator.animate().alpha(0f).setDuration(120).withEndAction {
            option.indicator.visibility = View.INVISIBLE
        }.start()
    } else {
        option.indicator.alpha = 0f
        option.indicator.visibility = View.INVISIBLE
    }
}
