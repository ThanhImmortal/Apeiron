package com.example.lsdriver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.example.lsdriver.ui.theme.SPoPDriverTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"

        if (Settings.canDrawOverlays(this)) {
            val serviceIntent = Intent(this, AudioPopUp::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        KeepAliveReceiver.scheduleNextHeartbeat(this)

        setContent {
            var currentTheme by remember { mutableStateOf(themeMode) }
            SPoPDriverTheme(themeMode = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(onThemeChanged = { currentTheme = it })
                }
            }
        }
    }
}

fun copyUriToInternalStorage(context: Context, uri: Uri, fileName: String): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.filesDir, fileName)
        val outputStream = FileOutputStream(file)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onThemeChanged: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val chargingPrefs = remember { context.getSharedPreferences("charging_prefs", Context.MODE_PRIVATE) }
    var selectedTab by remember { mutableIntStateOf(0) }

    var appLanguage by remember {
        mutableStateOf(prefs.getString("app_language", "vi") ?: "vi")
    }
    val isEnglish = appLanguage == "en"

    // TAB NAVIGATION BACKSTACK HISTORY (Back Gesture / Back Button Memory)
    val tabBackStack = remember { mutableStateListOf(0) }

    fun selectTab(newTab: Int) {
        if (selectedTab != newTab) {
            tabBackStack.remove(newTab)
            tabBackStack.add(newTab)
            selectedTab = newTab
        }
    }

    // SYSTEM BACK HANDLER / SWIPE BACK GESTURE MEMORY
    BackHandler(enabled = tabBackStack.size > 1) {
        if (tabBackStack.size > 1) {
            tabBackStack.removeAt(tabBackStack.size - 1)
            selectedTab = tabBackStack.last()
        }
    }

    var customMediaUri by remember {
        mutableStateOf(prefs.getString("custom_media_uri", "") ?: "")
    }

    var chargingMediaUri by remember {
        mutableStateOf(chargingPrefs.getString("charging_media_uri", "") ?: "")
    }

    var deviceName by remember {
        mutableStateOf(prefs.getString("device_custom_name", "Audio USB") ?: "Audio USB")
    }

    var popupCardHeight by remember {
        mutableIntStateOf(prefs.getInt("popup_card_height", 440))
    }

    var soundEnabled by remember {
        mutableStateOf(prefs.getBoolean("video_sound_enabled", false))
    }
    var videoVolume by remember {
        mutableFloatStateOf(prefs.getFloat("video_volume", 0.5f))
    }

    var chargingSoundEnabled by remember {
        mutableStateOf(chargingPrefs.getBoolean("charging_sound_enabled", false))
    }
    var chargingVolume by remember {
        mutableFloatStateOf(chargingPrefs.getFloat("charging_volume", 0.5f))
    }

    var themeMode by remember {
        mutableStateOf(prefs.getString("theme_mode", "system") ?: "system")
    }

    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
        }
    }

    Scaffold(
        topBar = {
            // COMPACT ULTRA SPACE-SAVING TOP HEADER WITH STATUS BAR INSETS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Apeiron",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "STUDIO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomBar = {
            // TAB ROW PLACED AT THE BOTTOM OF SCREEN WITH BACKSTACK MEMORY INTEGRATION!
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .border(
                        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    )
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectTab(0) },
                        interactionSource = remember { MutableInteractionSource() },
                        text = { Text(if (isEnglish) "Headset Pop-up" else "Pop-up Tai nghe", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectTab(1) },
                        interactionSource = remember { MutableInteractionSource() },
                        text = { Text(if (isEnglish) "Charging Pop-up" else "Pop-up Sạc pin", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectTab(2) },
                        interactionSource = remember { MutableInteractionSource() },
                        text = { Text(if (isEnglish) "Settings & Presets" else "Cài đặt & Preset", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // INSTANT, CLEAN, ZERO-SLIDE HIGH-PERFORMANCE TAB SWITCHING!
            when (selectedTab) {
                0 -> PopupMediaTab(
                    context = context,
                    prefs = prefs,
                    initialMediaUri = customMediaUri,
                    deviceName = deviceName,
                    popupCardHeight = popupCardHeight,
                    soundEnabled = soundEnabled,
                    videoVolume = videoVolume,
                    isEnglish = isEnglish,
                    hasOverlayPermission = hasOverlayPermission,
                    onSoundEnabledChange = {
                        soundEnabled = it
                        prefs.edit().putBoolean("video_sound_enabled", it).apply()
                    },
                    onVolumeChange = {
                        videoVolume = it
                        prefs.edit().putFloat("video_volume", it).apply()
                    },
                    onOverlayPermissionClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                )
                1 -> ChargingMediaTab(
                    context = context,
                    chargingPrefs = chargingPrefs,
                    initialMediaUri = chargingMediaUri,
                    popupCardHeight = popupCardHeight,
                    soundEnabled = chargingSoundEnabled,
                    videoVolume = chargingVolume,
                    isEnglish = isEnglish,
                    onSoundEnabledChange = {
                        chargingSoundEnabled = it
                        chargingPrefs.edit().putBoolean("charging_sound_enabled", it).apply()
                    },
                    onVolumeChange = {
                        chargingVolume = it
                        chargingPrefs.edit().putFloat("charging_volume", it).apply()
                    }
                )
                2 -> SettingsPresetTab(
                    context = context,
                    prefs = prefs,
                    chargingPrefs = chargingPrefs,
                    deviceName = deviceName,
                    onDeviceNameChange = { deviceName = it },
                    popupCardHeight = popupCardHeight,
                    onPopupCardHeightChange = { newHeight ->
                        popupCardHeight = newHeight
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString("theme_mode", newMode).apply()
                        onThemeChanged(newMode)
                    },
                    appLanguage = appLanguage,
                    onLanguageChange = { newLang ->
                        appLanguage = newLang
                        prefs.edit().putString("app_language", newLang).apply()
                    },
                    hasOverlayPermission = hasOverlayPermission,
                    onPresetLoaded = {
                        customMediaUri = prefs.getString("custom_media_uri", "") ?: ""
                        chargingMediaUri = chargingPrefs.getString("charging_media_uri", "") ?: ""
                        deviceName = prefs.getString("device_custom_name", "Audio USB") ?: "Audio USB"
                        popupCardHeight = prefs.getInt("popup_card_height", 440)
                        soundEnabled = prefs.getBoolean("video_sound_enabled", false)
                        videoVolume = prefs.getFloat("video_volume", 0.5f)
                        chargingSoundEnabled = chargingPrefs.getBoolean("charging_sound_enabled", false)
                        chargingVolume = chargingPrefs.getFloat("charging_volume", 0.5f)
                    },
                    onOverlayPermissionClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PopupMediaTab(
    context: Context,
    prefs: SharedPreferences,
    initialMediaUri: String,
    deviceName: String,
    popupCardHeight: Int,
    soundEnabled: Boolean,
    videoVolume: Float,
    isEnglish: Boolean,
    hasOverlayPermission: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOverlayPermissionClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()

    val popupCardWidth = prefs.getInt("popup_card_width", 0)
    val effectiveWidthDp = if (popupCardWidth in 250..600) popupCardWidth.toFloat() else screenWidthDp

    val previewScaleFactor = 0.72f
    val previewWidthDp = (effectiveWidthDp * previewScaleFactor).dp
    val previewHeightDp = (popupCardHeight * previewScaleFactor).dp

    val headsetColorKey = prefs.getString("headset_popup_color", "blue") ?: "blue"
    val accentColor = when {
        headsetColorKey.startsWith("#") -> {
            try { Color(android.graphics.Color.parseColor(headsetColorKey)) } catch (_: Exception) { Color(0xFF2962FF) }
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
    val isSystemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }

    val customBgColor = prefs.getString("popup_bg_color", "") ?: ""
    val (_, bgColor2) = when {
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

    // Direct Pre-populated State to eliminate Frame 1 empty state flicker!
    var stagedUri by remember(initialMediaUri) { mutableStateOf(initialMediaUri.ifEmpty { prefs.getString("custom_media_uri", "") ?: "" }) }
    var stagedType by remember { mutableStateOf(prefs.getString("custom_media_type", "image") ?: "image") }
    var stagedZoom by remember { mutableFloatStateOf(prefs.getFloat("image_zoom", 1.0f)) }
    var stagedOffsetX by remember { mutableFloatStateOf(prefs.getFloat("image_offset_x", 0f)) }
    var stagedOffsetY by remember { mutableFloatStateOf(prefs.getFloat("image_offset_y", 0f)) }
    
    // DEFAULT IS STOPPED/PAUSED! Only plays when user taps "Phát"!
    var isPlaying by remember { mutableStateOf(false) }

    var exoPlayerInstance by remember { mutableStateOf<ExoPlayer?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val mimeType = context.contentResolver.getType(sourceUri)
            val isVideo = mimeType?.startsWith("video/") == true || sourceUri.toString().lowercase().let { it.contains("video") || it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") }
            stagedType = if (isVideo) "video" else "image"

            val fileName = if (isVideo) "headset_media_file.mp4" else "headset_media_file.jpg"
            coroutineScope.launch(Dispatchers.IO) {
                val localFile = copyUriToInternalStorage(context, sourceUri, fileName)
                val uriString = if (localFile != null) {
                    Uri.fromFile(localFile).toString()
                } else {
                    sourceUri.toString()
                }
                withContext(Dispatchers.Main) {
                    stagedUri = uriString
                    stagedZoom = 1.0f
                    stagedOffsetX = 0f
                    stagedOffsetY = 0f
                    isPlaying = false
                }
            }
        }
    }

    val isStagedVideo = remember(stagedUri, stagedType) {
        stagedUri.isNotEmpty() && (
            stagedType == "video" ||
            stagedUri.lowercase().let { it.contains("video") || it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") || it.endsWith(".3gp") }
        )
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(ImageDecoderDecoder.Factory()) }
            .allowHardware(true)
            .build()
    }

    val imageRequest = remember(stagedUri, context) {
        if (stagedUri.isNotEmpty() && !isStagedVideo) {
            ImageRequest.Builder(context)
                .data(Uri.parse(stagedUri))
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .crossfade(false)
                .build()
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!hasOverlayPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEnglish) "Please enable Display over other apps permission" else "Vui lòng bật Cho phép hiển thị trên các ứng dụng khác",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onOverlayPermissionClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isEnglish) "Go to Settings" else "Đi tới Cài đặt")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnglish) "Preview" else "Xem trước",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (isEnglish) "Reset" else "Đặt lại",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        stagedZoom = 1.0f
                        stagedOffsetX = 0f
                        stagedOffsetY = 0f
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Preview Box - RELIABLE PLAYERVIEW (0% CONTROLLERS, 0% REWIND ICONS, DECODES FRAME 1 THEN PAUSES!)
        Box(
            modifier = Modifier
                .width(previewWidthDp)
                .height(previewHeightDp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                .background(Color(0xFF0D0D12))
                .pointerInput(stagedUri) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val newZoom = (stagedZoom * zoomChange).coerceIn(0.5f, 5.0f)
                        stagedZoom = newZoom
                        val newX = (stagedOffsetX + pan.x).coerceIn(-2500f, 2500f)
                        val newY = (stagedOffsetY + pan.y).coerceIn(-2500f, 2500f)
                        stagedOffsetX = newX
                        stagedOffsetY = newY
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (stagedUri.isNotEmpty()) {
                val uri = Uri.parse(stagedUri)

                if (isStagedVideo) {
                    val exoPlayer = remember(stagedUri) {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(uri))
                            repeatMode = ExoPlayer.REPEAT_MODE_ONE
                            volume = if (soundEnabled) videoVolume else 0f
                            playWhenReady = isPlaying
                            prepare()
                            seekTo(0L)
                        }.also { exoPlayerInstance = it }
                    }

                    DisposableEffect(exoPlayer) {
                        onDispose {
                            exoPlayer.release()
                            exoPlayerInstance = null
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                controllerAutoShow = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                overlayFrameLayout?.visibility = View.GONE
                                hideController()
                            }
                        },
                        update = { playerView ->
                            playerView.player = exoPlayer
                            playerView.useController = false
                            playerView.controllerAutoShow = false
                            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            playerView.overlayFrameLayout?.visibility = View.GONE
                            playerView.hideController()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                            .graphicsLayer {
                                scaleX = stagedZoom
                                scaleY = stagedZoom
                                translationX = stagedOffsetX
                                translationY = stagedOffsetY
                            }
                    )
                } else if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        imageLoader = imageLoader,
                        contentDescription = "Preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                            .graphicsLayer {
                                scaleX = stagedZoom
                                scaleY = stagedZoom
                                translationX = stagedOffsetX
                                translationY = stagedOffsetY
                            }
                    )
                }
            } else {
                Text(
                    text = if (isEnglish) "No Media Selected\n(Tap button below to choose)" else "Chưa chọn Media\n(Bấm nút dưới để chọn)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Bottom Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x880D0D12), Color(0xEE0D0D12)),
                            startY = 80f
                        )
                    )
            )

            // Realistic Compact Miniature Pop-up Text & Synced Buttons
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (isEnglish) "Connected $deviceName" else "Đã kết nối $deviceName",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(0.95f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x44FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isEnglish) "Settings" else "Cài đặt", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(accentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isEnglish) "Done" else "Xong", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { launcher.launch(arrayOf("image/*", "video/*")) },
                modifier = Modifier
                    .weight(1.3f)
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isEnglish) "Select Media" else "Chọn Media", fontSize = 13.sp, maxLines = 1)
            }

            if (stagedUri.isNotEmpty() && isStagedVideo) {
                Button(
                    onClick = {
                        isPlaying = !isPlaying
                        if (isPlaying) {
                            exoPlayerInstance?.play()
                        } else {
                            exoPlayerInstance?.pause()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlaying) Color(0xFFFF3D00) else Color(0xFF2962FF)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isPlaying) (if (isEnglish) "Stop" else "Dừng") else (if (isEnglish) "Play" else "Phát"),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            if (stagedUri.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        stagedUri = ""
                        stagedType = "image"
                        stagedZoom = 1.0f
                        stagedOffsetX = 0f
                        stagedOffsetY = 0f
                        prefs.edit()
                            .remove("custom_media_uri")
                            .remove("custom_media_type")
                            .apply()
                    },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isEnglish) "Delete" else "Xóa", fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        // EXACT UNIFORM SCALE SAVING
        Button(
            onClick = {
                val normalizedOffsetX = stagedOffsetX / previewScaleFactor
                val normalizedOffsetY = stagedOffsetY / previewScaleFactor

                prefs.edit()
                    .putString("custom_media_uri", stagedUri)
                    .putString("custom_media_type", stagedType)
                    .putFloat("image_zoom", stagedZoom)
                    .putFloat("image_offset_x", stagedOffsetX)
                    .putFloat("image_offset_y", stagedOffsetY)
                    .putFloat("image_offset_x_normalized", normalizedOffsetX)
                    .putFloat("image_offset_y_normalized", normalizedOffsetY)
                    .apply()
                Toast.makeText(context, if (isEnglish) "Applied new framing to Pop-up!" else "Đã áp dụng cài đặt khung hình mới vào Pop-up!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isEnglish) "Apply" else "Áp dụng",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Compact Framing Card
        FramingSlidersCard(
            isEnglish = isEnglish,
            zoomProvider = { stagedZoom },
            onZoomChange = { stagedZoom = it },
            offsetXProvider = { stagedOffsetX },
            onOffsetXChange = { stagedOffsetX = it },
            offsetYProvider = { stagedOffsetY },
            onOffsetYChange = { stagedOffsetY = it },
            onResetFraming = {
                stagedZoom = 1.0f
                stagedOffsetX = 0f
                stagedOffsetY = 0f
            }
        )

        // Headset Audio Settings Card
        AudioSettingsCard(
            title = if (isEnglish) "Headset Sound" else "Âm thanh tai nghe",
            soundEnabled = soundEnabled,
            onSoundEnabledChange = onSoundEnabledChange,
            volumeProvider = { videoVolume },
            onVolumeChange = onVolumeChange,
            onVolumeChangeFinished = {
                prefs.edit().putFloat("video_volume", videoVolume).apply()
            },
            isEnglish = isEnglish
        )

        Button(
            onClick = {
                val serviceIntent = Intent(context, AudioPopUp::class.java).apply {
                    putExtra("test_manual", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2962FF)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isEnglish) "Test Headset Pop-up" else "Kiểm tra Pop-up Tai nghe",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ChargingMediaTab(
    context: Context,
    chargingPrefs: SharedPreferences,
    initialMediaUri: String,
    popupCardHeight: Int,
    soundEnabled: Boolean,
    videoVolume: Float,
    isEnglish: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val scrollState = rememberScrollState()
    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()

    val popupCardWidth = appPrefs.getInt("popup_card_width", 0)
    val effectiveWidthDp = if (popupCardWidth in 250..600) popupCardWidth.toFloat() else screenWidthDp

    val previewScaleFactor = 0.72f
    val previewWidthDp = (effectiveWidthDp * previewScaleFactor).dp
    val previewHeightDp = (popupCardHeight * previewScaleFactor).dp

    val chargingColorKey = chargingPrefs.getString("charging_popup_color", "cyan") ?: "cyan"
    val accentColor = when {
        chargingColorKey.startsWith("#") -> {
            try { Color(android.graphics.Color.parseColor(chargingColorKey)) } catch (_: Exception) { Color(0xFF00E5FF) }
        }
        chargingColorKey == "silver" -> Color(0xFFE0E0E0)
        chargingColorKey == "yellow" -> Color(0xFFFFEE58)
        chargingColorKey == "coral" -> Color(0xFFFF6F00)
        chargingColorKey == "turquoise" -> Color(0xFF00ACC1)
        chargingColorKey == "crimson" -> Color(0xFFC62828)
        chargingColorKey == "indigo" -> Color(0xFF3F51B5)
        chargingColorKey == "lime" -> Color(0xFFCDDC39)
        chargingColorKey == "navy" -> Color(0xFF1565C0)
        chargingColorKey == "amber" -> Color(0xFFFF8F00)
        chargingColorKey == "magenta" -> Color(0xFFD81B60)
        chargingColorKey == "red" -> Color(0xFFFF3D00)
        chargingColorKey == "gold" -> Color(0xFFFFD700)
        chargingColorKey == "teal" -> Color(0xFF00BFA5)
        chargingColorKey == "cyan" -> Color(0xFF00E5FF)
        chargingColorKey == "green" -> Color(0xFF00E676)
        chargingColorKey == "orange" -> Color(0xFFFF9100)
        chargingColorKey == "pink" -> Color(0xFFFF4081)
        chargingColorKey == "purple" -> Color(0xFF7C4DFF)
        else -> Color(0xFF00E5FF)
    }

    val themeMode = appPrefs.getString("theme_mode", "system") ?: "system"
    val isSystemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }

    val customBgColor = appPrefs.getString("popup_bg_color", "") ?: ""
    val (_, bgColor2) = when {
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

    // Direct Pre-populated State to eliminate Frame 1 empty state flicker!
    var stagedUri by remember(initialMediaUri) { mutableStateOf(initialMediaUri.ifEmpty { chargingPrefs.getString("charging_media_uri", "") ?: "" }) }
    var stagedType by remember { mutableStateOf(chargingPrefs.getString("custom_media_type", "image") ?: "image") }
    var stagedZoom by remember { mutableFloatStateOf(chargingPrefs.getFloat("charging_zoom", 1.0f)) }
    var stagedOffsetX by remember { mutableFloatStateOf(chargingPrefs.getFloat("charging_offset_x", 0f)) }
    var stagedOffsetY by remember { mutableFloatStateOf(chargingPrefs.getFloat("charging_offset_y", 0f)) }
    
    // DEFAULT IS STOPPED/PAUSED! Only plays when user taps "Phát"!
    var isPlaying by remember { mutableStateOf(false) }

    var exoPlayerInstance by remember { mutableStateOf<ExoPlayer?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val chargingLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { sourceUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val mimeType = context.contentResolver.getType(sourceUri)
            val isVideo = mimeType?.startsWith("video/") == true || sourceUri.toString().lowercase().let { it.contains("video") || it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") }
            stagedType = if (isVideo) "video" else "image"

            val fileName = if (isVideo) "charging_media_file.mp4" else "charging_media_file.jpg"
            coroutineScope.launch(Dispatchers.IO) {
                val localFile = copyUriToInternalStorage(context, sourceUri, fileName)
                val uriString = if (localFile != null) {
                    Uri.fromFile(localFile).toString()
                } else {
                    sourceUri.toString()
                }
                withContext(Dispatchers.Main) {
                    stagedUri = uriString
                    stagedZoom = 1.0f
                    stagedOffsetX = 0f
                    stagedOffsetY = 0f
                    isPlaying = false
                }
            }
        }
    }

    val isStagedVideo = remember(stagedUri, stagedType) {
        stagedUri.isNotEmpty() && (
            stagedType == "video" ||
            chargingPrefs.getBoolean("is_charging_video", false) ||
            stagedUri.lowercase().let { it.contains("video") || it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") || it.endsWith(".webm") || it.endsWith(".3gp") }
        )
    }

    var showWattage by remember { mutableStateOf(chargingPrefs.getBoolean("show_wattage", true)) }
    var showTemp by remember { mutableStateOf(chargingPrefs.getBoolean("show_temp", true)) }
    var showBattery by remember { mutableStateOf(chargingPrefs.getBoolean("show_battery", true)) }
    var showVoltage by remember { mutableStateOf(chargingPrefs.getBoolean("show_voltage", true)) }
    var showParticles by remember { mutableStateOf(chargingPrefs.getBoolean("show_charging_particles", true)) }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(ImageDecoderDecoder.Factory()) }
            .allowHardware(true)
            .build()
    }

    val imageRequest = remember(stagedUri, context) {
        if (stagedUri.isNotEmpty() && !isStagedVideo) {
            ImageRequest.Builder(context)
                .data(Uri.parse(stagedUri))
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .crossfade(false)
                .build()
        } else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnglish) "Preview" else "Xem trước",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (isEnglish) "Reset" else "Đặt lại",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        stagedZoom = 1.0f
                        stagedOffsetX = 0f
                        stagedOffsetY = 0f
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Preview Box - RELIABLE PLAYERVIEW (0% CONTROLLERS, 0% REWIND ICONS, DECODES FRAME 1 THEN PAUSES!)
        Box(
            modifier = Modifier
                .width(previewWidthDp)
                .height(previewHeightDp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
                .background(Color(0xFF0D0D12))
                .pointerInput(stagedUri) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val newZoom = (stagedZoom * zoomChange).coerceIn(0.5f, 5.0f)
                        stagedZoom = newZoom
                        val newX = (stagedOffsetX + pan.x).coerceIn(-2500f, 2500f)
                        val newY = (stagedOffsetY + pan.y).coerceIn(-2500f, 2500f)
                        stagedOffsetX = newX
                        stagedOffsetY = newY
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (stagedUri.isNotEmpty()) {
                val uri = Uri.parse(stagedUri)

                if (isStagedVideo) {
                    val exoPlayer = remember(stagedUri) {
                        ExoPlayer.Builder(context).build().apply {
                            setMediaItem(MediaItem.fromUri(uri))
                            repeatMode = ExoPlayer.REPEAT_MODE_ONE
                            volume = if (soundEnabled) videoVolume else 0f
                            playWhenReady = isPlaying
                            prepare()
                            seekTo(0L)
                        }.also { exoPlayerInstance = it }
                    }

                    DisposableEffect(exoPlayer) {
                        onDispose {
                            exoPlayer.release()
                            exoPlayerInstance = null
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                controllerAutoShow = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                overlayFrameLayout?.visibility = View.GONE
                                hideController()
                            }
                        },
                        update = { playerView ->
                            playerView.player = exoPlayer
                            playerView.useController = false
                            playerView.controllerAutoShow = false
                            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            playerView.overlayFrameLayout?.visibility = View.GONE
                            playerView.hideController()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                            .graphicsLayer {
                                scaleX = stagedZoom
                                scaleY = stagedZoom
                                translationX = stagedOffsetX
                                translationY = stagedOffsetY
                            }
                    )
                } else if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        imageLoader = imageLoader,
                        contentDescription = "Charging Preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                            .graphicsLayer {
                                scaleX = stagedZoom
                                scaleY = stagedZoom
                                translationX = stagedOffsetX
                                translationY = stagedOffsetY
                            }
                    )
                }
            } else {
                Text(
                    text = if (isEnglish) "No Media Selected\n(Tap button below to choose)" else "Chưa chọn Media\n(Bấm nút dưới để chọn)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Bottom Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xAA0D0D12), Color(0xEE0D0D12)),
                            startY = 80f
                        )
                    )
            )

            // Realistic Compact Miniature Pop-up Text & Synced Buttons
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (isEnglish) "Charging Connected" else "Đã cắm sạc",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(0.95f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x44FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isEnglish) "Settings" else "Cài đặt", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(accentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isEnglish) "Done" else "Xong", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action Button Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { chargingLauncher.launch(arrayOf("image/*", "video/*")) },
                modifier = Modifier
                    .weight(1.3f)
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isEnglish) "Select Media" else "Chọn Media", fontSize = 13.sp, maxLines = 1)
            }

            if (stagedUri.isNotEmpty() && isStagedVideo) {
                Button(
                    onClick = {
                        isPlaying = !isPlaying
                        if (isPlaying) {
                            exoPlayerInstance?.play()
                        } else {
                            exoPlayerInstance?.pause()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlaying) Color(0xFFFF3D00) else Color(0xFF00E676)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isPlaying) (if (isEnglish) "Stop" else "Dừng") else (if (isEnglish) "Play" else "Phát"),
                        color = if (isPlaying) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            if (stagedUri.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        stagedUri = ""
                        stagedType = "image"
                        stagedZoom = 1.0f
                        stagedOffsetX = 0f
                        stagedOffsetY = 0f
                        chargingPrefs.edit()
                            .remove("charging_media_uri")
                            .remove("custom_media_type")
                            .putBoolean("is_charging_video", false)
                            .apply()
                    },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isEnglish) "Delete" else "Xóa", fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        // EXACT UNIFORM SCALE SAVING
        Button(
            onClick = {
                val normalizedOffsetX = stagedOffsetX / previewScaleFactor
                val normalizedOffsetY = stagedOffsetY / previewScaleFactor

                chargingPrefs.edit()
                    .putString("charging_media_uri", stagedUri)
                    .putString("custom_media_type", stagedType)
                    .putBoolean("is_charging_video", stagedType == "video")
                    .putFloat("charging_zoom", stagedZoom)
                    .putFloat("charging_offset_x", stagedOffsetX)
                    .putFloat("charging_offset_y", stagedOffsetY)
                    .putFloat("charging_offset_x_normalized", normalizedOffsetX)
                    .putFloat("charging_offset_y_normalized", normalizedOffsetY)
                    .apply()
                Toast.makeText(context, if (isEnglish) "Applied new framing to Charging Pop-up!" else "Đã áp dụng cài đặt khung hình sạc mới vào Pop-up!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isEnglish) "Apply" else "Áp dụng",
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Compact Framing Card
        FramingSlidersCard(
            isEnglish = isEnglish,
            zoomProvider = { stagedZoom },
            onZoomChange = { stagedZoom = it },
            offsetXProvider = { stagedOffsetX },
            onOffsetXChange = { stagedOffsetX = it },
            offsetYProvider = { stagedOffsetY },
            onOffsetYChange = { stagedOffsetY = it },
            onResetFraming = {
                stagedZoom = 1.0f
                stagedOffsetX = 0f
                stagedOffsetY = 0f
            }
        )

        // Telemetry Display Toggles Card
        ExpandableCard(title = if (isEnglish) "Charging Info" else "Thông tin sạc", initialExpanded = false) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Wattage (W)" else "Công suất (W)", fontSize = 14.sp)
                    Switch(
                        checked = showWattage,
                        onCheckedChange = {
                            showWattage = it
                            chargingPrefs.edit().putBoolean("show_wattage", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Battery Temp" else "Nhiệt độ pin", fontSize = 14.sp)
                    Switch(
                        checked = showTemp,
                        onCheckedChange = {
                            showTemp = it
                            chargingPrefs.edit().putBoolean("show_temp", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Battery Percentage (%)" else "Phần trăm pin (%)", fontSize = 14.sp)
                    Switch(
                        checked = showBattery,
                        onCheckedChange = {
                            showBattery = it
                            chargingPrefs.edit().putBoolean("show_battery", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Voltage (V)" else "Số Volt (V)", fontSize = 14.sp)
                    Switch(
                        checked = showVoltage,
                        onCheckedChange = {
                            showVoltage = it
                            chargingPrefs.edit().putBoolean("show_voltage", it).apply()
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "SuperVOOC Ring Effect" else "Hiệu ứng vòng tròn SuperVOOC", fontSize = 14.sp)
                    Switch(
                        checked = showParticles,
                        onCheckedChange = {
                            showParticles = it
                            chargingPrefs.edit().putBoolean("show_charging_particles", it).apply()
                        }
                    )
                }
            }
        }

        // Charging Audio Settings Card
        AudioSettingsCard(
            title = if (isEnglish) "Charging Sound" else "Âm thanh sạc",
            soundEnabled = soundEnabled,
            onSoundEnabledChange = onSoundEnabledChange,
            volumeProvider = { videoVolume },
            onVolumeChange = onVolumeChange,
            onVolumeChangeFinished = {
                chargingPrefs.edit().putFloat("charging_volume", videoVolume).apply()
            },
            isEnglish = isEnglish
        )

        Button(
            onClick = {
                val serviceIntent = Intent(context, ChargingOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isEnglish) "Test Charging Pop-up" else "Kiểm tra Pop-up Sạc pin",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                softWrap = false
            )
        }
    }
}

@Composable
fun SegmentedSelector(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onOptionSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = selectedKey == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onOptionSelected(key) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ExpandableCard(
    title: String,
    initialExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(18.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (expanded) "▲" else "▼",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun FramingSlidersCard(
    isEnglish: Boolean,
    zoomProvider: () -> Float,
    onZoomChange: (Float) -> Unit,
    offsetXProvider: () -> Float,
    onOffsetXChange: (Float) -> Unit,
    offsetYProvider: () -> Float,
    onOffsetYChange: (Float) -> Unit,
    onResetFraming: () -> Unit
) {
    ExpandableCard(title = if (isEnglish) "Size & Position" else "Kích thước và vị trí", initialExpanded = false) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val zoom = zoomProvider()
            val offX = offsetXProvider()
            val offY = offsetYProvider()

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = "Zoom: ${String.format(Locale.getDefault(), "%.2f", zoom)}x", fontSize = 12.sp)
                Slider(
                    value = zoom,
                    onValueChange = onZoomChange,
                    valueRange = 0.5f..5.0f,
                    modifier = Modifier.height(28.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = "Offset X: ${offX.toInt()}px", fontSize = 12.sp)
                Slider(
                    value = offX,
                    onValueChange = onOffsetXChange,
                    valueRange = -2500f..2500f,
                    modifier = Modifier.height(28.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = "Offset Y: ${offY.toInt()}px", fontSize = 12.sp)
                Slider(
                    value = offY,
                    onValueChange = onOffsetYChange,
                    valueRange = -2500f..2500f,
                    modifier = Modifier.height(28.dp)
                )
            }

            Text(
                text = if (isEnglish) "Reset Framing" else "Đặt lại Khung hình",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onResetFraming()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun AudioSettingsCard(
    title: String,
    soundEnabled: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    volumeProvider: () -> Float,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: (() -> Unit)? = null,
    isEnglish: Boolean
) {
    ExpandableCard(title = title, initialExpanded = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEnglish) "Enable sound on play" else "Bật âm thanh khi phát",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = onSoundEnabledChange
                )
            }

            if (soundEnabled) {
                val vol = volumeProvider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isEnglish) "Volume: ${(vol * 100).toInt()}%" else "Âm lượng: ${(vol * 100).toInt()}%",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = vol,
                        onValueChange = onVolumeChange,
                        onValueChangeFinished = onVolumeChangeFinished,
                        valueRange = 0.0f..1.0f
                    )
                }
            }
        }
    }
}

data class PaletteColorItem(
    val color: Color,
    val name: String,
    val hexString: String
)

fun colorToHsv(color: Color): FloatArray {
    val hsv = FloatArray(3)
    val colorInt = android.graphics.Color.argb(
        (color.alpha * 255f).toInt().coerceIn(0, 255),
        (color.red * 255f).toInt().coerceIn(0, 255),
        (color.green * 255f).toInt().coerceIn(0, 255),
        (color.blue * 255f).toInt().coerceIn(0, 255)
    )
    android.graphics.Color.colorToHSV(colorInt, hsv)
    return hsv
}

fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val colorInt = android.graphics.Color.HSVToColor(
        floatArrayOf(
            hue.coerceIn(0f, 360f),
            saturation.coerceIn(0f, 1f),
            value.coerceIn(0f, 1f)
        )
    )
    return Color(colorInt)
}

fun colorToHex(color: Color): String {
    val r = (color.red * 255f).toInt().coerceIn(0, 255)
    val g = (color.green * 255f).toInt().coerceIn(0, 255)
    val b = (color.blue * 255f).toInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

val HarmoniousPaletteList = listOf(
    // Nhóm 1: Rực rỡ & Hiện đại (Material 3 Expressive)
    PaletteColorItem(Color(0xFF2962FF), "Xanh dương rực", "#2962FF"),
    PaletteColorItem(Color(0xFF00E5FF), "Xanh Cyan sáng", "#00E5FF"),
    PaletteColorItem(Color(0xFF00BFA5), "Xanh ngọc (Teal)", "#00BFA5"),
    PaletteColorItem(Color(0xFF00E676), "Xanh neon", "#00E676"),
    PaletteColorItem(Color(0xFF4CAF50), "Xanh lá tươi", "#4CAF50"),
    PaletteColorItem(Color(0xFFCDDC39), "Xanh Lime", "#CDDC39"),
    PaletteColorItem(Color(0xFFFFD700), "Vàng ánh kim (Gold)", "#FFD700"),
    PaletteColorItem(Color(0xFFFF9100), "Cam tươi", "#FF9100"),
    PaletteColorItem(Color(0xFFFF3D00), "Đỏ cờ", "#FF3D00"),
    PaletteColorItem(Color(0xFFC62828), "Đỏ sậm (Crimson)", "#C62828"),
    PaletteColorItem(Color(0xFFFF4081), "Hồng tươi", "#FF4081"),
    PaletteColorItem(Color(0xFFD81B60), "Hồng Magenta", "#D81B60"),
    PaletteColorItem(Color(0xFF7C4DFF), "Tím rực", "#7C4DFF"),
    PaletteColorItem(Color(0xFF3F51B5), "Xanh Indigo", "#3F51B5"),
    PaletteColorItem(Color(0xFF1565C0), "Xanh Navy", "#1565C0"),
    PaletteColorItem(Color(0xFF00ACC1), "Xanh ngọc bích", "#00ACC1"),

    // Nhóm 2: Cyberpunk & Gaming Neon
    PaletteColorItem(Color(0xFF39FF14), "Neon Lime", "#39FF14"),
    PaletteColorItem(Color(0xFF00F0FF), "Laser Cyan", "#00F0FF"),
    PaletteColorItem(Color(0xFFFF007F), "Hyper Pink", "#FF007F"),
    PaletteColorItem(Color(0xFFBF00FF), "Electric Violet", "#BF00FF"),
    PaletteColorItem(Color(0xFFFF5E00), "Plasma Orange", "#FF5E00"),
    PaletteColorItem(Color(0xFFFFE600), "Reactor Yellow", "#FFE600"),
    PaletteColorItem(Color(0xFF7B00FF), "Ultraviolet", "#7B00FF"),
    PaletteColorItem(Color(0xFF00FF66), "Matrix Green", "#00FF66"),

    // Nhóm 3: Pastel & Tinh tế (Soft Aesthetic)
    PaletteColorItem(Color(0xFFFFB7B2), "Hồng phấn Sakura", "#FFB7B2"),
    PaletteColorItem(Color(0xFFFFDAC1), "Kem đào (Peach)", "#FFDAC1"),
    PaletteColorItem(Color(0xFFE2F0CB), "Vàng chanh dịu", "#E2F0CB"),
    PaletteColorItem(Color(0xFFB5EAD7), "Bạc hà (Mint)", "#B5EAD7"),
    PaletteColorItem(Color(0xFFC7CEEA), "Xanh baby sky", "#C7CEEA"),
    PaletteColorItem(Color(0xFFE8D7F1), "Tím oải hương", "#E8D7F1"),
    PaletteColorItem(Color(0xFFF7D6E0), "Hồng kẹo ngọt", "#F7D6E0"),
    PaletteColorItem(Color(0xFFD8E2DC), "Khói xám sương", "#D8E2DC"),

    // Nhóm 4: Kim loại & Đơn sắc (Metallic & Monochrome)
    PaletteColorItem(Color(0xFFFFFFFF), "Trắng tinh khiết", "#FFFFFF"),
    PaletteColorItem(Color(0xFFE0E0E0), "Bạc kim (Silver)", "#E0E0E0"),
    PaletteColorItem(Color(0xFF9E9E9E), "Xám trung tính", "#9E9E9E"),
    PaletteColorItem(Color(0xFF607D8B), "Xám đá (Slate)", "#607D8B"),
    PaletteColorItem(Color(0xFF37474F), "Thép xám đậm", "#37474F"),
    PaletteColorItem(Color(0xFF1E1E2C), "Đêm huyền bí (Midnight)", "#1E1E2C"),
    PaletteColorItem(Color(0xFF121212), "Than chì (Carbon)", "#121212"),
    PaletteColorItem(Color(0xFF000000), "Đen tuyền (OLED Black)", "#000000"),

    // Nhóm 5: Tự nhiên & Đất mộc (Morandi & Earthy)
    PaletteColorItem(Color(0xFFC97A63), "Gạch đất nung", "#C97A63"),
    PaletteColorItem(Color(0xFFC48B9F), "Hồng đất khô", "#C48B9F"),
    PaletteColorItem(Color(0xFF9FAF90), "Xanh xô thơm (Sage)", "#9FAF90"),
    PaletteColorItem(Color(0xFFD7C4B7), "Cát ấm (Sand)", "#D7C4B7"),
    PaletteColorItem(Color(0xFF52796F), "Xanh rêu đá", "#52796F"),
    PaletteColorItem(Color(0xFF5C4033), "Nâu cà phê", "#5C4033"),
    PaletteColorItem(Color(0xFF795548), "Nâu cacao", "#795548"),
    PaletteColorItem(Color(0xFFFFC107), "Vàng hổ phách", "#FFC107")
)

@Composable
fun ColorPaletteDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (PaletteColorItem) -> Unit
) {
    // 0: Spectrum mode (After Effects / Photoshop), 1: Preset Grid
    var selectedMode by remember { mutableIntStateOf(0) }

    val initialHsv = remember(initialColor) { colorToHsv(initialColor) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    val currentColor = remember(hue, saturation, value) {
        hsvToColor(hue, saturation, value)
    }
    val currentHex = remember(currentColor) {
        colorToHex(currentColor)
    }

    var selectedPresetItem by remember {
        mutableStateOf(
            HarmoniousPaletteList.find { it.color == initialColor } ?: HarmoniousPaletteList.first()
        )
    }

    val quickColors = remember {
        listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFF1744), Color(0xFFFF9100),
            Color(0xFFFFEA00), Color(0xFF00E676), Color(0xFF00E5FF), Color(0xFF2979FF),
            Color(0xFFD500F9), Color(0xFFFF4081)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bảng chọn màu", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                // Mode Switcher Tab
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modeTitles = listOf("Dải màu (AE)", "Mẫu có sẵn")
                    modeTitles.forEachIndexed { index, title ->
                        val isSelected = selectedMode == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { selectedMode = index },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Live Color Compare & Hex Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Original Swatch
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(initialColor)
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        )
                        Text("→", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // New Selected Swatch
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(currentColor)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                    }

                    // Hex code pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = currentHex,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (selectedMode == 0) {
                    // MODE 0: SPECTRUM MODE (After Effects 2D Saturation-Value + Rainbow Hue Slider)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 2D Saturation & Value Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(hsvToColor(hue, 1f, 1f))
                        ) {
                            // Horizontal White Gradient (Saturation 0 -> 1)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color.White, Color.Transparent)
                                        )
                                    )
                            )
                            // Vertical Black Gradient (Value 1 -> 0)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black)
                                        )
                                    )
                            )
                            // Interactive Canvas Touch & Crosshair
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val width = size.width.toFloat().coerceAtLeast(1f)
                                            val height = size.height.toFloat().coerceAtLeast(1f)
                                            saturation = (down.position.x / width).coerceIn(0f, 1f)
                                            value = (1f - down.position.y / height).coerceIn(0f, 1f)
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                if (!change.pressed) break
                                                change.consume()
                                                saturation = (change.position.x / width).coerceIn(0f, 1f)
                                                value = (1f - change.position.y / height).coerceIn(0f, 1f)
                                            }
                                        }
                                    }
                            ) {
                                val cx = saturation * size.width
                                val cy = (1f - value) * size.height
                                drawCircle(
                                    color = Color.Black,
                                    radius = 9.dp.toPx(),
                                    center = Offset(cx, cy),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 7.dp.toPx(),
                                    center = Offset(cx, cy),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                drawCircle(
                                    color = currentColor,
                                    radius = 5.dp.toPx(),
                                    center = Offset(cx, cy)
                                )
                            }
                        }

                        // Rainbow Hue Slider (0° - 360°)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFFFF0000),
                                            Color(0xFFFFFF00),
                                            Color(0xFF00FF00),
                                            Color(0xFF00FFFF),
                                            Color(0xFF0000FF),
                                            Color(0xFFFF00FF),
                                            Color(0xFFFF0000)
                                        )
                                    )
                                )
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val width = size.width.toFloat().coerceAtLeast(1f)
                                            hue = ((down.position.x / width).coerceIn(0f, 1f) * 360f).coerceIn(0f, 360f)
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull() ?: break
                                                if (!change.pressed) break
                                                change.consume()
                                                hue = ((change.position.x / width).coerceIn(0f, 1f) * 360f).coerceIn(0f, 360f)
                                            }
                                        }
                                    }
                            ) {
                                val hx = (hue / 360f).coerceIn(0f, 1f) * size.width
                                val centerY = size.height / 2f
                                drawCircle(
                                    color = Color.Black,
                                    radius = 11.dp.toPx(),
                                    center = Offset(hx, centerY),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 9.dp.toPx(),
                                    center = Offset(hx, centerY),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                drawCircle(
                                    color = hsvToColor(hue, 1f, 1f),
                                    radius = 7.dp.toPx(),
                                    center = Offset(hx, centerY)
                                )
                            }
                        }

                        // Quick Swatches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            quickColors.forEach { qc ->
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(qc)
                                        .border(0.8.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)
                                        .clickable {
                                            val hsv = colorToHsv(qc)
                                            hue = hsv[0]
                                            saturation = hsv[1]
                                            value = hsv[2]
                                        }
                                )
                            }
                        }
                    }
                } else {
                    // MODE 1: EXPANDED PRESET GRID
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HarmoniousPaletteList.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowItems.forEach { item ->
                                    val isSelected = currentHex.equals(item.hexString, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(item.color)
                                            .border(
                                                width = if (isSelected) 2.5.dp else 0.5.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                selectedPresetItem = item
                                                val hsv = colorToHsv(item.color)
                                                hue = hsv[0]
                                                saturation = hsv[1]
                                                value = hsv[2]
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(if ((item.color.red * 0.299f + item.color.green * 0.587f + item.color.blue * 0.114f) > 0.5f) Color.Black else Color.White)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onColorSelected(PaletteColorItem(currentColor, "Custom", currentHex))
                }
            ) {
                Text("Chọn")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

@Composable
fun ColorSettingRow(
    title: String,
    currentColorHex: String,
    onClick: () -> Unit
) {
    val parsedColor = remember(currentColorHex) {
        try {
            Color(android.graphics.Color.parseColor(currentColorHex))
        } catch (_: Exception) {
            when (currentColorHex) {
                "blue" -> Color(0xFF2962FF)
                "cyan" -> Color(0xFF00E5FF)
                "red" -> Color(0xFFFF3D00)
                "green" -> Color(0xFF00E676)
                "orange" -> Color(0xFFFF9100)
                "pink" -> Color(0xFFFF4081)
                "purple" -> Color(0xFF7C4DFF)
                else -> Color(0xFF2962FF)
            }
        }
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (currentColorHex.startsWith("#")) currentColorHex.uppercase() else currentColorHex,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(parsedColor)
                        .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                )
                Text(
                    text = "Đổi màu",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SettingsPresetTab(
    context: Context,
    prefs: SharedPreferences,
    chargingPrefs: SharedPreferences,
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    popupCardHeight: Int,
    onPopupCardHeightChange: (Int) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    appLanguage: String,
    onLanguageChange: (String) -> Unit,
    hasOverlayPermission: Boolean,
    onPresetLoaded: () -> Unit,
    onOverlayPermissionClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    var refreshKey by remember { mutableIntStateOf(0) }
    val isEnglish = appLanguage == "en"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasOverlayPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isEnglish) "IMPORTANT: Please enable Display over other apps permission" else "QUAN TRỌNG: Vui lòng bật \"Cho phép hiển thị trên các ứng dụng khác\"",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onOverlayPermissionClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isEnglish) "Go to Settings" else "Đi tới Cài đặt")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isEnglish) "GUIDE" else "HƯỚNG DẪN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (isEnglish) "1. Please enable Lock in Recents / Lock App in background.\n(Open Recents -> 3 dots on App card -> Lock)" else "1. Vui lòng bật \"Khóa đa nhiệm\" hoặc \"Cố định ứng dụng chạy ngầm\"\n(Mở đa nhiệm => Dấu 3 chấm trên tab app => Khóa)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (isEnglish) "2. Please set Battery Optimization to \"Unrestricted / Allow background activity\" in Battery Settings." else "2. Vui lòng bật \"Cho phép hoạt động dưới nền\" (Tối ưu hóa Pin => Không giới hạn) trong Cài đặt pin.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (isEnglish) "3. Please enable \"Autostart / Auto-launch\" permission in App / System Settings." else "3. Vui lòng bật \"Tự khởi chạy\" (Autostart) trong Cài đặt ứng dụng / Quản lý hệ thống.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = 18.sp
                )
            }
        }

        // 1. Color Customization Card
        ExpandableCard(title = if (isEnglish) "Pop-up Colors" else "Màu sắc Pop-up", initialExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var headsetColor by remember { mutableStateOf(prefs.getString("headset_popup_color", "blue") ?: "blue") }
                var chargingColor by remember { mutableStateOf(chargingPrefs.getString("charging_popup_color", "cyan") ?: "cyan") }
                var chargingRingColor by remember { mutableStateOf(chargingPrefs.getString("charging_ring_color", "cyan") ?: "cyan") }
                
                var showHeadsetPalette by remember { mutableStateOf(false) }
                var showChargingPalette by remember { mutableStateOf(false) }
                var showChargingRingPalette by remember { mutableStateOf(false) }

                ColorSettingRow(
                    title = if (isEnglish) "Headset Pop-up Color" else "Màu sắc Pop-up Tai nghe",
                    currentColorHex = headsetColor,
                    onClick = { showHeadsetPalette = true }
                )

                if (showHeadsetPalette) {
                    val initial = try {
                        Color(android.graphics.Color.parseColor(headsetColor))
                    } catch (_: Exception) {
                        Color(0xFF2962FF)
                    }
                    ColorPaletteDialog(
                        initialColor = initial,
                        onDismiss = { showHeadsetPalette = false },
                        onColorSelected = { paletteItem ->
                            headsetColor = paletteItem.hexString
                            prefs.edit().putString("headset_popup_color", paletteItem.hexString).apply()
                            showHeadsetPalette = false
                        }
                    )
                }

                ColorSettingRow(
                    title = if (isEnglish) "Charging Pop-up Color" else "Màu sắc Pop-up Sạc pin",
                    currentColorHex = chargingColor,
                    onClick = { showChargingPalette = true }
                )

                if (showChargingPalette) {
                    val initial = try {
                        Color(android.graphics.Color.parseColor(chargingColor))
                    } catch (_: Exception) {
                        Color(0xFF00E5FF)
                    }
                    ColorPaletteDialog(
                        initialColor = initial,
                        onDismiss = { showChargingPalette = false },
                        onColorSelected = { paletteItem ->
                            chargingColor = paletteItem.hexString
                            chargingPrefs.edit().putString("charging_popup_color", paletteItem.hexString).apply()
                            showChargingPalette = false
                        }
                    )
                }

                ColorSettingRow(
                    title = if (isEnglish) "Charging Ring & Particles" else "Màu vòng tròn & Hiệu ứng sạc",
                    currentColorHex = chargingRingColor,
                    onClick = { showChargingRingPalette = true }
                )

                if (showChargingRingPalette) {
                    val initial = try {
                        Color(android.graphics.Color.parseColor(chargingRingColor))
                    } catch (_: Exception) {
                        Color(0xFF00E5FF)
                    }
                    ColorPaletteDialog(
                        initialColor = initial,
                        onDismiss = { showChargingRingPalette = false },
                        onColorSelected = { paletteItem ->
                            chargingRingColor = paletteItem.hexString
                            chargingPrefs.edit().putString("charging_ring_color", paletteItem.hexString).apply()
                            showChargingRingPalette = false
                        }
                    )
                }
            }
        }

        // Background Color Card - UNIFIED SEAMLESS SEGMENTED SELECTOR!
        ExpandableCard(title = if (isEnglish) "Pop-up Background" else "Màu nền Pop-up") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var bgStyle by remember { mutableStateOf(prefs.getString("popup_bg_style", "default") ?: "default") }
                var customBgColor by remember { mutableStateOf(prefs.getString("popup_bg_color", "") ?: "") }
                var showBgPalette by remember { mutableStateOf(false) }

                Text(text = if (isEnglish) "Preset Style:" else "Kiểu nền mẫu:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                
                val bgOptions = if (isEnglish) listOf("default" to "Default", "black" to "Black", "navy" to "Navy", "carbon" to "Carbon")
                else listOf("default" to "Mặc định", "black" to "Đen", "navy" to "Navy", "carbon" to "Carbon")

                SegmentedSelector(
                    options = bgOptions,
                    selectedKey = if (customBgColor.isEmpty()) bgStyle else "",
                    onOptionSelected = { key ->
                        bgStyle = key
                        customBgColor = ""
                        prefs.edit().putString("popup_bg_style", key).remove("popup_bg_color").apply()
                    }
                )

                ColorSettingRow(
                    title = if (isEnglish) "Custom Background Color" else "Màu nền tùy chỉnh",
                    currentColorHex = if (customBgColor.isEmpty()) "#1E1E2C" else customBgColor,
                    onClick = { showBgPalette = true }
                )

                if (showBgPalette) {
                    val initial = try {
                        Color(android.graphics.Color.parseColor(customBgColor))
                    } catch (_: Exception) {
                        Color(0xFF1E1E2C)
                    }
                    ColorPaletteDialog(
                        initialColor = initial,
                        onDismiss = { showBgPalette = false },
                        onColorSelected = { paletteItem ->
                            customBgColor = paletteItem.hexString
                            prefs.edit().putString("popup_bg_color", paletteItem.hexString).apply()
                            showBgPalette = false
                        }
                    )
                }
            }
        }

        // 2. App Theme, Language & Master Switches Card - UNIFIED SEAMLESS SEGMENTED SELECTORS!
        ExpandableCard(title = if (isEnglish) "App Theme & Language" else "Giao diện và bật tắt") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = if (isEnglish) "App Language" else "Ngôn ngữ giao diện", fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                
                SegmentedSelector(
                    options = listOf("vi" to "Tiếng Việt", "en" to "English"),
                    selectedKey = appLanguage,
                    onOptionSelected = { onLanguageChange(it) }
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(text = if (isEnglish) "App Theme Mode" else "Giao diện ứng dụng", fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                
                val themeOptions = if (isEnglish) listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                else listOf("system" to "Hệ thống", "light" to "Sáng", "dark" to "Tối")

                SegmentedSelector(
                    options = themeOptions,
                    selectedKey = themeMode,
                    onOptionSelected = { onThemeModeChange(it) }
                )

                Spacer(modifier = Modifier.height(4.dp))
                var enableHeadset by remember { mutableStateOf(prefs.getBoolean("enable_headset_popup", true)) }
                var enableCharging by remember { mutableStateOf(prefs.getBoolean("enable_charging_popup", true)) }
                var enableLandscape by remember { mutableStateOf(prefs.getBoolean("enable_landscape_popup", true)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Enable Headset Pop-up" else "Bật Pop-up Tai nghe", fontSize = 13.sp, maxLines = 1)
                    Switch(
                        checked = enableHeadset,
                        onCheckedChange = {
                            enableHeadset = it
                            prefs.edit().putBoolean("enable_headset_popup", it).apply()
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Enable Charging Pop-up" else "Bật Pop-up Sạc pin", fontSize = 13.sp, maxLines = 1)
                    Switch(
                        checked = enableCharging,
                        onCheckedChange = {
                            enableCharging = it
                            prefs.edit().putBoolean("enable_charging_popup", it).apply()
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = if (isEnglish) "Enable Landscape Pop-up" else "Bật Pop-up màn hình ngang", fontSize = 13.sp, maxLines = 1)
                    Switch(
                        checked = enableLandscape,
                        onCheckedChange = {
                            enableLandscape = it
                            prefs.edit().putBoolean("enable_landscape_popup", it).apply()
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }
                    )
                }
            }
        }

        // 3. Multi-slot Presets Card
        ExpandableCard(title = if (isEnglish) "Preset Manager" else "Quản lý Preset") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in 1..3) {
                    val slotExists = remember(refreshKey) { prefs.getBoolean("preset_${slot}_exists", false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Preset $slot ${if (slotExists) (if (isEnglish) "(Saved)" else "(Đã lưu)") else (if (isEnglish) "(Empty)" else "(Trống)")}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    prefs.edit()
                                        .putBoolean("preset_${slot}_exists", true)
                                        .putString("preset_${slot}_headset_uri", prefs.getString("custom_media_uri", ""))
                                        .putString("preset_${slot}_device_name", deviceName)
                                        .putFloat("preset_${slot}_zoom", prefs.getFloat("image_zoom", 1.0f))
                                        .putFloat("preset_${slot}_offset_x", prefs.getFloat("image_offset_x", 0f))
                                        .putFloat("preset_${slot}_offset_y", prefs.getFloat("image_offset_y", 0f))
                                        .putBoolean("preset_${slot}_sound", prefs.getBoolean("video_sound_enabled", false))
                                        .putFloat("preset_${slot}_volume", prefs.getFloat("video_volume", 0.5f))
                                        .apply()

                                    chargingPrefs.edit()
                                        .putString("preset_${slot}_charging_uri", chargingPrefs.getString("charging_media_uri", ""))
                                        .putBoolean("preset_${slot}_charging_sound", chargingPrefs.getBoolean("charging_sound_enabled", false))
                                        .putFloat("preset_${slot}_charging_volume", chargingPrefs.getFloat("charging_volume", 0.5f))
                                        .putBoolean("preset_${slot}_show_wattage", chargingPrefs.getBoolean("show_wattage", true))
                                        .putBoolean("preset_${slot}_show_temp", chargingPrefs.getBoolean("show_temp", true))
                                        .putBoolean("preset_${slot}_show_battery", chargingPrefs.getBoolean("show_battery", true))
                                        .putBoolean("preset_${slot}_show_voltage", chargingPrefs.getBoolean("show_voltage", true))
                                        .putBoolean("preset_${slot}_show_charging_particles", chargingPrefs.getBoolean("show_charging_particles", true))
                                        .putString("preset_${slot}_charging_ring_color", chargingPrefs.getString("charging_ring_color", "cyan"))
                                        .apply()

                                    refreshKey++
                                },
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (isEnglish) "Save" else "Lưu / Cập nhật", fontSize = 11.sp, maxLines = 1)
                            }

                            if (slotExists) {
                                OutlinedButton(
                                    onClick = {
                                        val hUri = prefs.getString("preset_${slot}_headset_uri", "") ?: ""
                                        val dName = prefs.getString("preset_${slot}_device_name", "Audio USB") ?: "Audio USB"
                                        val zoom = prefs.getFloat("preset_${slot}_zoom", 1.0f)
                                        val offX = prefs.getFloat("preset_${slot}_offset_x", 0f)
                                        val offY = prefs.getFloat("preset_${slot}_offset_y", 0f)
                                        val hSound = prefs.getBoolean("preset_${slot}_sound", false)
                                        val hVol = prefs.getFloat("preset_${slot}_volume", 0.5f)

                                        prefs.edit()
                                            .putString("custom_media_uri", hUri)
                                            .putString("device_custom_name", dName)
                                            .putFloat("image_zoom", zoom)
                                            .putFloat("image_offset_x", offX)
                                            .putFloat("image_offset_y", offY)
                                            .putBoolean("video_sound_enabled", hSound)
                                            .putFloat("video_volume", hVol)
                                            .apply()

                                        val cUri = chargingPrefs.getString("preset_${slot}_charging_uri", "") ?: ""
                                        val cSound = chargingPrefs.getBoolean("preset_${slot}_charging_sound", false)
                                        val cVol = chargingPrefs.getFloat("preset_${slot}_charging_volume", 0.5f)
                                        val sW = chargingPrefs.getBoolean("preset_${slot}_show_wattage", true)
                                        val sT = chargingPrefs.getBoolean("preset_${slot}_show_temp", true)
                                        val sB = chargingPrefs.getBoolean("preset_${slot}_show_battery", true)
                                        val sV = chargingPrefs.getBoolean("preset_${slot}_show_voltage", true)
                                        val sP = chargingPrefs.getBoolean("preset_${slot}_show_charging_particles", true)
                                        val cRingColor = chargingPrefs.getString("preset_${slot}_charging_ring_color", "cyan") ?: "cyan"

                                        chargingPrefs.edit()
                                            .putString("charging_media_uri", cUri)
                                            .putBoolean("charging_sound_enabled", cSound)
                                            .putFloat("charging_volume", cVol)
                                            .putBoolean("show_wattage", sW)
                                            .putBoolean("show_temp", sT)
                                            .putBoolean("show_battery", sB)
                                            .putBoolean("show_voltage", sV)
                                            .putBoolean("show_charging_particles", sP)
                                            .putString("charging_ring_color", cRingColor)
                                            .apply()

                                        onDeviceNameChange(dName)
                                        onPresetLoaded()
                                        refreshKey++
                                    },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(if (isEnglish) "Load" else "Tải", fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Device Name & Framing Card - NO TEXT CLIPPING ON RESET BUTTON!
        ExpandableCard(title = if (isEnglish) "Device Name & Card Dimensions" else "Tên thiết bị và kích thước Pop-up") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = if (isEnglish) "Display Device Name" else "Tên thiết bị hiển thị", fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                var textState by remember(deviceName) { mutableStateOf(deviceName) }
                OutlinedTextField(
                    value = textState,
                    onValueChange = { newVal ->
                        textState = newVal
                        onDeviceNameChange(newVal)
                        prefs.edit().putString("device_custom_name", newVal).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = if (isEnglish) "Pop-up Height: ${popupCardHeight}dp" else "Chiều cao Pop-up: ${popupCardHeight}dp", fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
                        Text(
                            text = if (isEnglish) "Reset" else "Đặt lại",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onPopupCardHeightChange(440)
                                    prefs.edit().putInt("popup_card_height", 440).apply()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Slider(
                        value = popupCardHeight.toFloat(),
                        onValueChange = { newVal -> onPopupCardHeightChange(newVal.toInt()) },
                        onValueChangeFinished = {
                            prefs.edit().putInt("popup_card_height", popupCardHeight).apply()
                        },
                        valueRange = 300f..600f,
                        modifier = Modifier.height(28.dp)
                    )
                }

                var popupCardWidth by remember { mutableIntStateOf(prefs.getInt("popup_card_width", 0)) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEnglish) "Pop-up Width: ${if (popupCardWidth > 0) "${popupCardWidth}dp" else "Full Width"}" else "Độ rộng Pop-up: ${if (popupCardWidth > 0) "${popupCardWidth}dp" else "Toàn màn hình"}",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Text(
                            text = if (isEnglish) "Reset" else "Đặt lại",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    popupCardWidth = 0
                                    prefs.edit().putInt("popup_card_width", 0).apply()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Slider(
                        value = if (popupCardWidth > 0) popupCardWidth.toFloat() else 390f,
                        onValueChange = { newVal ->
                            popupCardWidth = newVal.toInt()
                        },
                        onValueChangeFinished = {
                            prefs.edit().putInt("popup_card_width", popupCardWidth).apply()
                        },
                        valueRange = 280f..410f,
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        // PROFESSIONAL ELEGANT DEVELOPER FOOTER AT THE BOTTOM OF SETTINGS TAB ONLY!
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
            thickness = 1.dp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Apeiron",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (isEnglish) "Developed by Nguyen Chi Thanh" else "Phát triển bởi Nguyễn Chí Thành",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
