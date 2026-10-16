package com.hearthappy.loggerx.preview

import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hearthappy.basic.AbsBaseActivity
import com.hearthappy.loggerx.LoggerX
import com.hearthappy.loggerx.R
import com.hearthappy.loggerx.core.LogScopeProxy
import com.hearthappy.loggerx.databinding.ActivityPreviewTextAttachmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PreviewTextAttachmentActivity : AbsBaseActivity<ActivityPreviewTextAttachmentBinding>() {
    override fun ActivityPreviewTextAttachmentBinding.initData() = Unit

    override fun ActivityPreviewTextAttachmentBinding.initListener() {
        btnClose.setOnClickListener { finish() }
    }

    override fun ActivityPreviewTextAttachmentBinding.initView() {
        val outputterIndex = intent.getIntExtra(PreviewLargeImageActivity.ARG_OUTPUTTER_INDEX, -1)
        val logId = intent.getIntExtra(PreviewLargeImageActivity.ARG_LOG_ID, -1)
        if (outputterIndex !in LoggerX.getOutputters().indices || logId < 0) {
            Toast.makeText(this@PreviewTextAttachmentActivity, "日志附件参数无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                val scopeProxy: LogScopeProxy = LoggerX.getOutputters()[outputterIndex].scope.getProxy()
                val preview = scopeProxy.loadAttachmentPreviewData(logId) ?: return@withContext null
                val file = File(preview.filePath)
                if (file.exists() && file.isFile && file.canRead()) file.readText() else null
            }
            if (content == null) {
                Toast.makeText(this@PreviewTextAttachmentActivity, "崩溃报告读取失败", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                val scopeTag = LoggerX.getOutputters().getOrNull(outputterIndex)?.scope?.getTag()
                tvContent.text = if (scopeTag == LoggerX.CATCH.getTag()) {
                    CatchCallChainHighlighter.highlight(
                        content,
                        ContextCompat.getColor(this@PreviewTextAttachmentActivity, R.color.catch_package_highlight)
                    )
                } else {
                    content
                }
            }
        }
    }

    override fun ActivityPreviewTextAttachmentBinding.initViewModelListener() = Unit
}
