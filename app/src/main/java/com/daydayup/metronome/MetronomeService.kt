package com.daydayup.metronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.daydayup.MainActivity
import com.daydayup.R

class MetronomeService : Service() {
    companion object {
        const val START = "com.daydayup.START"
        const val PAUSE = "com.daydayup.PAUSE"
        const val RESUME = "com.daydayup.RESUME"
        const val STOP = "com.daydayup.STOP"
        const val SET_BPM = "com.daydayup.SET_BPM"
        const val SET_VOLUME = "com.daydayup.SET_VOLUME"
        const val SET_SOUND = "com.daydayup.SET_SOUND"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SOUND = "sound"

        fun send(context: Context, action: String, value: Int? = null) {
            val intent = Intent(context, MetronomeService::class.java).setAction(action)
            if (value != null) intent.putExtra(EXTRA_BPM, value)
            if (action == START) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val channel = "metronome_running"
    private val notificationId = 1001
    private lateinit var store: SessionStore
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var pool: SoundPool
    private lateinit var wakeLock: PowerManager.WakeLock
    private val stateLock = Any()
    private var clickId = 0
    private var session: MetronomeSession? = null
    private var nextBeatNs = 0L
    private var wallStartedAt = 0L
    private var lastCheckpointMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(channel, "节拍器运行", NotificationManager.IMPORTANCE_LOW))
        thread = HandlerThread("daydayup-clicks").also { it.start() }
        handler = Handler(thread.looper)
        pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        clickId = pool.load(this, R.raw.click, 1)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daydayup:metronome")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) { when (intent?.action) {
            START -> if (session == null) {
                val bpm = intent.getIntExtra(EXTRA_BPM, MetronomeRuntime.bpm)
                if (!Cadence.isValid(bpm)) { stopSelf(); return START_NOT_STICKY }
                store.recoverInterrupted()
                wallStartedAt = System.currentTimeMillis()
                session = MetronomeSession(bpm, now)
                store.begin(wallStartedAt, wallStartedAt, bpm)
                MetronomeRuntime.bpm = bpm
                MetronomeRuntime.elapsedMs = 0
                MetronomeRuntime.phase = "running"
                wakeLock.acquire()
                startForeground(notificationId, notification())
                nextBeatNs = SystemClock.elapsedRealtimeNanos()
                handler.post(tick)
            }
            PAUSE -> session?.let {
                it.pause(now)
                handler.removeCallbacks(tick)
                if (wakeLock.isHeld) wakeLock.release()
                checkpoint(now)
                MetronomeRuntime.elapsedMs = it.elapsedMs(now)
                MetronomeRuntime.phase = "paused"
                updateNotification()
            }
            RESUME -> session?.let {
                it.resume(now)
                MetronomeRuntime.phase = "running"
                if (!wakeLock.isHeld) wakeLock.acquire()
                nextBeatNs = SystemClock.elapsedRealtimeNanos()
                handler.removeCallbacks(tick)
                handler.post(tick)
                updateNotification()
            }
            SET_BPM -> session?.let {
                val value = intent.getIntExtra(EXTRA_BPM, it.bpm)
                if (it.changeBpm(value, now)) {
                    MetronomeRuntime.bpm = value
                    nextBeatNs = SystemClock.elapsedRealtimeNanos() + 60_000_000_000L / value
                    handler.removeCallbacks(tick)
                    if (!it.paused) handler.postDelayed(tick, 60_000L / value)
                    checkpoint(now)
                    updateNotification()
                }
            }
            SET_VOLUME -> MetronomeRuntime.volume = intent.getFloatExtra(EXTRA_VOLUME, MetronomeRuntime.volume).coerceIn(0f, 1f)
            SET_SOUND -> {
                MetronomeRuntime.soundEnabled = intent.getBooleanExtra(EXTRA_SOUND, MetronomeRuntime.soundEnabled)
                MetronomeRuntime.audible = MetronomeRuntime.soundEnabled && MetronomeRuntime.volume > 0f
                updateNotification()
            }
            STOP -> session?.let {
                handler.removeCallbacks(tick)
                if (wakeLock.isHeld) wakeLock.release()
                val duration = it.stop(now)
                store.finish(System.currentTimeMillis(), duration, it.changes)
                session = null
                MetronomeRuntime.elapsedMs = duration
                MetronomeRuntime.phase = "idle"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            synchronized(stateLock) {
            val current = session ?: return
            if (current.paused) return
            val tickNs = SystemClock.elapsedRealtimeNanos()
            if (tickNs < nextBeatNs) {
                handler.postDelayed(this, ((nextBeatNs - tickNs) / 1_000_000L).coerceAtLeast(1L))
                return
            }
            val now = SystemClock.elapsedRealtime()
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val callActive = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
            val wasAudible = MetronomeRuntime.audible
            MetronomeRuntime.audible = !callActive && MetronomeRuntime.soundEnabled && MetronomeRuntime.volume > 0f
            if (wasAudible != MetronomeRuntime.audible) updateNotification()
            if (MetronomeRuntime.audible && pool.play(clickId, MetronomeRuntime.volume, MetronomeRuntime.volume, 1, 0, 1f) == 0) {
                MetronomeRuntime.audible = false
                updateNotification()
            }
            MetronomeRuntime.elapsedMs = current.elapsedMs(now)
            if (now - lastCheckpointMs >= 5_000L) checkpoint(now)
            val intervalNs = 60_000_000_000L / current.bpm
            nextBeatNs += intervalNs
            if (nextBeatNs < tickNs) nextBeatNs = tickNs + intervalNs
            handler.postDelayed(this, ((nextBeatNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(1L))
            }
        }
    }

    private fun checkpoint(now: Long) {
        session?.let { store.checkpoint(it.elapsedMs(now), it.changes) }
        lastCheckpointMs = now
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notificationId, notification())
    }

    private fun notification(): Notification {
        val paused = MetronomeRuntime.phase == "paused"
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun command(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, MetronomeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("天天向上 · ${MetronomeRuntime.bpm} 步/分钟")
            .setContentText(if (paused) "已暂停" else if (MetronomeRuntime.audible) "节拍器运行中" else "计时继续，当前无提示音")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停",
                command(if (paused) RESUME else PAUSE, 2)).build())
            .addAction(Notification.Action.Builder(null, "结束", command(STOP, 3)).build())
            .build()
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            session?.let { checkpoint(SystemClock.elapsedRealtime()) }
            if (wakeLock.isHeld) wakeLock.release()
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        pool.release()
        super.onDestroy()
    }
}
