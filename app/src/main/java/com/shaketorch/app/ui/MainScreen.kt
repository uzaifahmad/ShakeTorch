package com.shaketorch.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaketorch.app.R
import com.shaketorch.app.model.Sensitivity
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val Black = Color.Black
private val White = Color.White
private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
private val stars = List(36) { i ->
    val random = Random(i * 271 + 17)
    Star(random.nextFloat(), random.nextFloat(), .7f + random.nextFloat() * 1.7f, random.nextFloat() * 6.28f)
}

@Composable
fun MainScreen(viewModel: MainViewModel, hasNotificationPermission: Boolean, onRequestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.isServiceEnabled.collectAsState()
    val running by viewModel.serviceBound.collectAsState()
    val sensorReady by viewModel.sensorReady.collectAsState()
    val torchOn by viewModel.isTorchOn.collectAsState()
    val flashAvailable by viewModel.isFlashAvailable.collectAsState()
    val error by viewModel.torchErrorState.collectAsState()
    val sensitivity by viewModel.sensitivity.collectAsState()
    val batteryExempt by viewModel.isIgnoringBatteryOptimizations.collectAsState()
    val protection by viewModel.isBatteryProtectionEnabled.collectAsState()
    val vibration by viewModel.vibrateOnGesture.collectAsState()
    val entrance = remember { Animatable(0f) }
    var settingsOpen by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0L) }
    var gesturePulse by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(1500))
    }
    LaunchedEffect(Unit) {
        while (true) { delay(40); seconds += 40; gesturePulse = (gesturePulse * .92f).coerceAtLeast(0f) }
    }
    LaunchedEffect(viewModel) {
        viewModel.shakeEvents.collect { gesturePulse = 1f }
    }
    val fg = if (torchOn) Black else White
    val bg = if (torchOn) White else Black
    val listening = enabled && running && sensorReady
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val elapsed = seconds / 1000f
            stars.forEach { star ->
                val px = ((star.x + elapsed * .002f * (1f + star.r)) % 1f) * size.width
                val py = ((star.y + sin(elapsed * .24f + star.phase).toFloat() * .012f + 1f) % 1f) * size.height
                val twinkle = (.24f + .2f * sin(elapsed * 1.5f + star.phase).toFloat() + gesturePulse * .42f).coerceIn(.08f, .86f)
                drawCircle(fg.copy(alpha = twinkle), star.r * (1f + gesturePulse * .5f), Offset(px, py))
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SHAKE / TORCH", color = fg, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Light)
                TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "CLOSE" else "SETTINGS", color = fg, fontSize = 11.sp, letterSpacing = 2.sp) }
            }
            Spacer(Modifier.height(80.dp))
            // The entrance UFO's saucer glides down while its beam closes into the power ring.
            Canvas(Modifier.size(width = 240.dp, height = 205.dp).clickable(enabled = flashAvailable) { viewModel.toggleTorch() }
                .semantics { contentDescription = if (torchOn) "Turn flashlight off" else "Turn flashlight on" }) {
                val p = entrance.value
                val cx = size.width / 2f
                val cy = size.height * .54f
                val saucerY = cy - size.height * .22f - (1f - p) * size.height * .6f
                val saucerHalf = size.width * (.18f - p * .12f)
                drawLine(fg, Offset(cx - saucerHalf, saucerY), Offset(cx + saucerHalf, saucerY), 2f, cap = StrokeCap.Round)
                drawArc(fg, 185f, 170f, false, Offset(cx - saucerHalf, saucerY - 8f), Size(saucerHalf * 2, 16f), style = Stroke(1.7f))
                val beam = Path().apply {
                    moveTo(cx - saucerHalf * .5f, saucerY + 4f)
                    lineTo(cx - size.width * .22f * (1f - p), cy + size.height * .22f)
                    moveTo(cx + saucerHalf * .5f, saucerY + 4f)
                    lineTo(cx + size.width * .22f * (1f - p), cy + size.height * .22f)
                }
                drawPath(beam, fg.copy(alpha = (1f - p) * .55f), style = Stroke(1.6f))
                drawCircle(fg, radius = size.width * .25f, center = Offset(cx, cy), style = Stroke(width = 2.5f), alpha = p)
                drawLine(fg, Offset(cx, cy - size.width * .13f), Offset(cx, cy + size.width * .035f), 3f, cap = StrokeCap.Round, alpha = p)
                drawArc(fg, 132f, 276f, false, Offset(cx - size.width * .11f, cy - size.width * .075f), Size(size.width * .22f, size.width * .22f), style = Stroke(3f, cap = StrokeCap.Round), alpha = p)
            }
            Text(if (torchOn) "LIGHT ON" else "LIGHT OFF", color = fg, fontSize = 23.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text("Tap the circle to ${if (torchOn) "switch off" else "switch on"}", color = fg.copy(alpha = .65f), fontSize = 13.sp)
            if (error != null || !flashAvailable) {
                Spacer(Modifier.height(14.dp))
                Text(error ?: "This phone has no camera flash", color = fg, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
            Spacer(Modifier.height(76.dp))
            HorizontalDivider(color = fg.copy(alpha = .3f))
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SHAKE GESTURE", color = fg, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(when { !enabled -> "Off"; listening -> "Listening — including lock screen"; else -> "Starting or needs attention" }, color = fg.copy(alpha = .65f), fontSize = 12.sp)
                }
                Switch(checked = enabled, onCheckedChange = { viewModel.toggleService() }, colors = SwitchDefaults.colors(checkedTrackColor = fg, checkedThumbColor = bg, uncheckedTrackColor = bg, uncheckedThumbColor = fg, uncheckedBorderColor = fg))
            }
            HorizontalDivider(color = fg.copy(alpha = .3f))
            if (settingsOpen) {
                Spacer(Modifier.height(25.dp))
                SettingTitle("SENSITIVITY", fg)
                Text("Choose how deliberate the double-chop must be. Try it while holding your phone.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Sensitivity.LOW to "Easier", Sensitivity.MEDIUM to "Balanced", Sensitivity.HIGH to "Deliberate").forEach { (value, label) ->
                        OutlinedButton(onClick = { viewModel.setSensitivity(value) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (value == sensitivity) fg else bg, contentColor = if (value == sensitivity) bg else fg), border = androidx.compose.foundation.BorderStroke(1.dp, fg)) { Text(label, fontSize = 11.sp) }
                    }
                }
                Text("Pocket tip: Deliberate reduces accidental triggers. Pause gestures when carrying the phone loosely in a bag.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                Spacer(Modifier.height(26.dp))
                SettingTitle("LOCK SCREEN", fg)
                Text("No separate lock-screen permission is needed. Enable the gesture above, lock your phone, and try two deliberate chops.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                Spacer(Modifier.height(26.dp))
                SettingTitle("BACKGROUND & BATTERY", fg)
                Text(if (batteryExempt) "Battery optimization exemption granted" else "Android may limit continuous motion listening. Check battery settings if the locked-phone test fails.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                TextButton(onClick = { viewModel.openBatterySettings(context) }) { Text("OPEN BATTERY SETTINGS ↗", color = fg, fontSize = 12.sp) }
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                    Text("Notifications show listening status and a quick off action. Gesture mode can still run if you decline.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                    TextButton(onClick = onRequestNotificationPermission) { Text("ALLOW NOTIFICATIONS ↗", color = fg, fontSize = 12.sp) }
                }
                SettingTitle("PREFERENCES", fg)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic feedback", Modifier.weight(1f), color = fg)
                    Switch(checked = vibration, onCheckedChange = viewModel::setVibrateOnGesture)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Block gestures below 15% battery", Modifier.weight(1f), color = fg, fontSize = 13.sp)
                    Switch(checked = protection, onCheckedChange = viewModel::setBatteryProtection)
                }
                HorizontalDivider(color = fg.copy(alpha = .3f))
                Spacer(Modifier.height(22.dp))
                SettingTitle("ABOUT", fg)
                Text(stringResource(R.string.developer_credit), color = fg.copy(alpha = .6f), fontSize = 12.sp)
                Spacer(Modifier.height(32.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("TWO CHOPS. ONE LIGHT.", color = fg.copy(alpha = .55f), fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable private fun SettingTitle(title: String, fg: Color) {
    Text(title, color = fg, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp))
}
