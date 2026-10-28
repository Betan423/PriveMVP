package com.example.privagentcollector

import android.app.Application
import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

object CollectorRuntime {
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "collector-io") }
    )
    val dropped = AtomicInteger()
    @Volatile var lastError: String? = null
        private set
    fun submit(task: () -> Unit): Boolean {
        return try {
            executor.execute {
                try { task() } catch (e: Exception) {
                    // Exception messages can contain contacts or URIs; do not log them.
                    lastError = "資料處理失敗：${e.javaClass.simpleName}"
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            dropped.incrementAndGet()
            lastError = "事件佇列已滿，已略過部分事件"
            false
        }
    }
    fun event(context: Context, source: DataSource, session: String, task: () -> Unit) {
        submit { if (CollectionControl.allowed(context, source, session)) task() }
    }
}

class CollectorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CollectorRuntime.submit { ResearchLog.maintain(this) }
    }
}
