package com.arghya.buddyonthego.alarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.MathChallenge
import com.arghya.buddyonthego.ui.BigButton
import com.arghya.buddyonthego.ui.BtnStyle
import com.arghya.buddyonthego.ui.BuddyTheme
import com.arghya.buddyonthego.ui.ClockText
import com.arghya.buddyonthego.ui.LocalLook
import com.arghya.buddyonthego.ui.rememberBool
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.sqrt

/** RNG-3: full-screen ring over the lockscreen. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { BuddyTheme { RingScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun RingScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val look = LocalLook.current
    val h12 = rememberBool(K.H12, true)
    val info by RingBus.info.collectAsState()
    LaunchedEffect(info == null) {
        if (info == null) {
            delay(1500)
            if (RingBus.info.value == null) onClose()
        }
    }
    val i = info ?: return Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))

    val elapsed by produceState(0L, i.startedAt) {
        while (true) { value = System.currentTimeMillis() - i.startedAt; delay(500) }
    }
    var failed by remember(i.startedAt) { mutableIntStateOf(0) }
    // ALM-7 never lock the user out: after a minute, or several wrong answers, a plain Dismiss appears.
    val easy = elapsed > 60_000 || failed >= 5 || i.kind != RingKind.TIME
    // The challenge is finished, so the service is told it may dismiss.
    val dismiss = { RingService.send(ctx, RingService.ACTION_DISMISS, verified = true) }
    val journey = RingKind.isJourneyProblem(i.kind)
    val challengeShown = i.kind == RingKind.TIME && !easy && i.challenge != Challenge.TAP
    val clock = remember(elapsed / 1000) { Calendar.getInstance() }

    Surface(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(if (journey) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background),
            ),
        ),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ClockText(clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE), h12, if (challengeShown && i.challenge == Challenge.MATH) 56.sp else 76.sp)
                Spacer(Modifier.height(16.dp))
                Text(i.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(i.body, fontSize = 20.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))

                if (challengeShown) {
                    when (i.challenge) {
                        Challenge.MATH -> MathChallengeView(i.mathLevel, failed, onSolved = { dismiss() }, onFail = { failed++ })
                        Challenge.SHAKE -> ShakeChallenge(onDone = { dismiss() })
                        else -> HoldToDismiss("Hold to dismiss", onDone = { dismiss() })
                    }
                } else {
                    if (i.kind == RingKind.TIME && i.challenge != Challenge.TAP) {
                        Text("Challenge skipped so you are never stuck.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                    }
                    BigButton(
                        if (RingKind.endsTrip(i.kind)) "Stop tracking" else "Dismiss", { dismiss() },
                        Modifier.fillMaxWidth(), height = 88.dp,
                    )
                }
            }

            if (i.kind == RingKind.TIME) {
                val left = i.maxSnoozes - i.snoozeCount
                Spacer(Modifier.height(12.dp))
                BigButton(
                    if (left > 0) "Snooze ${i.snoozeMinutes} min" else "No snoozes left",
                    { RingService.send(ctx, RingService.ACTION_SNOOZE) },
                    Modifier.fillMaxWidth(), BtnStyle.Tonal, enabled = left > 0, height = 64.dp,
                )
            }
        }
    }
}

@Composable
private fun HoldToDismiss(label: String, onDone: () -> Unit) {
    val look = LocalLook.current
    val prog = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        prog.animateTo(1f, tween(2000, easing = LinearEasing))
                        onDone()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { prog.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(prog.value.coerceIn(0f, 1f))
                .background(Brush.horizontalGradient(listOf(look.btnA, look.btnB))),
        )
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Math with an on-screen number pad, so no keyboard can ever cover the answer. */
@Composable
private fun MathChallengeView(level: Int, failed: Int, onSolved: () -> Unit, onFail: () -> Unit) {
    val look = LocalLook.current
    // A wrong answer gives a fresh problem, so guessing does not help.
    val problem = remember(failed) { MathChallenge.generate(level) }
    var text by remember(failed) { mutableStateOf("") }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${problem.text} = ?", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, if (failed > 0 && text.isEmpty()) look.danger else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (text.isEmpty()) (if (failed > 0) "Wrong. Try this one" else "Your answer") else text,
                fontSize = if (text.isEmpty()) 18.sp else 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "OK"))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val style = if (key == "OK") BtnStyle.Primary else BtnStyle.Tonal
                    BigButton(
                        if (key == "<") "Del" else key,
                        {
                            if (key == "<") {
                                text = text.dropLast(1)
                            } else if (key == "OK") {
                                if (text.isNotEmpty()) {
                                    if (text.toIntOrNull() == problem.answer) onSolved() else onFail()
                                }
                            } else if (text.length < 4) {
                                text += key
                            }
                        },
                        Modifier.weight(1f), style, height = 60.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShakeChallenge(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var shakes by remember { mutableIntStateOf(0) }
    val needed = 12
    val currentDone by rememberUpdatedState(onDone)
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                val now = System.currentTimeMillis()
                if (g > 20f && now - last > 250) {
                    last = now
                    shakes++
                    if (shakes == needed) currentDone()
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Shake the phone to dismiss", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        LinearProgressIndicator(progress = { (shakes.toFloat() / needed).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)))
        Text("$shakes of $needed", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
