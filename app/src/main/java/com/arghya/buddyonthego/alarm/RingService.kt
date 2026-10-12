package com.arghya.buddyonthego.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.EventLog
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.gps.TripController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.arghya.buddyonthego.AppScope

data class RingInfo(
    val kind: Int,
    val title: String,
    val body: String,
    val ownerId: Long = 0,
    val snoozeCount: Int = 0,
    val maxSnoozes: Int = 0,
    val snoozeMinutes: Int = 9,
    val rampSec: Int = 20,
    val vibe: Int = 0,
    val challenge: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
)

/** RingActivity watches this to know what to show. */
object RingBus {
    val info = MutableStateFlow<RingInfo?>(null)
}

/**
 * RNG-1 .. RNG-8: the single ring path. Sound and vibration are played here, not by the channel,
 * so silent mode does not mute an alarm (alarm audio stream).
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var info: RingInfo? = null
    private var volume = 0.1f
    private var finished = false

    private val rampTick = object : Runnable {
        override fun run() {
            val step = 0.9f / (((info?.rampSec ?: 20).coerceAtLeast(1)) * 2f)
            volume = (volume + step).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 500)
        }
    }

    private val realert = object : Runnable {
        override fun run() { // RNG-4
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (cur < max) am.setStreamVolume(AudioManager.STREAM_ALARM, cur + 1, 0)
            volume = 1f
            player?.setVolume(1f, 1f)
            startVibration(strong = true)
            info?.let { Notifs.post(this@RingService, Notifs.ID_RING, Notifs.ring(this@RingService, it)) }
            handler.postDelayed(this, ServiceLocator.settings.int(K.REALERT_SEC, 60) * 1000L)
        }
    }

    private val autoStop = Runnable { // ALM-8
        val i = info
        if (i != null) {
            AppScope.io.launch {
                ServiceLocator.db.eventLogDao().insert(EventLog(type = "missed alarm", detail = i.title))
            }
            Notifs.status(this, Notifs.ID_MISSED, "Missed: ${i.title}", "Nobody dismissed it. ${i.body}")
        }
        finish(ownerCleanup = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLocator.init(this)
        when (intent?.action) {
            ACTION_DISMISS -> { finish(ownerCleanup = true); return START_NOT_STICKY }
            ACTION_SNOOZE -> { snooze(); return START_NOT_STICKY }
            ACTION_DISMISS_KIND -> {
                if (info?.kind == intent.getIntExtra("kind", -1)) finish(ownerCleanup = false)
                return START_NOT_STICKY
            }
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val new = RingInfo(
            kind = intent.getIntExtra("kind", RingKind.TIME),
            title = intent.getStringExtra("title") ?: "Buddy",
            body = intent.getStringExtra("body") ?: "",
            ownerId = intent.getLongExtra("ownerId", 0),
            snoozeCount = intent.getIntExtra("snoozeCount", 0),
            maxSnoozes = intent.getIntExtra("maxSnoozes", 0),
            snoozeMinutes = intent.getIntExtra("snoozeMinutes", 9),
            rampSec = intent.getIntExtra("rampSec", 20),
            vibe = intent.getIntExtra("vibe", 0),
            challenge = intent.getIntExtra("challenge", 0),
        )
        info = new
        finished = false
        RingBus.info.value = new
        try {
            ServiceCompat.startForeground(
                this, Notifs.ID_RING, Notifs.ring(this, new),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            // Still try to ring even if the foreground promotion failed.
        }
        acquireWake()
        beginSound()
        handler.removeCallbacks(realert)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(realert, ServiceLocator.settings.int(K.REALERT_SEC, 60) * 1000L)
        handler.postDelayed(autoStop, ServiceLocator.settings.int(K.AUTOSTOP_MIN, 10) * 60_000L)
        return START_NOT_STICKY
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:ring").apply {
            acquire((ServiceLocator.settings.int(K.AUTOSTOP_MIN, 10) + 1) * 60_000L)
        }
    }

    private fun beginSound() {
        val am = getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL) { // ALM-9: wait for the call to end
            AppScope.io.launch {
                ServiceLocator.db.eventLogDao().insert(EventLog(type = "delayed alarm", detail = info?.title ?: ""))
            }
            handler.postDelayed({ if (!finished) beginSound() }, 5000)
            return
        }
        stopSound()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus!!)

        val journey = info?.let { RingKind.isJourneyProblem(it.kind) } == true
        val uri = RingtoneManager.getDefaultUri(if (journey) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        volume = if ((info?.rampSec ?: 0) <= 0) 1f else 0.1f
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                setVolume(volume, volume)
                start()
            }
            if (volume < 1f) handler.postDelayed(rampTick, 500)
        } catch (e: Exception) {
            player = null // vibration still works
        }
        startVibration(strong = false)
    }

    private fun startVibration(strong: Boolean) {
        val v = vibrator ?: if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val i = info ?: return
        val pattern = when {
            RingKind.isJourneyProblem(i.kind) -> longArrayOf(0, 200, 150, 200, 150, 200, 700) // short pulses
            strong || i.vibe == 1 -> longArrayOf(0, 1600, 350)
            else -> longArrayOf(0, 900, 600)
        }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
    }

    private fun stopSound() {
        handler.removeCallbacks(rampTick)
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        vibrator?.cancel()
    }

    private fun snooze() {
        val i = info
        if (i != null && i.kind == RingKind.TIME && i.snoozeCount < i.maxSnoozes) {
            AlarmScheduler.scheduleSnooze(this, i.ownerId, i.snoozeMinutes, i.snoozeCount + 1)
        }
        finish(ownerCleanup = false)
    }

    /** RNG-8: clean ending. */
    private fun finish(ownerCleanup: Boolean) {
        if (finished) return
        finished = true
        val i = info
        handler.removeCallbacksAndMessages(null)
        stopSound()
        if (wake?.isHeld == true) wake?.release()
        Notifs.cancel(this, Notifs.ID_RING)
        info = null
        RingBus.info.value = null
        if (ownerCleanup && i != null && RingKind.endsTrip(i.kind)) TripController.stop(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "com.arghya.buddyonthego.RING_DISMISS"
        const val ACTION_SNOOZE = "com.arghya.buddyonthego.RING_SNOOZE"
        const val ACTION_DISMISS_KIND = "com.arghya.buddyonthego.RING_DISMISS_KIND"

        fun start(
            ctx: Context, kind: Int, title: String, body: String,
            ownerId: Long = 0, snoozeCount: Int = 0, maxSnoozes: Int = 0, snoozeMinutes: Int = 9,
            rampSec: Int = 20, vibe: Int = 0, challenge: Int = 0,
        ) {
            val i = Intent(ctx, RingService::class.java)
                .putExtra("kind", kind).putExtra("title", title).putExtra("body", body)
                .putExtra("ownerId", ownerId).putExtra("snoozeCount", snoozeCount)
                .putExtra("maxSnoozes", maxSnoozes).putExtra("snoozeMinutes", snoozeMinutes)
                .putExtra("rampSec", rampSec).putExtra("vibe", vibe).putExtra("challenge", challenge)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String, kind: Int = -1) {
            try {
                ctx.startService(Intent(ctx, RingService::class.java).setAction(action).putExtra("kind", kind))
            } catch (_: Exception) { }
        }
    }
}
