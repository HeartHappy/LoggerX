package com.hearthappy.loggerx.preview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hearthappy.loggerx.LoggerX
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewFilterEspressoTest {

    @Before
    fun prepareData() {
        LoggerX.COMMON.clearAllLogs()
        TagALogger.logDebug()
        TagBLogger.logInfo()
        TagALogger.logDebug()
        TagBLogger.logInfo()
    }

    @Test
    fun preparedLogs_shouldBeQueryable() {
        assertEquals(4, LoggerX.COMMON.queryLogs(limit = 10).size)
    }



    private object TagALogger {
        fun logDebug() {
            LoggerX.COMMON.d("Tag A message")
        }
    }

    private object TagBLogger {
        fun logInfo() {
            LoggerX.COMMON.i("Tag B message")
        }
    }
}
