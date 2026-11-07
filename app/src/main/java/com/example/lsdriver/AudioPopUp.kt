package com.example.lsdriver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
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
import coil.size.Size
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import java.io.File

@UnstableApi
class AudioPopUp : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var currentExoPlayer: ExoPlayer? = null
    private val serviceJob = SupervisorJob()
    private lateinit var audioManager: AudioManager
    private lateinit var audioDeviceCallback: AudioDeviceCallback
    private lateinit var powerReceiver: BroadcastReceiver

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingShowOverlayRunnable: Runnable? = null

    // Prevent re-triggering pop-up for already connected devices on initial service start/restart!
    private var isServiceJustCreated = true

    // Track active connection IDs to prevent duplicate/unexpected pop-ups when heartbeat ticks!
    private var activeDeviceId: Int? = null
    private var isPowerConnectedAlready = false

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

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        startForegroundNotification()
        registerAudioCallback()
        registerPowerReceiver()

        KeepAliveReceiver.scheduleNextHeartbeat(this)

        // After 1.2s of service startup, enable new plug-in detection!
        mainHandler.postDelayed({
            isServiceJustCreated = false
        }, 1200L)
    }

    private fun startForegroundNotification() {
        val channelId = "audio_popup_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Apeiron Audio Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Apeiron Service")
                .setContentText("Đang chạy ngầm Pop-up Tai nghe & Sạc...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Apeiron Service")
                .setContentText("Đang chạy ngầm Pop-up Tai nghe & Sạc...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
        }
        startForeground(3, notification)
    }

    private fun registerAudioCallback() {
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val enableHeadset = prefs.getBoolean("enable_headset_popup", true)
                if (!enableHeadset) return

                // IGNORE pre-existing devices reported immediately upon service startup!
                if (isServiceJustCreated) return

                for (device in addedDevices) {
                    val isBle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        isBle ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        
                        val deviceId = device.id
                        // Prevent re-triggering pop-up for an already-connected headset when heartbeat ticks!
                        if (deviceId == activeDeviceId) return
                        activeDeviceId = deviceId

                        val isBluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                          isBle ||
                                          device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

                        val detectedName = if (isBluetooth) {
                            device.productName?.toString()?.takeIf { it.isNotBlank() }
                        } else {
                            "Audio USB"
                        }

                        debouncedShowOverlay(detectedName, isBluetooth)
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                for (device in removedDevices) {
                    if (device.id == activeDeviceId) {
                        activeDeviceId = null
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun debouncedShowOverlay(detectedName: String?, isBluetooth: Boolean) {
        pendingShowOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            showOverlay(detectedName, isBluetooth)
        }
        pendingShowOverlayRunnable = runnable
        mainHandler.postDelayed(runnable, 150L)
    }

    private fun registerPowerReceiver() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        // Prevent re-triggering pop-up for an already-plugged charger when heartbeat ticks!
                        if (isPowerConnectedAlready) return
                        isPowerConnectedAlready = true

                        val prefs = context.getSharedPreferences("app_prefs", MODE_PRIVATE)
                        val enableCharging = prefs.getBoolean("enable_charging_popup", true)
                        if (!enableCharging) return

                        val serviceIntent = Intent(context, ChargingOverlayService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        isPowerConnectedAlready = false
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KeepAliveReceiver.scheduleNextHeartbeat(this)
        if (intent?.getBooleanExtra("test_manual", false) == true) {
            showOverlay(null, false)
        }
        return START_STICKY
    }

    private fun releasePlayer() {
        try {
            currentExoPlayer?.volume = 0f
            currentExoPlayer?.pause()
            currentExoPlayer?.stop()
            currentExoPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentExoPlayer = null
    }

    fun showOverlay(deviceNameArg: String?, isBluetooth: Boolean) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val enableHeadset = prefs.getBoolean("enable_headset_popup", true)
        if (!enableHeadset) return

        val appLanguage = prefs.getString("app_language", "vi") ?: "vi"
        val isEnglish = appLanguage == "en"

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val enableLandscape = prefs.getBoolean("enable_landscape_popup", true)
        if (isLandscape && !enableLandscape) return

        removeOverlay()

        val rawMediaUri = prefs.getString("custom_media_uri", "") ?: ""
        val customMediaUri = if (rawMediaUri.startsWith("file://") || rawMediaUri.startsWith("/")) {
            val path = if (rawMediaUri.startsWith("file://")) Uri.parse(rawMediaUri).path ?: "" else rawMediaUri
            if (File(path).exists()) rawMediaUri else ""
        } else {
            rawMediaUri
        }

        if (customMediaUri.isEmpty()) {
            releasePlayer()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val popupHeight = prefs.getInt("popup_card_height", 440)
        val popupWidthDp = prefs.getInt("popup_card_width", 0)
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

        val customSavedName = prefs.getString("device_custom_name", "Audio USB") ?: "Audio USB"
        val deviceName = if (isBluetooth && !deviceNameArg.isNullOrBlank()) {
            deviceNameArg
        } else {
            if (customSavedName.isBlank()) "Audio USB" else customSavedName
        }

        val headsetColorKey = prefs.getString("headset_popup_color", "blue") ?: "blue"
        val accentColor = when {
            headsetColorKey.startsWith("#") -> {
                try {
                    Color(android.graphics.Color.parseColor(headsetColorKey))
                } catch (_: Exception) {
                    Color(0xFF2962FF)
                }
            }
            headsetColorKey == "silver" -> Color(0xFFE0E0E0)
            headsetColorKey == "yellow" -> Color(0xFFFFEE58)
            headsetColorKey == "coral" -> Color(0xFFFF6F00)
            headsetColorKey == "turquoise" -> Color(0xFF00ACC1)
            headsetColorKey == "crimson" -> Color(0xFFC62828)
            headsetColorKey == "indigo" -> Color(0xFF3F51B5)
            headsetColorKey == "lime" -> Color(0xFFCDDC39)
            headsetColorKey == "navy" -> Color(0xFF1565C0)
            headsetColorKey == "amber" -> Color(0xFFFF8F00)
            headsetColorKey == "magenta" -> Color(0xFFD81B60)
            headsetColorKey == "red" -> Color(0xFFFF3D00)
            headsetColorKey == "gold" -> Color(0xFFFFD700)
            headsetColorKey == "teal" -> Color(0xFF00BFA5)
            headsetColorKey == "cyan" -> Color(0xFF00E5FF)
            headsetColorKey == "green" -> Color(0xFF00E676)
            headsetColorKey == "orange" -> Color(0xFFFF9100)
            headsetColorKey == "pink" -> Color(0xFFFF4081)
            headsetColorKey == "purple" -> Color(0xFF7C4DFF)
            else -> Color(0xFF2962FF)
        }

        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> isSystemDark
        }

        val customBgColor = prefs.getString("popup_bg_color", "") ?: ""
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
                val bgStylePref = prefs.getString("popup_bg_style", "default") ?: "default"
                when (bgStylePref) {
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

        val zoom = prefs.getFloat("image_zoom", 1.0f)
        val rawOffsetX = prefs.getFloat("image_offset_x", 0f)
        val rawOffsetY = prefs.getFloat("image_offset_y", 0f)
        val normalizedOffsetX = prefs.getFloat("image_offset_x_normalized", 0f)
        val normalizedOffsetY = prefs.getFloat("image_offset_y_normalized", 0f)

        val soundEnabled = prefs.getBoolean("video_sound_enabled", false)
        val videoVolume = prefs.getFloat("video_volume", 0.5f)

        val uri = if (customMediaUri.isNotEmpty()) {
            if (customMediaUri.startsWith("/")) Uri.fromFile(File(customMediaUri)) else Uri.parse(customMediaUri)
        } else null

        val isVideo = prefs.getString("custom_media_type", "image") == "video" || customMediaUri.lowercase().let { it.endsWith(".mp4") || it.contains("video") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AudioPopUp)
            setViewTreeSavedStateRegistryOwner(this@AudioPopUp)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setContent {
                var visible by remember { mutableStateOf(false) }
                var dismissDuration by remember { mutableLongStateOf(4500L) }
                
                var showUi by remember { mutableStateOf(!isVideo) }

                // PRE-RENDER PREPARATION: Track GPU frame readiness!
                var isVideoReady by remember { mutableStateOf(!isVideo) }

                val dragOffsetY = remember { Animatable(0f) }
                val dragOffsetX = remember { Animatable(0f) }
                val coroutineScope = rememberCoroutineScope()
                val velocityTracker = remember { VelocityTracker() }

                var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

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

                val exoPlayer = remember(customMediaUri) {
                    if (isVideo && uri != null) {
                        releasePlayer()
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
                    mainHandler.removeCallbacksAndMessages(null)
                    coroutineScope.launch {
                        dragOffsetY.animateTo(
                            targetValue = popupHeight * density * 1.2f,
                            animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                        )
                        releasePlayer()
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
                                            mainHandler.removeCallbacks(dismissRunnable)
                                            mainHandler.postDelayed(dismissRunnable, dur + 500L)
                                        }
                                    }
                                }
                                if (playbackState == Player.STATE_ENDED) {
                                    showUi = true
                                    mainHandler.removeCallbacks(dismissRunnable)
                                    mainHandler.post {
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

                    // 3/4 Video Time Monitor Loop
                    LaunchedEffect(player) {
                        while (isActive) {
                            val dur = player.duration
                            val pos = player.currentPosition
                            if (dur > 500L && pos > 0L) {
                                if (pos.toFloat() / dur.toFloat() >= 0.75f) {
                                    showUi = true
                                    break
                                }
                            }
                            delay(100)
                        }
                    }
                } else {
                    DisposableEffect(dismissDuration) {
                        mainHandler.postDelayed(dismissRunnable, dismissDuration)
                        onDispose { mainHandler.removeCallbacks(dismissRunnable) }
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

                val easeOutSmooth = CubicBezierEasing(0.18f, 0.85f, 0.25f, 1.0f)
                val cardShape = if (isLandscape) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (isLandscape) Alignment.CenterStart else Alignment.BottomCenter
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = if (isLandscape) {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(durationMillis = 600, easing = easeOutSmooth)
                            ) + fadeIn(animationSpec = tween(durationMillis = 400, easing = easeOutSmooth))
                        } else {
                            slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = tween(durationMillis = 600, easing = easeOutSmooth)
                            ) + fadeIn(animationSpec = tween(durationMillis = 400, easing = easeOutSmooth))
                        },
                        exit = fadeOut(animationSpec = tween(durationMillis = 150))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(popupHeight.dp)
                                .offset {
                                    if (isLandscape) IntOffset(dragOffsetX.value.roundToInt(), 0)
                                    else IntOffset(0, dragOffsetY.value.roundToInt())
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
                                                    mainHandler.removeCallbacks(dismissRunnable)
                                                    coroutineScope.launch {
                                                        dragOffsetX.animateTo(-popupHeight * density, tween(180))
                                                        releasePlayer()
                                                        removeOverlay()
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        dragOffsetX.animateTo(0f, spring(stiffness = Spring.StiffnessHigh))
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
                                                    mainHandler.removeCallbacks(dismissRunnable)
                                                    coroutineScope.launch {
                                                        dragOffsetY.animateTo(
                                                            targetValue = popupHeight * density * 1.2f,
                                                            animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)
                                                        )
                                                        releasePlayer()
                                                        removeOverlay()
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        dragOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessHigh))
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
                                .graphicsLayer { clip = true }
                                .background(
                                    Brush.verticalGradient(colors = listOf(bgColor1, bgColor2))
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
                                    color = bgColor2,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Normal
                                )
                            }

                            // Layer 1: TextureView for Video Rendering WITH 100% UNIFIED 1:1 SLIDE-IN!
                            if (customMediaUri.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(cardShape)
                                        .clickable { showUi = !showUi }
                                ) {
                                    val actualOffsetX = if (normalizedOffsetX != 0f) normalizedOffsetX else rawOffsetX
                                    val actualOffsetY = if (normalizedOffsetY != 0f) normalizedOffsetY else rawOffsetY

                                    if (isVideo && player != null) {
                                        AndroidView(
                                            factory = { ctx ->
                                                TextureView(ctx).apply {
                                                    isOpaque = false
                                                    textureViewRef = this
                                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                                            try {
                                                                val surface = Surface(surfaceTexture)
                                                                player.setVideoSurface(surface)
                                                                player.playWhenReady = true
                                                                if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                                                                    updateTextureViewMatrix(this@apply, player.videoSize.width.toFloat(), player.videoSize.height.toFloat())
                                                                }
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                            }
                                                        }
                                                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                                            if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                                                                updateTextureViewMatrix(this@apply, player.videoSize.width.toFloat(), player.videoSize.height.toFloat())
                                                            }
                                                        }
                                                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                                            try {
                                                                player.setVideoSurface(null)
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
                                                .size(Size.ORIGINAL) // 100% UNTRIMMED BITMAP!
                                                .precision(Precision.EXACT)
                                                .bitmapConfig(Bitmap.Config.ARGB_8888)
                                                .crossfade(true)
                                                .build(),
                                            imageLoader = imageLoader,
                                            contentDescription = "Custom Animation",
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

                            // Foreground Content - Appears at 3/4 Video Time or on Tap!
                            AnimatedVisibility(
                                visible = showUi,
                                enter = fadeIn(animationSpec = tween(300, easing = easeOutSmooth)) +
                                        slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(300, easing = easeOutSmooth)),
                                exit = fadeOut(animationSpec = tween(200)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(32.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isEnglish) "Connected $deviceName" else "Đã kết nối $deviceName",
                                            color = textColor,
                                            fontSize = 22.sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            letterSpacing = 0.4.sp,
                                            softWrap = false
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    mainHandler.removeCallbacks(dismissRunnable)
                                                    removeOverlay()
                                                    val intent = Intent(context, MainActivity::class.java).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = buttonBg
                                                ),
                                                shape = RoundedCornerShape(24.dp)
                                            ) {
                                                Text(
                                                    text = if (isEnglish) "Settings" else "Cài đặt",
                                                    color = textColor,
                                                    fontSize = 18.sp,
                                                    fontFamily = FontFamily.SansSerif,
                                                    fontWeight = FontWeight.SemiBold,
                                                    softWrap = false
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    mainHandler.removeCallbacks(dismissRunnable)
                                                    dismissWithAnimation()
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = accentColor
                                                ),
                                                shape = RoundedCornerShape(24.dp)
                                            ) {
                                                Text(
                                                    text = if (isEnglish) "Done" else "Xong",
                                                    color = Color.White,
                                                    fontSize = 18.sp,
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
        releasePlayer()
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
        releasePlayer()
        removeOverlay()
        serviceJob.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
