package com.msnguard.vpn

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.msnguard.vpn.MainActivity.SelectionOption
import com.msnguard.vpn.MainActivity.TypefaceStyle
import com.msnguard.vpn.MainActivity.Companion.PAGE_ANIMATION_MS

/**
 * Split tunneling: the mode page, the searchable app picker, and the draft state
 * that survives until the user taps Done.
 */

internal fun MainActivity.openSplitTunnelScreen() {
    splitTunnelPage?.let(pageHost::removeView)
    val settings = SplitTunnelSettings(this)
    val selected = settings.packages().toMutableSet()
    splitTunnelDraftMode = settings.mode()
    splitTunnelDraftPackages = selected
    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val header = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { closeSplitTunnelScreen() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        addView(label("Split tunneling", 22f, INK, TypefaceStyle.MEDIUM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(createSettingsButton("Done", backgroundOverride = primary, textColorOverride = primaryContainer) {
            settings.save(settings.mode(), selected)
            closeSplitTunnelScreen()
        }, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(4) })
    }
    content.addView(header)
    content.addView(label("Choose which apps use MSN-GUARD. Changes apply next connection.", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-8); bottomMargin = dp(20) })
    content.addView(label("MODE", 12f, MUTED).apply { letterSpacing = 0.1f })
    val modeOptions = mutableMapOf<SplitTunnelSettings.Mode, SelectionOption>()
    SplitTunnelSettings.Mode.entries.forEachIndexed { index, mode ->
        val option = createSplitModeOption(mode, settings.mode()) { chosen ->
            modeOptions.forEach { (m, opt) -> setSelectionState(opt, m == chosen, animate = true) }
            splitTunnelDraftMode = chosen
            splitTunnelDraftPackages = selected
            settings.save(chosen, selected.toHashSet())
            if (chosen == SplitTunnelSettings.Mode.ALL) {
                closeSplitTunnelScreen()
            } else {
                openSplitTunnelAppsScreen(chosen, selected)
            }
        }
        modeOptions[mode] = option
        content.addView(option.row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58),
        ).apply { topMargin = if (index == 0) dp(10) else dp(8) })
    }

    page.addView(content, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply {
        leftMargin = dp(24)
        rightMargin = dp(24)
        topMargin = dp(16)
        bottomMargin = dp(16)
    })
    page.setOnApplyWindowInsetsListener { _, insets ->
        (content.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insets.systemWindowInsetTop + dp(16)
            bottomMargin = insets.systemWindowInsetBottom + dp(16)
            content.layoutParams = this
        }
        insets
    }
    splitTunnelPage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    animatePageOpen(page)
    staggerListItems(content)
}

internal fun MainActivity.openSplitTunnelAppsScreen(mode: SplitTunnelSettings.Mode, selected: MutableSet<String>) {
    splitTunnelAppsPage?.let(pageHost::removeView)
    splitTunnelDraftMode = mode
    splitTunnelDraftPackages = selected
    val settings = SplitTunnelSettings(this)
    val page = FrameLayout(this).apply {
        setBackgroundColor(CANVAS)
        isClickable = true
    }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    content.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(createHeaderBackButton { closeSplitTunnelAppsScreen() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        addView(label("Apps", 22f, INK, TypefaceStyle.MEDIUM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(createSettingsButton("Done", backgroundOverride = primary, textColorOverride = primaryContainer) {
            settings.save(mode, selected.toHashSet())
            closeSplitTunnelAppsScreen()
            closeSplitTunnelScreen()
        }, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(4) })
    })
    content.addView(label("Select apps to ${if (mode == SplitTunnelSettings.Mode.INCLUDE) "include" else "exclude"}", 14f, MUTED), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = dp(48); topMargin = dp(-8); bottomMargin = dp(16) })

    val searchField = EditText(this).apply {
        hint = "Search apps…"
        setHintTextColor(MUTED)
        setTextColor(INK)
        textSize = 15f
        setSingleLine(true)
        background = roundedBackground(SURFACE_VARIANT, 12, DIVIDER)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val searchIcon = getDrawable(android.R.drawable.ic_menu_search)?.apply {
            setTint(MUTED)
            setBounds(0, 0, dp(20), dp(20))
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon, null, null, null)
        compoundDrawablePadding = dp(10)
    }
    content.addView(searchField, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(16); leftMargin = dp(4); rightMargin = dp(4) })

    val progressBar = ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateDrawable?.setTint(primary)
    }
    val loading = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(progressBar, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(label("Scanning installed apps…", 14f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
    }
    val listScroll = ScrollView(this).apply {
        alpha = 0f
        visibility = View.INVISIBLE
        addView(appList)
    }
    content.addView(FrameLayout(this).apply {
        addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        addView(listScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
    ))
    page.addView(content, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply {
        leftMargin = dp(24)
        rightMargin = dp(24)
        topMargin = dp(16)
        bottomMargin = dp(16)
    })
    page.setOnApplyWindowInsetsListener { _, insets ->
        (content.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insets.systemWindowInsetTop + dp(16)
            bottomMargin = insets.systemWindowInsetBottom + dp(16)
            content.layoutParams = this
        }
        insets
    }
    splitTunnelAppsPage = page
    pageHost.addView(page)
    page.requestApplyInsets()
    page.alpha = 0f
    page.translationX = dp(20).toFloat()
    page.animate().alpha(1f).translationX(0f)
        .setDuration(PAGE_ANIMATION_MS)
        .setInterpolator(motionInterpolator)
        .start()
    loadUserApps { apps ->
        if (splitTunnelAppsPage !== page) return@loadUserApps

        settings.cleanup(apps.map { it.packageName }.toSet())
        selected.clear()
        selected.addAll(settings.packages())

        val sortedApps = apps.sortedWith(compareByDescending<ApplicationInfo> { it.packageName in selected }
            .thenBy { packageManager.getApplicationLabel(it).toString().lowercase() })

        sortedApps.forEach { app ->
            appList.addView(createSplitTunnelAppOption(app, mode, selected, settings, appList), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72),
            ).apply { bottomMargin = dp(8) })
        }
        loading.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                loading.visibility = View.GONE
                listScroll.visibility = View.VISIBLE
                listScroll.animate().alpha(1f).setDuration(250).start()
                staggerListItems(appList)
            }.start()

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase() ?: ""
                for (i in 0 until appList.childCount) {
                    val row = appList.getChildAt(i)
                    val name = (row.tag as? String)?.lowercase() ?: ""
                    val pkg = (row.contentDescription as? String)?.lowercase() ?: ""
                    row.visibility = if (query.isEmpty() || name.contains(query) || pkg.contains(query)) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }
}

internal fun MainActivity.createSplitTunnelAppOption(
    app: ApplicationInfo,
    mode: SplitTunnelSettings.Mode,
    selected: MutableSet<String>,
    settings: SplitTunnelSettings,
    container: ViewGroup,
): LinearLayout {
    val packageName = app.packageName
    lateinit var row: LinearLayout
    fun updateSelection(checked: Boolean, animate: Boolean) {
        row.background = roundedBackground(
            if (checked) primaryContainer else SURFACE_VARIANT,
            16,
            if (checked) primary else SURFACE_VARIANT,
        )
        if (animate) {
            row.animate().cancel()
            row.animate().scaleX(0.98f).scaleY(0.98f)
                .setDuration(80)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    row.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }
    val checkbox = CheckBox(this).apply {
        isChecked = packageName in selected
        contentDescription = "Select ${packageManager.getApplicationLabel(app)}"
        setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selected += packageName
                if (container.indexOfChild(row) != 0) {
                    container.removeView(row)
                    container.addView(row, 0)
                }
            } else {
                selected -= packageName
            }
            settings.save(mode, selected.toHashSet())
            updateSelection(checked, animate = true)
        }
    }
    row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(8), 0)
        isClickable = true
        isFocusable = true
        tag = packageManager.getApplicationLabel(app).toString()
        contentDescription = packageName
        setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        addView(ImageView(self).apply {
            setImageDrawable(app.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val labels = LinearLayout(self).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        labels.addView(label(packageManager.getApplicationLabel(app).toString(), 16f, INK, TypefaceStyle.MEDIUM))
        labels.addView(label(packageName, 11f, MUTED).apply {
            ellipsize = android.text.TextUtils.TruncateAt.END
            setSingleLine(true)
        })
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(checkbox, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    updateSelection(checkbox.isChecked, animate = false)
    return row
}

internal fun MainActivity.createSplitModeOption(
    mode: SplitTunnelSettings.Mode,
    selected: SplitTunnelSettings.Mode,
    onSelect: (SplitTunnelSettings.Mode) -> Unit,
): SelectionOption {
    val title = label(mode.label, 16f, INK, TypefaceStyle.MEDIUM)
    val indicator = label("SELECTED", 11f, primary, TypefaceStyle.MEDIUM).apply { letterSpacing = 0.08f }
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onSelect(mode) }
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator)
    }
    return SelectionOption(row, title, indicator, 18).also { setSelectionState(it, mode == selected, animate = false) }
}

internal fun MainActivity.closeSplitTunnelScreen() {
    persistSplitTunnelDraft()
    splitTunnelPage?.let { animatePageClose(it) { splitTunnelPage = null } }
    splitTunnelSummaryButton?.setValue(splitTunnelSummary())
}

internal fun MainActivity.closeSplitTunnelAppsScreen() {
    persistSplitTunnelDraft()
    splitTunnelAppsPage?.let { animatePageClose(it) { splitTunnelAppsPage = null } }
    splitTunnelSummaryButton?.setValue(splitTunnelSummary())
}

internal fun MainActivity.persistSplitTunnelDraft() {
    val mode = splitTunnelDraftMode ?: return
    val packages = splitTunnelDraftPackages ?: return
    SplitTunnelSettings(this).save(mode, packages.toHashSet())
}

internal fun MainActivity.loadUserApps(onLoaded: (List<ApplicationInfo>) -> Unit) {
    cachedUserApps?.let(onLoaded) ?: Thread {
        val apps = installedUserApps()
        cachedUserApps = apps
        runOnUiThread { if (!isFinishing && !isDestroyed) onLoaded(apps) }
    }.start()
}

@Suppress("DEPRECATION")
internal fun MainActivity.installedUserApps(): List<ApplicationInfo> = packageManager.queryIntentActivities(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
    0,
)
    .asSequence()
    .map { it.activityInfo.applicationInfo }
    .filter { it.packageName != packageName }
    .distinctBy { it.packageName }
    .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
    .toList()
