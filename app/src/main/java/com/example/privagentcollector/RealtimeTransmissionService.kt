package com.example.privagentcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealtimeTransmissionService : Service() {
    companion object {
        private const val ACTION_START = "com.example.privagentcollector.REALTIME_START"
        private const val ACTION_STOP = "com.example.privagentcollector.REALTIME_STOP"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_BASELINE_SECONDS = "baseline_seconds"
        private const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        private const val EXTRA_IMMEDIATE = "immediate"
        private const val CHANNEL_ID = "realtime_transmission"
        private const val NOTIFICATION_ID = 4101
        private const val IMMEDIATE_DEBOUNCE_SECONDS = 2L

        fun start(context: Context, baseUrl: String, settings: RealtimeSettings) {
            RealtimeRuntime.markStarting()
            try {
                ContextCompat.startForegroundService(context, Intent(context, RealtimeTransmissionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_BASE_URL, baseUrl)
                    putExtra(EXTRA_BASELINE_SECONDS, settings.baselineSeconds)
                    putExtra(EXTRA_INTERVAL_SECONDS, settings.intervalSeconds)
                    putExtra(EXTRA_IMMEDIATE, settings.immediateEvents)
                })
            } catch (e: Exception) {
                RealtimeRuntime.markPermanentError("無法啟動即時服務：${e.javaClass.simpleName}")
                throw e
            }
        }

        fun stop(context: Context) {
            RealtimeRuntime.requestStop()
            val intent = Intent(context, RealtimeTransmissionService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(intent)
                CollectionControl.stop()
                RealtimeRuntime.finishStopped()
            }
        }
    }

    private val generation = AtomicInteger()
    private lateinit var executor: ScheduledThreadPoolExecutor
    private var sessionId: String? = null
    private var baseUrl = ""
    private var intervalSeconds = RealtimeSettingRules.DEFAULT_INTERVAL_SECONDS
    @Volatile private var baselineComplete = false
    private var pendingMode: String? = null
    private var permanentFailure = false
    private var baselineFuture: ScheduledFuture<*>? = null
    private var periodicFuture: ScheduledFuture<*>? = null
    private var immediateFuture: ScheduledFuture<*>? = null
    private val scheduleLock = Any()

    override fun onCreate() {
        super.onCreate()
        executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "realtime-transmission").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPipeline()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在啟動即時蒐集與傳送"))
        if (intent?.action != ACTION_START) {
            stopPipeline()
            return START_NOT_STICKY
        }
        if (sessionId != null) return START_NOT_STICKY
        return try {
            startPipeline(intent)
            START_NOT_STICKY
        } catch (e: Exception) {
            failPermanently("即時服務啟動失敗：${e.message ?: e.javaClass.simpleName}")
            START_NOT_STICKY
        }
    }

    private fun startPipeline(intent: Intent) {
        check(!CollectionControl.active()) { "請先停止目前的手動蒐集" }
        baseUrl = TransferProtocol.normalizeBaseUrl(intent.getStringExtra(EXTRA_BASE_URL).orEmpty())
        val baselineSeconds = intent.getIntExtra(
            EXTRA_BASELINE_SECONDS,
            RealtimeSettingRules.DEFAULT_BASELINE_SECONDS
        )
        intervalSeconds = intent.getIntExtra(
            EXTRA_INTERVAL_SECONDS,
            RealtimeSettingRules.DEFAULT_INTERVAL_SECONDS
        )
        val settings = RealtimeSettingRules.parse(
            baselineSeconds.toString(),
            intervalSeconds.toString(),
            intent.getBooleanExtra(EXTRA_IMMEDIATE, true)
        )
        val session = CollectionControl.start(this)
        sessionId = session
        baselineComplete = false
        pendingMode = null
        permanentFailure = false
        val run = generation.incrementAndGet()
        RealtimeRuntime.start(session, settings.immediateEvents) { scheduleImmediate(run, session) }
        updateNotification()

        executor.execute {
            if (!isCurrent(run, session)) return@execute
            collectBaseline(session)
        }
        baselineFuture = executor.schedule(
            { finishBaseline(run, session) },
            settings.baselineSeconds.toLong(),
            TimeUnit.SECONDS
        )
    }

    private fun collectBaseline(session: String) {
        RealtimeRuntime.markPhase("baseline")
        updateNotification()
        Collectors.collectAll(applicationContext, session) { title ->
            RealtimeRuntime.markPhase("baseline")
            updateNotification("基礎蒐集：$title")
        }
        if (CollectionControl.allowed(applicationContext, DataSource.NOTIFICATIONS, session)) {
            runCatching { ResearchNotificationListener.snapshot(session) }
        }
    }

    private fun finishBaseline(run: Int, session: String) {
        if (!isCurrent(run, session)) return
        Collectors.collectRealtimeSelected(applicationContext, session)
        baselineComplete = true
        flush(run, session, "baseline")
        if (!isCurrent(run, session)) return
        periodicFuture = executor.scheduleWithFixedDelay(
            {
                if (!isCurrent(run, session)) return@scheduleWithFixedDelay
                Collectors.collectRealtimeSelected(applicationContext, session)
                flush(run, session, "periodic")
            },
            intervalSeconds.toLong(),
            intervalSeconds.toLong(),
            TimeUnit.SECONDS
        )
    }

    private fun scheduleImmediate(run: Int, session: String) {
        if (!baselineComplete || !isCurrent(run, session)) return
        synchronized(scheduleLock) {
            immediateFuture?.cancel(false)
            immediateFuture = executor.schedule(
                { if (isCurrent(run, session)) flush(run, session, "immediate") },
                IMMEDIATE_DEBOUNCE_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun flush(run: Int, session: String, requestedMode: String) {
        if (!isCurrent(run, session)) return
        val batch = RealtimeRuntime.outbox.beginBatch() ?: run {
            RealtimeRuntime.markPhase("waiting")
            updateNotification()
            return
        }
        if (pendingMode == null) pendingMode = requestedMode
        RealtimeRuntime.markPhase("sending")
        updateNotification("正在傳送 ${batch.recordCount} 筆資料")
        try {
            RawDataSender.sendLiveBatch(
                applicationContext,
                baseUrl,
                batch,
                requireNotNull(pendingMode),
                session
            )
            if (!isCurrent(run, session)) return
            check(RealtimeRuntime.outbox.acknowledge(batch.transferId)) { "即時佇列回執狀態不一致" }
            pendingMode = null
            val remaining = RealtimeRuntime.outbox.queuedCount()
            RealtimeRuntime.markTransferSuccess(batch.transferId, remaining)
            updateNotification()
            if (remaining > 0 && isCurrent(run, session)) {
                flush(run, session, requestedMode)
            }
        } catch (e: Exception) {
            if (!isCurrent(run, session)) return
            val retryable = when (e) {
                is TransferFailedException -> e.retryable
                is IOException -> true
                else -> false
            }
            val message = e.message ?: e.javaClass.simpleName
            if (retryable) {
                RealtimeRuntime.markRetryableError("$message；將於下次週期重試")
                updateNotification()
            } else {
                failPermanently(message)
            }
        }
    }

    private fun failPermanently(message: String) {
        generation.incrementAndGet()
        baselineFuture?.cancel(false)
        periodicFuture?.cancel(false)
        synchronized(scheduleLock) { immediateFuture?.cancel(false) }
        sessionId = null
        baselineComplete = false
        pendingMode = null
        permanentFailure = true
        CollectionControl.stop()
        RealtimeRuntime.markPermanentError(message)
        updateNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isCurrent(run: Int, session: String): Boolean =
        generation.get() == run && sessionId == session && RealtimeRuntime.validFor(session) &&
            CollectionControl.session() == session

    private fun stopPipeline() {
        generation.incrementAndGet()
        baselineFuture?.cancel(true)
        periodicFuture?.cancel(true)
        synchronized(scheduleLock) { immediateFuture?.cancel(true) }
        sessionId = null
        baselineComplete = false
        pendingMode = null
        CollectionControl.stop()
        RealtimeRuntime.finishStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        baselineFuture?.cancel(true)
        periodicFuture?.cancel(true)
        synchronized(scheduleLock) { immediateFuture?.cancel(true) }
        executor.shutdownNow()
        CollectionControl.stop()
        if (!permanentFailure && RealtimeRuntime.phase != "error") RealtimeRuntime.finishStopped()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RealtimeRuntime.markRetryableError("系統已達 dataSync 前景服務時限，請重新啟動")
        stopPipeline()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "即時資料傳送",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "顯示基礎、定期與事件觸發的資料傳送狀態" }
        )
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RealtimeTransmissionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_collector)
            .setContentTitle("AI 金孫即時資料管線")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun updateNotification(content: String = RealtimeRuntime.statusText().lineSequence().first()) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(content.take(100))
        )
    }
}
