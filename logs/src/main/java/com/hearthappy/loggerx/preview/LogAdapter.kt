package com.hearthappy.loggerx.preview

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hearthappy.basic.ext.dp
import com.hearthappy.basic.ext.show
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.core.LogLevel
import com.hearthappy.loggerx.image.LogImageLoaderFactory
import com.hearthappy.loggerx.databinding.ItemLogEmptyBinding
import com.hearthappy.loggerx.databinding.ItemLogListBinding
import com.hearthappy.loggerx.databinding.ItemLogStreamlineListBinding

class LogAdapter(val context : Context, val outPutterIndex : Int) : ListAdapter<Map<String, Any>, RecyclerView.ViewHolder>(LogDiffCallback) {

    var isSimplified = false

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_EMPTY -> {
                val binding = ItemLogEmptyBinding.inflate(inflater, parent, false)
                EmptyViewHolder(binding)
            }
            TYPE_SIMPLIFIED -> {
                val binding = ItemLogStreamlineListBinding.inflate(inflater, parent, false)
                SimplifiedLogViewHolder(binding)
            }
            else -> {
                val binding = ItemLogListBinding.inflate(inflater, parent, false)
                FullLogViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder : RecyclerView.ViewHolder, position : Int) {
        when (holder) {
            is FullLogViewHolder -> holder.bind(getItem(position))
            is SimplifiedLogViewHolder -> holder.bind(getItem(position))
            is EmptyViewHolder -> { /* 空布局无需绑定数据 */ }
        }
    }

    override fun onViewRecycled(holder : RecyclerView.ViewHolder) {
        if (holder is FullLogViewHolder) holder.unbind()
        if (holder is SimplifiedLogViewHolder) holder.unbind()
        super.onViewRecycled(holder)
    }

    // 核心修改：当数据为空时，返回 1 个 Item 用于显示空布局
    override fun getItemCount(): Int {
        val actualCount = super.getItemCount()
        return if (actualCount == 0) 1 else actualCount
    }

    // 核心修改：根据数据量返回对应的视图类型
    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        if (item.containsKey("IS_EMPTY_PLACEHOLDER")) {
            return TYPE_EMPTY
        }
        return if (isSimplified) TYPE_SIMPLIFIED else TYPE_FULL
    }

    override fun getItemId(position : Int) : Long {
        if (super.getItemCount() == 0) return -1L // 空布局的固定 ID
        return getItem(position)[LoggerX.COLUMN_ID]?.toString()?.toLongOrNull() ?: RecyclerView.NO_ID
    }

    fun submitLogs(logs: List<Map<String, Any>>) {
        if (logs.isEmpty()) {
            // 如果为空，提交一个带特殊标记的 Map，确保 ListAdapter 认为数量是 1
            submitList(listOf(mapOf("IS_EMPTY_PLACEHOLDER" to true)))
        } else {
            submitList(logs.toList())
        }
    }

    // --- ViewHolder 定义 ---

    inner class FullLogViewHolder(private val binding : ItemLogListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(data : Map<String, Any>) = with(binding) {
            tvId.text = String.format("%s", data[LoggerX.COLUMN_ID])
            tvTime.text = data[LoggerX.COLUMN_TIME].toString()
            tvLevel.text = data[LoggerX.COLUMN_LEVEL].toString()
            level2Color(tvLevel, data[LoggerX.COLUMN_LEVEL].toString())
            tvTag.text = String.format(">_  %s", data[LoggerX.COLUMN_TAG])
            tvMethod.text = data[LoggerX.COLUMN_METHOD].toString()
            tvMessage.text = data[LoggerX.COLUMN_MESSAGE].toString()

            val isImage = LogImageUiHelper.isImageLog(data)
            val hasAttachment = LogImageUiHelper.hasAttachment(data)
            val filePath = LogImageUiHelper.resolveFilePath(data)
            ivImageThumb.show(hasAttachment, showBlock = {
                val requestPath = filePath.ifBlank { null }
                this.setImageDrawable(null)
                tag = requestPath
                if (requestPath != null && isImage) {
                    LogImageLoaderFactory.get(context).loadThumbnail(this, path = requestPath)
                } else {
                    setImageResource(android.R.drawable.ic_menu_info_details)
                }

                setOnClickListener {
                    data[LoggerX.COLUMN_ID]?.toString()?.toIntOrNull()?.let { logId ->
                        val activityClass = if (isImage) PreviewLargeImageActivity::class.java else PreviewTextAttachmentActivity::class.java
                        context.startActivity(Intent(context, activityClass).apply {
                            putExtra(PreviewLargeImageActivity.ARG_OUTPUTTER_INDEX, outPutterIndex)
                            putExtra(PreviewLargeImageActivity.ARG_LOG_ID, logId)
                        })
                    }
                }
            }) {
                tag = null
                setImageDrawable(null)
                setOnClickListener(null)
            }
        }

        fun unbind() = with(binding.ivImageThumb) {
            tag = null
            setImageDrawable(null)
            setOnClickListener(null)
        }
    }

    inner class SimplifiedLogViewHolder(private val binding : ItemLogStreamlineListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(data : Map<String, Any>) = with(binding) {
            tvId.text = String.format("%s", data[LoggerX.COLUMN_ID])
            tvTime.text = data[LoggerX.COLUMN_TIME].toString()
            tvLevel.text = data[LoggerX.COLUMN_LEVEL].toString()
            level2Color(tvLevel, data[LoggerX.COLUMN_LEVEL].toString(),false)
            val isImage = LogImageUiHelper.isImageLog(data)
            val hasAttachment = LogImageUiHelper.hasAttachment(data)
            val filePath = LogImageUiHelper.resolveFilePath(data)
            ivImageThumb.show(hasAttachment, showBlock = {
                val requestPath = filePath.ifBlank { null }
                this.setImageDrawable(null)
                tag = requestPath
                if (requestPath != null && isImage) {
                    LogImageLoaderFactory.get(context).loadThumbnail(this, path = requestPath)
                } else {
                    setImageResource(android.R.drawable.ic_menu_info_details)
                }

                setOnClickListener {
                    data[LoggerX.COLUMN_ID]?.toString()?.toIntOrNull()?.let { logId ->
                        val activityClass = if (isImage) PreviewLargeImageActivity::class.java else PreviewTextAttachmentActivity::class.java
                        context.startActivity(Intent(context, activityClass).apply {
                            putExtra(PreviewLargeImageActivity.ARG_OUTPUTTER_INDEX, outPutterIndex)
                            putExtra(PreviewLargeImageActivity.ARG_LOG_ID, logId)
                        })
                    }
                }
            }) {
                tag = null
                setImageDrawable(null)
                setOnClickListener(null)
            }
            tvMessage.text = data[LoggerX.COLUMN_MESSAGE].toString()
        }

        fun unbind() = with(binding.ivImageThumb) {
            tag = null
            setImageDrawable(null)
            setOnClickListener(null)
        }
    }

    // 空布局 ViewHolder
    inner class EmptyViewHolder(binding: ItemLogEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    private fun level2Color(tvLevel : TextView, level : String, isBackground: Boolean = true) {
        val baseColor = when (level) {
            LogLevel.VERBOSE.value -> 0xFFBBBBBB.toInt()
            LogLevel.DEBUG.value -> 0xFF33B5E5.toInt()
            LogLevel.INFO.value -> 0xFF99CC00.toInt()
            LogLevel.WARN.value -> 0xFFFFBB33.toInt()
            LogLevel.ERROR.value -> 0xFFFF4444.toInt()
            else -> 0xFFBBBBBB.toInt()
        }
        tvLevel.setTextColor(baseColor)
        if(isBackground){
            val colorWithAlpha = ColorUtils.setAlphaComponent(baseColor, (255 * 0.3f).toInt())
            val borderColor = ColorUtils.setAlphaComponent(baseColor, (255 * 0.6f).toInt())
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.RECTANGLE
            drawable.cornerRadius = 50f.dp
            drawable.setColor(colorWithAlpha)
            drawable.setStroke(1.dp, borderColor)
            tvLevel.background = drawable
        }
    }

    private object LogDiffCallback : DiffUtil.ItemCallback<Map<String, Any>>() {
        override fun areItemsTheSame(oldItem : Map<String, Any>, newItem : Map<String, Any>) : Boolean {
            val oldId = oldItem[LoggerX.COLUMN_ID]?.toString()
            val newId = newItem[LoggerX.COLUMN_ID]?.toString()
            return oldId != null && oldId == newId
        }

        override fun areContentsTheSame(oldItem : Map<String, Any>, newItem : Map<String, Any>) : Boolean {
            if (oldItem.size != newItem.size) return false
            for ((key, value) in oldItem) {
                if (value != newItem[key]) return false
            }
            return true
        }
    }

    companion object {
        private const val TYPE_FULL = 0
        private const val TYPE_SIMPLIFIED = 1
        private const val TYPE_EMPTY = 2
    }
}
