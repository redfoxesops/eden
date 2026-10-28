// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui.viewholder

import android.view.View
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.ListItemSettingBinding
import org.yuzu.yuzu_emu.features.settings.model.view.CpuCoreMaskSetting
import org.yuzu.yuzu_emu.features.settings.model.view.SettingsItem
import org.yuzu.yuzu_emu.features.settings.ui.SettingsAdapter
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible

class CpuCoreMaskViewHolder(val binding: ListItemSettingBinding, adapter: SettingsAdapter) :
    SettingViewHolder(binding.root, adapter) {
    private lateinit var setting: CpuCoreMaskSetting

    override fun bind(item: SettingsItem) {
        setting = item as CpuCoreMaskSetting
        binding.textSettingName.text = setting.title
        binding.textSettingDescription.setVisible(item.description.isNotEmpty())
        binding.textSettingDescription.text = setting.description

        val mask = setting.getMask()
        binding.textSettingValue.setVisible(true)
        binding.textSettingValue.text = if (mask == 0) {
            binding.root.context.getString(R.string.cpu_cores_all)
        } else {
            (0 until CpuCoreMaskSetting.coreCount).filter { (mask shr it) and 1 != 0 }
                .joinToString(", ")
        }

        binding.buttonClear.setVisible(setting.clearable)
        binding.buttonClear.setOnClickListener {
            adapter.onClearClick(setting, bindingAdapterPosition)
        }

        setStyle(setting.isEditable, binding)
    }

    override fun onClick(clicked: View) {
        if (setting.isEditable) {
            adapter.onCpuCoreMaskClick(setting, bindingAdapterPosition)
        }
    }

    override fun onLongClick(clicked: View): Boolean {
        if (setting.isEditable) {
            return adapter.onLongClick(setting, bindingAdapterPosition)
        }
        return false
    }
}
