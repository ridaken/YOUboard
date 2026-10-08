// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.latin.utils

import android.content.Context
import com.youboard.keyboard.latin.R
import com.youboard.keyboard.latin.settings.SettingsValues
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared by the cache and application path; contains no editor or text data. */
data class KeyboardGeometrySignature(
    val width: Int, val height: Int, val split: Boolean, val splitGap: Float,
    val bottomPadding: Float, val sidePadding: Float, val keyGap: Float, val bottomRow: Float,
    val orientation: Int, val densityDpi: Int, val floating: Boolean,
    val oneHanded: Boolean, val oneHandedScale: Float, val oneHandedGravity: Int,
    val toolbar: ToolbarMode, val toolbarHidingGlobal: Boolean, val toolbarHeight: Int,
    val numberRow: Boolean, val numberRowInSymbols: Boolean,
) {
    fun diagnosticFields() = buildJsonObject {
        put("width", width); put("height", height); put("split", split); put("splitGap", splitGap)
        put("bottomPadding", bottomPadding); put("sidePadding", sidePadding)
        put("keyGap", keyGap); put("bottomRow", bottomRow); put("orientation", orientation)
        put("densityDpi", densityDpi); put("floating", floating); put("oneHanded", oneHanded)
        put("oneHandedScale", oneHandedScale); put("oneHandedGravity", oneHandedGravity)
        put("toolbar", toolbar.name); put("toolbarHidingGlobal", toolbarHidingGlobal)
        put("toolbarHeight", toolbarHeight); put("numberRow", numberRow)
        put("numberRowInSymbols", numberRowInSymbols)
    }

    companion object {
        @JvmStatic fun create(context: Context, values: SettingsValues): KeyboardGeometrySignature {
            val resources = context.resources
            return KeyboardGeometrySignature(ResourceUtils.getKeyboardWidth(context, values),
                ResourceUtils.getKeyboardHeight(resources, values), values.mIsSplitKeyboardEnabled,
                if (values.mIsSplitKeyboardEnabled) values.mSplitKeyboardSpacerRelativeWidth else 0f,
                values.mBottomPaddingScale, values.mSidePaddingScale, values.mKeyGapScale,
                values.mBottomRowScale, resources.configuration.orientation, resources.displayMetrics.densityDpi,
                values.mIsFloatingKeyboard, values.mOneHandedModeEnabled,
                if (values.mOneHandedModeEnabled) values.mOneHandedModeScale else 1f,
                if (values.mOneHandedModeEnabled) values.mOneHandedModeGravity else 0,
                values.mToolbarMode, values.mToolbarHidingGlobal,
                if (values.isSecondaryStripVisible()) resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) else 0,
                values.mShowsNumberRow, values.mShowsNumberRowInSymbols)
        }
    }
}
