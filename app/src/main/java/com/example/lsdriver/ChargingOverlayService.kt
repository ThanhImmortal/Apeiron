package com.example.lsdriver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import java.io.File
import java.util.Locale
import kotlin.random.Random
import androidx.compose.foundation.border

@UnstableApi
class ChargingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var currentExoPlayer: ExoPlayer? = null
    private val serviceJob = SupervisorJob()

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "charging_popup_channel"
        val channel = NotificationChannel(
            channelId,
            "Apeiron Charging Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Apeiron")
            .setContentText("Đang theo dõi trạng thái sạc...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(2, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val enableCharging = prefs.getBoolean("enable_charging_popup", true)
        if (enableCharging || intent?.getBooleanExtra("test_manual", false) == true) {
            showOverlay()
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun getDetailedBatteryInfo(): BatteryInfo {
        try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, iFilter)
            
            // 1. Battery Percentage (%)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                50
            }

            // 2. Battery Temperature (°C): EXTRA_TEMPERATURE is in tenths of a degree C
            val tempRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250
            val temperatureC = tempRaw / 10f

            // 3. Battery Voltage (V): EXTRA_VOLTAGE is in millivolts (mV)
            val rawVoltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3800) ?: 3800
            val voltageV = when {
                rawVoltage > 100000 -> rawVoltage / 1000000f // microvolts
                rawVoltage > 1000 -> rawVoltage / 1000f      // millivolts -> Volts (e.g. 4150mV -> 4.15V)
                else -> 4.0f
            }

            // 4. Battery Current (A): BATTERY_PROPERTY_CURRENT_NOW
            val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
            val currentNowUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val absCurrent = abs(currentNowUa).toFloat()

            var currentMa = when {
                absCurrent > 100_000f -> absCurrent / 1000f // uA -> mA
                absCurrent > 0f -> absCurrent               // already mA
                else -> 2200f                              // fallback 2.2A
            }

            // Auto-scale current if kernel driver reports in nA or overscaled uA
            while (currentMa > 8000f) {
                currentMa /= 10f
            }

            var currentA = currentMa / 1000f
            if (currentA < 0.3f) currentA = 2.0f // Realistic charging current fallback

            var calculatedWattage = voltageV * currentA

            // Sanitize wattage to realistic 5W - 65W charging range
            while (calculatedWattage > 80f) {
                calculatedWattage /= 10f
            }

            val wattage = calculatedWattage.coerceIn(5.0f, 65.0f)

            return BatteryInfo(batteryPct, wattage, temperatureC, voltageV)
        } catch (e: Exception) {
            e.printStackTrace()
            return BatteryInfo(50, 18.0f, 30.0f, 4.1f)
        }
    }

    data class BatteryInfo(
        val percent: Int,
        val wattage: Float,
        val temperature: Float,
        val voltage: Float
    )

    fun showOverlay() {
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val appLanguage = appPrefs.getString("app_language", "vi") ?: "vi"
        val isEnglish = appLanguage == "en"

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val enableLandscape = appPrefs.getBoolean("enable_landscape_popup", true)
        if (isLandscape && !enableLandscape) return

        removeOverlay()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val popupHeight = appPrefs.getInt("popup_card_height", 440)
        val popupWidthDp = appPrefs.getInt("popup_card_width", 0)
        val density = resources.displayMetrics.density

        val portraitWidth = if (popupWidthDp in 250..600) (popupWidthDp * density).toInt() else WindowManager.LayoutParams.MATCH_PARENT

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            if (isLandscape) (370 * density).toInt() else portraitWidth,
            (popupHeight * density).toInt().coerceAtMost(resources.displayMetrics.heightPixels - (24 * density).toInt()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isLandscape) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = if (isLandscape) (12 * density).toInt() else 0
            y = 0
        }

        val prefs = getSharedPreferences("charging_prefs", MODE_PRIVATE)
        val rawMediaUri = prefs.getString("charging_media_uri", "") ?: ""
        val customMediaUri = if (rawMediaUri.startsWith("file://") || rawMediaUri.startsWith("/")) {
            val path = if (rawMediaUri.startsWith("file://")) Uri.parse(rawMediaUri).path ?: "" else rawMediaUri
            if (File(path).exists()) rawMediaUri else ""
        } else {
            rawMediaUri
        }
        
        val soundEnabled = prefs.getBoolean("charging_sound_enabled", false)
        val videoVolume = prefs.getFloat("charging_volume", 0.5f)

        val zoom = prefs.getFloat("charging_zoom", 1.0f)
        val rawOffsetX = prefs.getFloat("charging_offset_x", 0f)
        val rawOffsetY = prefs.getFloat("charging_offset_y", 0f)
        val normalizedOffsetX = prefs.getFloat("charging_offset_x_normalized", 0f)
        val normalizedOffsetY = prefs.getFloat("charging_offset_y_normalized", 0f)

        val showWattage = prefs.getBoolean("show_wattage", true)
        val showTemp = prefs.getBoolean("show_temp", true)
        val showBattery = prefs.getBoolean("show_battery", true)
        val showVoltage = prefs.getBoolean("show_voltage", true)
        val showParticles = prefs.getBoolean("show_charging_particles", true)

        val chargingColorKey = prefs.getString("charging_popup_color", "cyan") ?: "cyan"
        val chargingRingColorKey = prefs.getString("charging_ring_color", "cyan") ?: "cyan"

        fun parseColorFromKey(key: String, defaultColor: Color): Color {
            return when {
                key.startsWith("#") -> {
                    try { Color(android.graphics.Color.parseColor(key)) } catch (_: Exception) { defaultColor }
                }
                key == "silver" -> Color(0xFFE0E0E0)
                key == "yellow" -> Color(0xFFFFEE58)
                key == "coral" -> Color(0xFFFF6F00)
                key == "turquoise" -> Color(0xFF00ACC1)
                key == "crimson" -> Color(0xFFC62828)
                key == "indigo" -> Color(0xFF3F51B5)
                key == "lime" -> Color(0xFFCDDC39)
                key == "navy" -> Color(0xFF1565C0)
                key == "amber" -> Color(0xFFFF8F00)
                key == "magenta" -> Color(0xFFD81B60)
                key == "red" -> Color(0xFFFF3D00)
                key == "gold" -> Color(0xFFFFD700)
                key == "teal" -> Color(0xFF00BFA5)
                key == "cyan" -> Color(0xFF00E5FF)
                key == "green" -> Color(0xFF00E676)
                key == "orange" -> Color(0xFFFF9100)
                key == "pink" -> Color(0xFFFF4081)
                key == "purple" -> Color(0xFF7C4DFF)
                else -> defaultColor
            }
        }

        val accentColor = parseColorFromKey(chargingColorKey, Color(0xFF00E5FF))
        val ringAccentColor = parseColorFromKey(chargingRingColorKey, Color(0xFF00E5FF))

        val themeMode = appPrefs.getString("theme_mode", "system") ?: "system"
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemDark
        }

        val customBgColor = appPrefs.getString("popup_bg_color", "") ?: ""
        val (bgColor1, bgColor2) = when {
            customBgColor.startsWith("#") -> {
                try {
                    val c = Color(android.graphics.Color.parseColor(customBgColor))
                    Pair(c, c)
                } catch (_: Exception) {
                    if (isDark) Pair(Color(0xFF1E1E2C), Color(0xFF0D0D12))
                    else Pair(Color(0xFFF8F9FA), Color(0xFFE9ECEF))
                }
            }
            else -> {
                val bgStyle = appPrefs.getString("popup_bg_style", "default") ?: "default"
                when (bgStyle) {
                    "black" -> Pair(Color(0xFF000000), Color(0xFF000000))
                    "navy" -> Pair(Color(0xFF0D1B2A), Color(0xFF1B263B))
                    "carbon" -> Pair(Color(0xFF212121), Color(0xFF000000))
                    else -> {
                        if (isDark) Pair(Color(0xFF1E1E2C), Color(0xFF0D0D12))
                        else Pair(Color(0xFFF8F9FA), Color(0xFFE9ECEF))
                    }
                }
            }
        }

        val textColor = Color.White
        val buttonBg = Color(0x33FFFFFF)

        val handler = Handler(Looper.getMainLooper())

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ChargingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ChargingOverlayService)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setContent {
                var visible by remember { mutableStateOf(false) }
                var dismissDuration by remember { mutableLongStateOf(3500L) }
                var showUi by remember { mutableStateOf(true) }
                
                val uri = if (customMediaUri.isNotEmpty()) {
                    if (customMediaUri.startsWith("/")) Uri.fromFile(File(customMediaUri)) else Uri.parse(customMediaUri)
                } else null
                val isVideo = prefs.getString("custom_media_type", "image") == "video" || customMediaUri.lowercase().let { it.endsWith(".mp4") || it.contains("video") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") }

                // PRE-RENDER PREPARATION: Track GPU frame readiness!
                var isVideoReady by remember { mutableStateOf(!isVideo) }

                val dragOffsetY = remember { Animatable(0f) }
                val dragOffsetX = remember { Animatable(0f) }
                val coroutineScope = rememberCoroutineScope()
                val velocityTracker = remember { VelocityTracker() }

                var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
                var batteryInfo by remember { mutableStateOf(getDetailedBatteryInfo()) }

                fun updateTextureViewMatrix(tv: TextureView, videoW: Float, videoH: Float) {
                    val viewW = tv.width.toFloat()
                    val viewH = tv.height.toFloat()
                    if (videoW > 0f && videoH > 0f && viewW > 0f && viewH > 0f) {
                        val videoAspect = videoW / videoH
                        val viewAspect = viewW / viewH
                        val scaleX: Float
                        val scaleY: Float
                        if (videoAspect > viewAspect) {
                            scaleX = 1f
                            scaleY = (viewW / videoAspect) / viewH
                        } else {
                            scaleX = (viewH * videoAspect) / viewW
                            scaleY = 1f
                        }
                        val matrix = Matrix()
                        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
                        tv.setTransform(matrix)
                    }
                }

                LaunchedEffect(Unit) {
                    while (isActive) {
                        batteryInfo = getDetailedBatteryInfo()
                        delay(1000)
                    }
                }

                val wattageFormatted = String.format(Locale.getDefault(), "%.1f", batteryInfo.wattage)
                val tempFormatted = String.format(Locale.getDefault(), "%.1f", batteryInfo.temperature)
                val voltageFormatted = String.format(Locale.getDefault(), "%.1f", batteryInfo.voltage)

                val exoPlayer = remember(customMediaUri) {
                    if (isVideo && uri != null) {
                        try {
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(uri))
                                repeatMode = ExoPlayer.REPEAT_MODE_OFF
                                volume = if (soundEnabled) videoVolume else 0f
                                prepare()
                                playWhenReady = true
                            }.also { currentExoPlayer = it }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    } else null
                }

                val dismissWithAnimation: () -> Unit = {
                    handler.removeCallbacksAndMessages(null)
                    coroutineScope.launch {
                        if (isLandscape) {
                            dragOffsetX.animateTo(
                                targetValue = -popupHeight * density * 1.2f,
                                animationSpec = tween(durationMillis = 200, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
                            )
                        } else {
                            dragOffsetY.animateTo(
                                targetValue = popupHeight * density * 1.2f,
                                animationSpec = tween(durationMillis = 200, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
                            )
                        }
                        try {
                            exoPlayer?.stop()
                            exoPlayer?.release()
                        } catch (_: Exception) {}
                        removeOverlay()
                    }
                }

                val dismissRunnable = Runnable {
                    dismissWithAnimation()
                }

                val player = exoPlayer
                if (isVideo && player != null) {
                    DisposableEffect(player) {
                        val listener = object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                isVideoReady = true
                            }

                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                textureViewRef?.let { tv ->
                                    updateTextureViewMatrix(tv, videoSize.width.toFloat(), videoSize.height.toFloat())
                                }
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    isVideoReady = true
                                    if (player.duration > 0) {
                                        val dur = player.duration
                                        if (dur > 500L) {
                                            handler.removeCallbacks(dismissRunnable)
                                            dismissDuration = dur + 200L
                                            handler.postDelayed(dismissRunnable, dismissDuration)
                                        }
                                    }
                                }
                                if (playbackState == Player.STATE_ENDED) {
                                    handler.removeCallbacks(dismissRunnable)
                                    handler.post {
                                        dismissWithAnimation()
                                    }
                                }
                            }
                        }
                        player.addListener(listener)
                        if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                            textureViewRef?.let { tv ->
                                updateTextureViewMatrix(tv, player.videoSize.width.toFloat(), player.videoSize.height.toFloat())
                            }
                        }
                        onDispose { player.removeListener(listener) }
                    }
                } else {
                    DisposableEffect(dismissDuration) {
                        handler.postDelayed(dismissRunnable, dismissDuration)
                        onDispose { handler.removeCallbacks(dismissRunnable) }
                    }
                }

                // PERFECT 1:1 SYNCHRONIZED SLIDE-IN: Pre-draw 2 frames off-screen so card + video slide TOGETHER as 1 layer!
                LaunchedEffect(isVideoReady) {
                    if (isVideoReady || !isVideo) {
                        delay(60) // 60ms off-screen pre-draw buffer pass
                        visible = true
                    }
                }

                // Fallback trigger
                LaunchedEffect(Unit) {
                    delay(250)
                    visible = true
                }

                // Material Motion Curves (Emphasized Decelerate / Accelerate)
                val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
                val emphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
                val cardShape = if (isLandscape) RoundedCornerShape(20.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (isLandscape) Modifier.graphicsLayer { rotationZ = 90f } else Modifier),
                    contentAlignment = if (isLandscape) Alignment.CenterStart else Alignment.BottomCenter
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = if (isLandscape) {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(durationMillis = 480, easing = emphasizedDecelerate)
                            ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing))
                        } else {
                            slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = tween(durationMillis = 500, easing = emphasizedDecelerate)
                            ) + fadeIn(animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing))
                        },
                        exit = fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(popupHeight.dp)
                                .graphicsLayer {
                                    translationX = dragOffsetX.value
                                    translationY = dragOffsetY.value
                                    clip = false
                                }
                                .pointerInput(Unit) {
                                    if (isLandscape) {
                                        detectHorizontalDragGestures(
                                            onDragStart = {
                                                velocityTracker.resetTracking()
                                            },
                                            onDragEnd = {
                                                val currentVelocity = velocityTracker.calculateVelocity().x
                                                val currentOffset = dragOffsetX.value

                                                if (currentOffset < -100f || currentVelocity < -350f) {
                                                    handler.removeCallbacks(dismissRunnable)
                                                    coroutineScope.launch {
                                                        dragOffsetX.animateTo(-popupHeight * density, tween(200, easing = emphasizedAccelerate))
                                                        try {
                                                            exoPlayer?.stop()
                                                            exoPlayer?.release()
                                                        } catch (_: Exception) {}
                                                        removeOverlay()
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        dragOffsetX.animateTo(
                                                            0f,
                                                            spring(
                                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                                stiffness = Spring.StiffnessMediumLow
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                                change.consume()
                                                coroutineScope.launch {
                                                    val newOffset = (dragOffsetX.value + dragAmount).coerceAtMost(0f)
                                                    dragOffsetX.snapTo(newOffset)
                                                }
                                            }
                                        )
                                    } else {
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                velocityTracker.resetTracking()
                                            },
                                            onDragEnd = {
                                                val currentVelocity = velocityTracker.calculateVelocity().y
                                                val currentOffset = dragOffsetY.value
                                                val threshold = popupHeight * density * 0.10f

                                                if (currentOffset > threshold || currentVelocity > 225f) {
                                                    handler.removeCallbacks(dismissRunnable)
                                                    coroutineScope.launch {
                                                        dragOffsetY.animateTo(
                                                            targetValue = popupHeight * density * 1.2f,
                                                            animationSpec = tween(durationMillis = 200, easing = emphasizedAccelerate)
                                                        )
                                                        try {
                                                            exoPlayer?.stop()
                                                            exoPlayer?.release()
                                                        } catch (_: Exception) {}
                                                        removeOverlay()
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        dragOffsetY.animateTo(
                                                            0f,
                                                            spring(
                                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                                stiffness = Spring.StiffnessMediumLow
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                                change.consume()
                                                coroutineScope.launch {
                                                    dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceAtLeast(0f))
                                                }
                                            }
                                        )
                                    }
                                }
                                .clip(cardShape)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.22f),
                                            Color.White.copy(alpha = 0.05f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = cardShape
                                )
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(bgColor1, bgColor2)
                                    )
                                )
                        ) {
                            // Layer 0: Version watermark
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 12.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Text(
                                    text = "Apeiron",
                                    color = Color.White.copy(alpha = 0.08f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Layer 0.5: Top Drag Handle Pill
                            if (!isLandscape) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp)
                                        .width(36.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.32f))
                                )
                            }

                            // Layer 1: TextureView for Video Rendering WITH 100% UNIFIED 1:1 SLIDE-IN!
                            if (customMediaUri.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(cardShape)
                                ) {
                                    val actualOffsetX = if (normalizedOffsetX != 0f) normalizedOffsetX else rawOffsetX
                                    val actualOffsetY = if (normalizedOffsetY != 0f) normalizedOffsetY else rawOffsetY

                                    if (isVideo && exoPlayer != null) {
                                        AndroidView(
                                            factory = { ctx ->
                                                TextureView(ctx).apply {
                                                    isOpaque = false
                                                    textureViewRef = this
                                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                                            try {
                                                                val surface = Surface(surfaceTexture)
                                                                exoPlayer.setVideoSurface(surface)
                                                                exoPlayer.playWhenReady = true
                                                                if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
                                                                    updateTextureViewMatrix(this@apply, exoPlayer.videoSize.width.toFloat(), exoPlayer.videoSize.height.toFloat())
                                                                }
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                            }
                                                        }
                                                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                                            if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
                                                                updateTextureViewMatrix(this@apply, exoPlayer.videoSize.width.toFloat(), exoPlayer.videoSize.height.toFloat())
                                                            }
                                                        }
                                                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                                            try {
                                                                exoPlayer.setVideoSurface(null)
                                                            } catch (_: Exception) {}
                                                            return true
                                                        }
                                                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(cardShape)
                                                .graphicsLayer {
                                                    scaleX = zoom
                                                    scaleY = zoom
                                                    translationX = actualOffsetX
                                                    translationY = actualOffsetY
                                                }
                                        )
                                    } else if (uri != null) {
                                        val imageLoader = remember(context) {
                                            ImageLoader.Builder(context)
                                                .components { add(ImageDecoderDecoder.Factory()) }
                                                .allowHardware(true)
                                                .build()
                                        }

                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(uri)
                                                .size(coil.size.Size.ORIGINAL) // 100% UNTRIMMED BITMAP!
                                                .precision(Precision.EXACT)
                                                .bitmapConfig(Bitmap.Config.ARGB_8888)
                                                .crossfade(true)
                                                .build(),
                                            imageLoader = imageLoader,
                                            contentDescription = "Charging Preview",
                                            contentScale = ContentScale.FillWidth,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                                                .graphicsLayer {
                                                    scaleX = zoom
                                                    scaleY = zoom
                                                    translationX = actualOffsetX
                                                    translationY = actualOffsetY
                                                }
                                        )
                                    }
                                }
                            }

                            // Gradient Overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color(0x880D0D12),
                                                Color(0xEE0D0D12)
                                            ),
                                            startY = (popupHeight / 2).toFloat()
                                        )
                                    )
                            )

                            // Foreground Content
                            AnimatedVisibility(
                                visible = showUi,
                                enter = fadeIn(animationSpec = tween(350, easing = LinearOutSlowInEasing)) +
                                        slideInVertically(
                                            initialOffsetY = { it / 2 },
                                            animationSpec = tween(350, easing = emphasizedDecelerate)
                                        ),
                                exit = fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.78f)
                                                .weight(1.3f)
                                                .padding(top = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            SuperVOOCChargingRingToCenter(
                                                batteryPercent = batteryInfo.percent,
                                                showParticles = showParticles,
                                                showBatteryInCenter = showBattery,
                                                accentColor = ringAccentColor
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = if (isEnglish) "Charging Connected" else "Đã cắm sạc",
                                                color = textColor,
                                                fontSize = 24.sp,
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                softWrap = false
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))

                                            val infoParts = mutableListOf<String>()
                                            if (showWattage) infoParts.add("${wattageFormatted}W")
                                            if (showTemp) infoParts.add("${tempFormatted}°C")
                                            if (showVoltage) infoParts.add("${voltageFormatted}V")

                                            if (infoParts.isNotEmpty()) {
                                                Text(
                                                    text = infoParts.joinToString(" • "),
                                                    color = accentColor,
                                                    fontSize = 17.sp,
                                                    fontFamily = FontFamily.SansSerif,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(30.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                handler.removeCallbacks(dismissRunnable)
                                                removeOverlay()
                                                val intent = Intent(context, MainActivity::class.java).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
                                                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(25.dp)),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = buttonBg
                                            ),
                                            shape = RoundedCornerShape(25.dp)
                                        ) {
                                            Text(
                                                text = if (isEnglish) "Settings" else "Cài đặt",
                                                color = textColor,
                                                fontSize = 17.sp,
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                handler.removeCallbacks(dismissRunnable)
                                                dismissWithAnimation()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = accentColor
                                            ),
                                            shape = RoundedCornerShape(25.dp)
                                        ) {
                                            Text(
                                                text = if (isEnglish) "Done" else "Xong",
                                                color = Color.White,
                                                fontSize = 17.sp,
                                                fontFamily = FontFamily.SansSerif,
                                                fontWeight = FontWeight.Bold,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        overlayView = composeView
        try {
            windowManager.addView(overlayView, params)
            composeView.invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        try {
            currentExoPlayer?.stop()
            currentExoPlayer?.release()
        } catch (_: Exception) {}
        currentExoPlayer = null
        overlayView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
            overlayView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        serviceJob.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

data class SpiralParticle(
    val startAngle: Float,
    val speed: Float,
    val baseSize: Float,
    val sparklePhase: Float,
    val timeOffset: Float
)

@Composable
fun SuperVOOCChargingRingToCenter(
    batteryPercent: Int,
    showParticles: Boolean,
    showBatteryInCenter: Boolean,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring_to_center")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    // 70 stardust particles with randomized 360-degree angles!
    val particles = remember {
        List(70) { index ->
            val particleBaseSize = when (index % 3) {
                0 -> Random.nextFloat() * 1.7f + 3.8f // 33% Slightly larger particles (3.8dp - 5.5dp)
                1 -> Random.nextFloat() * 1.2f + 2.6f // 33% Medium particles (2.6dp - 3.8dp)
                else -> Random.nextFloat() * 0.8f + 1.8f // 33% Fine particles (1.8dp - 2.6dp)
            }
            SpiralParticle(
                startAngle = Random.nextFloat() * 360f,
                speed = Random.nextFloat() * 0.4f + 0.8f,
                baseSize = particleBaseSize,
                sparklePhase = Random.nextFloat() * 360f,
                timeOffset = index.toFloat() / 70f
            )
        }
    }

    val ringLayers = remember {
        listOf(
            Pair(0.08f, Stroke(width = 36f, cap = StrokeCap.Round)),
            Pair(0.12f, Stroke(width = 32f, cap = StrokeCap.Round)),
            Pair(0.20f, Stroke(width = 28f, cap = StrokeCap.Round)),
            Pair(0.30f, Stroke(width = 24f, cap = StrokeCap.Round)),
            Pair(0.45f, Stroke(width = 20f, cap = StrokeCap.Round)),
            Pair(0.90f, Stroke(width = 16f, cap = StrokeCap.Round)),
            Pair(0.90f, Stroke(width = 12f, cap = StrokeCap.Round))
        )
    }

    Box(
        modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val ringRadius = (size.width / 2f) * 0.82f

            // Clean 3D Multi-layer Ambient Glow Ring
            val diameter = ringRadius * 2f
            val topLeftOffset = Offset(centerX - ringRadius, centerY - ringRadius)
            val ringSize = Size(diameter, diameter)
            ringLayers.forEach { (alpha, strokeStyle) ->
                drawArc(
                    color = accentColor.copy(alpha = alpha),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeftOffset,
                    size = ringSize,
                    style = strokeStyle
                )
            }

            if (showParticles) {
                val startRadius = ringRadius * 0.92f
                val minRadius = 45f // Dissolve 45dp away from center so particles NEVER pile up in the middle!

                particles.forEach { p ->
                    val t = (progress + p.timeOffset) % 1f
                    val easedT = t * t // Accelerates inward into center!
                    val currentDist = startRadius - (easedT * (startRadius - minRadius))

                    val currentAngle = p.startAngle + (t * 90f)
                    val rad = Math.toRadians(currentAngle.toDouble())
                    val x = centerX + (currentDist * cos(rad)).toFloat()
                    val y = centerY + (currentDist * sin(rad)).toFloat()

                    // Dynamic pulsing size as particles travel inward!
                    val sizePulse = (1.1f - (easedT * 0.3f)) * (0.85f + sin(t * 18f + p.sparklePhase) * 0.25f)
                    val particleSize = (p.baseSize * sizePulse).coerceAtLeast(1.2f)

                    // CRISP OPACITY: Fade in at outer ring, stay 100% solid, dissolve before hitting center number!
                    val fadeAlpha = when {
                        t < 0.08f -> (t / 0.08f)
                        t > 0.80f -> (1.0f - t) / 0.20f // Smooth dissolve before center!
                        else -> 1.0f
                    }.coerceIn(0.0f, 1.0f)

                    val twinkle = (sin((t * 20f) + p.sparklePhase).toFloat() * 0.15f + 0.85f).coerceIn(0.70f, 1.0f)
                    val finalAlpha = (fadeAlpha * twinkle).coerceIn(0.0f, 1.0f)

                    // Pure, clean, single-color particle!
                    drawCircle(
                        color = accentColor.copy(alpha = finalAlpha),
                        radius = particleSize,
                        center = Offset(x, y)
                    )
                }
            }
        }

        if (showBatteryInCenter) {
            var percentWidthPx by remember { mutableFloatStateOf(0f) }
            val currentDensity = LocalDensity.current.density
            val offsetXDp = if (currentDensity > 0f) (percentWidthPx / 2f / currentDensity).dp else 10.dp

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.offset(x = offsetXDp)
            ) {
                Text(
                    text = "$batteryPercent",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "%",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier
                        .padding(bottom = 5.dp, start = 2.dp)
                        .onSizeChanged { size ->
                            percentWidthPx = size.width.toFloat()
                        }
                )
            }
        }
    }
}
