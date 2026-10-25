// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.model

// The stored value is in RCAS stops (0 = sharpest, 200 = softest), shown as 0-100% where
// higher is sharper, matching the desktop slider
object FsrSharpnessSetting : AbstractIntSetting by IntSetting.FSR_SHARPENING_SLIDER {
    private fun toDisplay(raw: Int) = (200 - raw + 1) / 2

    override fun getInt(needsGlobal: Boolean): Int =
        toDisplay(IntSetting.FSR_SHARPENING_SLIDER.getInt(needsGlobal))

    override fun setInt(value: Int) = IntSetting.FSR_SHARPENING_SLIDER.setInt(200 - value * 2)

    override val defaultValue: Int
        get() = toDisplay(IntSetting.FSR_SHARPENING_SLIDER.defaultValue)

    override fun getValueAsString(needsGlobal: Boolean): String = getInt(needsGlobal).toString()
}
