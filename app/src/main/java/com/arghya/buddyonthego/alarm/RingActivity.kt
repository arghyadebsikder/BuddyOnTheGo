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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.ui.BuddyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.sqrt
import kotlin.random.Random

/** RNG-3: full-screen ring over the lockscreen. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val easy = elapsed > 60_000 || failed >= 3 || i.kind != RingKind.TIME // ALM-7 never lock the user out
    val dismiss = { RingService.send(ctx, RingService.ACTION_DISMISS) }
    val journey = RingKind.isJourneyProblem(i.kind)

    Surface(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(if (journey) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background),
            ),
        ),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
                val now = remember(elapsed / 1000) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) }
                Text(now, fontSize = 72.sp, fontWeight = FontWeight.ExtraLight)
                Spacer(Modifier.height(24.dp))
                Text(i.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(i.body, fontSize = 20.sp, textAlign = TextAlign.Center)
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (i.kind == RingKind.TIME && !easy) {
                    when (i.challenge) {
                        1 -> MathChallenge(onSolved = { dismiss() }, onFail = { failed++ })
                        2 -> ShakeChallenge(onDone = { dismiss() })
                        else -> HoldToDismiss("Hold to dismiss", onDone = { dismiss() })
                    }
                }
                if (easy) {
                    Button(
                        onClick = { dismiss() },
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) { Text(if (RingKind.endsTrip(i.kind)) "Stop tracking" else "Dismiss", fontSize = 24.sp) }
                } else if (i.kind == RingKind.TIME && i.challenge == 0) {
                    // hold button already shown above
                }
                if (i.kind == RingKind.TIME) {
                    val left = i.maxSnoozes - i.snoozeCount
                    FilledTonalButton(
                        onClick = { RingService.send(ctx, RingService.ACTION_SNOOZE) },
                        enabled = left > 0,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text(if (left > 0) "Snooze ${i.snoozeMinutes} min" else "No snoozes left (maximum reached)", fontSize = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun HoldToDismiss(label: String, onDone: () -> Unit) {
    val prog = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        prog.animateTo(1f, tween(1500, easing = LinearEasing))
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        )
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MathChallenge(onSolved: () -> Unit, onFail: () -> Unit) {
    val a = remember { Random.nextInt(6, 19) }
    val b = remember { Random.nextInt(6, 19) }
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Solve to dismiss:  $a + $b = ?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = text, onValueChange = { text = it.filter(Char::isDigit).take(3) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (text.toIntOrNull() == a + b) onSolved() else { text = ""; onFail() } },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("Check", fontSize = 20.sp) }
    }
}

@Composable
private fun ShakeChallenge(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var shakes by remember { mutableIntStateOf(0) }
    val needed = 12
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
                    if (shakes >= needed) onDone()
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Shake the phone to dismiss", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { (shakes.toFloat() / needed).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(12.dp))
    }
}
