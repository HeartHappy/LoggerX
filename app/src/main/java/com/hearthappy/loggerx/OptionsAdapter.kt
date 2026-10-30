package com.hearthappy.loggerx

import com.hearthappy.basic.AbsSpecialAdapter
import com.hearthappy.loggerx.app.databinding.ItemOptionsBinding

class OptionsAdapter: AbsSpecialAdapter<ItemOptionsBinding, OptionsItem>() {
    override fun ItemOptionsBinding.bindViewHolder(data: OptionsItem, position: Int) {
        tvTitle.text=data.title
    }
}