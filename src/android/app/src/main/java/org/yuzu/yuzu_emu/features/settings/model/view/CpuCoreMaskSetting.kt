// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.model.view

import androidx.annotation.StringRes
import java.io.File
import org.yuzu.yuzu_emu.features.settings.model.IntSetting

class CpuCoreMaskSetting(
    @StringRes titleId: Int = 0,
    @StringRes descriptionId: Int = 0
) : SettingsItem(IntSetting.CPU_CORE_MASK, titleId, "", descriptionId, "") {
    override val type = TYPE_CPU_CORE_MASK

    fun getMask(needsGlobal: Boolean = false): Int = IntSetting.CPU_CORE_MASK.getInt(needsGlobal)

    fun setMask(value: Int) = IntSetting.CPU_CORE_MASK.setInt(value)

    companion object {
        // The setting is a 32-bit mask
        val coreCount: Int by lazy {
            val possible = runCatching {
                File("/sys/devices/system/cpu/possible").readText().trim()
                    .substringAfterLast('-').toInt() + 1
            }.getOrNull()
            (possible ?: Runtime.getRuntime().availableProcessors()).coerceIn(1, 32)
        }

        // Max frequency of a core in GHz, or null if it can't be read
        fun maxFrequency(cpu: Int): Float? = runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim()
                .toFloat() / 1_000_000f
        }.getOrNull()
    }
}
