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

fun copyFileOrUri(context: Context, sourceUriString: String, destFile: File): Boolean {
    if (sourceUriString.isEmpty()) return false
    return try {
        val uri = Uri.parse(sourceUriString)
        if (uri.scheme == "file" || sourceUriString.startsWith("/")) {
            val path = if (uri.scheme == "file") uri.path ?: "" else sourceUriString
            val srcFile = File(path)
            if (srcFile.exists()) {
                if (srcFile.canonicalPath == destFile.canonicalPath) return true
                srcFile.copyTo(destFile, overwrite = true)
                return true
            }
        }
        val inputStream = context.contentResolver.openInputStream(uri) ?: return false
        val outputStream = FileOutputStream(destFile)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun tr(lang: String, vi: String, en: String, zh: String): String {
    return when (lang) {
        "zh" -> zh
        "en" -> en
        else -> vi
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
    var presetLoadKey by remember { mutableIntStateOf(0) }

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
                        text = { Text(tr(appLanguage, "Pop-up Tai nghe", "Headset Pop-up", "耳机弹窗"), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectTab(1) },
                        interactionSource = remember { MutableInteractionSource() },
                        text = { Text(tr(appLanguage, "Pop-up Sạc pin", "Charging Pop-up", "充电弹窗"), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectTab(2) },
                        interactionSource = remember { MutableInteractionSource() },
                        text = { Text(tr(appLanguage, "Cài đặt & Preset", "Settings & Presets", "设置与预设"), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }
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
                    appLanguage = appLanguage,
                    isEnglish = isEnglish,
                    presetLoadKey = presetLoadKey,
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
                    appLanguage = appLanguage,
                    isEnglish = isEnglish,
                    presetLoadKey = presetLoadKey,
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
                        presetLoadKey++
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
    appLanguage: String = "vi",
    isEnglish: Boolean = appLanguage == "en",
    presetLoadKey: Int = 0,
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
    var stagedUri by remember(initialMediaUri, presetLoadKey) { mutableStateOf(initialMediaUri.ifEmpty { prefs.getString("custom_media_uri", "") ?: "" }) }
    var stagedType by remember(initialMediaUri, presetLoadKey) { mutableStateOf(prefs.getString("custom_media_type", "image") ?: "image") }
    var stagedZoom by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(prefs.getFloat("image_zoom", 1.0f)) }
    var stagedOffsetX by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(prefs.getFloat("image_offset_x", 0f)) }
    var stagedOffsetY by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(prefs.getFloat("image_offset_y", 0f)) }
    
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
                        text = tr(appLanguage, "Vui lòng bật Cho phép hiển thị trên các ứng dụng khác", "Please enable Display over other apps permission", "请开启“显示在其他应用上层”权限"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onOverlayPermissionClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(tr(appLanguage, "Đi tới Cài đặt", "Go to Settings", "前往设置"))
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
                text = tr(appLanguage, "Xem trước", "Preview", "预览"),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = tr(appLanguage, "Đặt lại", "Reset", "重置"),
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
                    text = tr(
                        appLanguage,
                        "Chưa chọn Media\n(Bấm nút dưới để chọn)",
                        "No Media Selected\n(Tap button below to choose)",
                        "未选择媒体\n(点击下方按钮选择)"
                    ),
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
                    text = tr(appLanguage, "Đã kết nối $deviceName", "Connected $deviceName", "已连接 $deviceName"),
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
                        Text(tr(appLanguage, "Cài đặt", "Settings", "设置"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(accentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tr(appLanguage, "Xong", "Done", "完成"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                Text(tr(appLanguage, "Chọn Media", "Select Media", "选择媒体"), fontSize = 13.sp, maxLines = 1)
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
                        text = if (isPlaying) tr(appLanguage, "Dừng", "Stop", "停止") else tr(appLanguage, "Phát", "Play", "播放"),
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
                    Text(tr(appLanguage, "Xóa", "Delete", "删除"), fontSize = 13.sp, maxLines = 1)
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
                Toast.makeText(
                    context,
                    tr(appLanguage, "Đã áp dụng cài đặt khung hình mới vào Pop-up!", "Applied new framing to Pop-up!", "已将新画面构图应用至弹窗！"),
                    Toast.LENGTH_SHORT
                ).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = tr(appLanguage, "Áp dụng", "Apply", "应用"),
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Compact Framing Card
        FramingSlidersCard(
            appLanguage = appLanguage,
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
            title = tr(appLanguage, "Âm thanh tai nghe", "Headset Sound", "耳机音效"),
            soundEnabled = soundEnabled,
            onSoundEnabledChange = onSoundEnabledChange,
            volumeProvider = { videoVolume },
            onVolumeChange = onVolumeChange,
            onVolumeChangeFinished = {
                prefs.edit().putFloat("video_volume", videoVolume).apply()
            },
            appLanguage = appLanguage,
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
                text = tr(appLanguage, "Kiểm tra Pop-up Tai nghe", "Test Headset Pop-up", "测试耳机弹窗"),
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
    appLanguage: String = "vi",
    isEnglish: Boolean = appLanguage == "en",
    presetLoadKey: Int = 0,
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
    var stagedUri by remember(initialMediaUri, presetLoadKey) { mutableStateOf(initialMediaUri.ifEmpty { chargingPrefs.getString("charging_media_uri", "") ?: "" }) }
    var stagedType by remember(initialMediaUri, presetLoadKey) { mutableStateOf(chargingPrefs.getString("custom_media_type", "image") ?: "image") }
    var stagedZoom by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(chargingPrefs.getFloat("charging_zoom", 1.0f)) }
    var stagedOffsetX by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(chargingPrefs.getFloat("charging_offset_x", 0f)) }
    var stagedOffsetY by remember(initialMediaUri, presetLoadKey) { mutableFloatStateOf(chargingPrefs.getFloat("charging_offset_y", 0f)) }
    
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

    var showWattage by remember(presetLoadKey) { mutableStateOf(chargingPrefs.getBoolean("show_wattage", true)) }
    var showTemp by remember(presetLoadKey) { mutableStateOf(chargingPrefs.getBoolean("show_temp", true)) }
    var showBattery by remember(presetLoadKey) { mutableStateOf(chargingPrefs.getBoolean("show_battery", true)) }
    var showVoltage by remember(presetLoadKey) { mutableStateOf(chargingPrefs.getBoolean("show_voltage", true)) }
    var showParticles by remember(presetLoadKey) { mutableStateOf(chargingPrefs.getBoolean("show_charging_particles", true)) }

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
                text = tr(appLanguage, "Xem trước", "Preview", "预览"),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = tr(appLanguage, "Đặt lại", "Reset", "重置"),
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
                    text = tr(
                        appLanguage,
                        "Chưa chọn Media\n(Bấm nút dưới để chọn)",
                        "No Media Selected\n(Tap button below to choose)",
                        "未选择媒体\n(点击下方按钮选择)"
                    ),
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
                    text = tr(appLanguage, "Đã cắm sạc", "Charging Connected", "已连接充电"),
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
                        Text(tr(appLanguage, "Cài đặt", "Settings", "设置"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(accentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tr(appLanguage, "Xong", "Done", "完成"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                Text(tr(appLanguage, "Chọn Media", "Select Media", "选择媒体"), fontSize = 13.sp, maxLines = 1)
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
                        text = if (isPlaying) tr(appLanguage, "Dừng", "Stop", "停止") else tr(appLanguage, "Phát", "Play", "播放"),
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
                    Text(tr(appLanguage, "Xóa", "Delete", "删除"), fontSize = 13.sp, maxLines = 1)
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
                Toast.makeText(
                    context,
                    tr(appLanguage, "Đã áp dụng cài đặt khung hình sạc mới vào Pop-up!", "Applied new framing to Charging Pop-up!", "已将充电画面新构图应用至弹窗！"),
                    Toast.LENGTH_SHORT
                ).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = tr(appLanguage, "Áp dụng", "Apply", "应用"),
                color = Color.Black,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Compact Framing Card
        FramingSlidersCard(
            appLanguage = appLanguage,
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
        ExpandableCard(title = tr(appLanguage, "Thông tin sạc", "Charging Info", "充电信息"), initialExpanded = false) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = tr(appLanguage, "Công suất (W)", "Wattage (W)", "功率 (W)"), fontSize = 14.sp)
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
                    Text(text = tr(appLanguage, "Nhiệt độ pin", "Battery Temp", "电池温度"), fontSize = 14.sp)
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
                    Text(text = tr(appLanguage, "Phần trăm pin (%)", "Battery Percentage (%)", "电池电量百分比 (%)"), fontSize = 14.sp)
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
                    Text(text = tr(appLanguage, "Điện áp (V)", "Voltage (V)", "电压 (V)"), fontSize = 14.sp)
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
                    Text(text = tr(appLanguage, "Hiệu ứng vòng tròn SuperVOOC", "SuperVOOC Ring Effect", "SuperVOOC 圆环动效"), fontSize = 14.sp)
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
            title = tr(appLanguage, "Âm thanh sạc", "Charging Sound", "充电音效"),
            soundEnabled = soundEnabled,
            onSoundEnabledChange = onSoundEnabledChange,
            volumeProvider = { videoVolume },
            onVolumeChange = onVolumeChange,
            onVolumeChangeFinished = {
                chargingPrefs.edit().putFloat("charging_volume", videoVolume).apply()
            },
            appLanguage = appLanguage,
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
                text = tr(appLanguage, "Kiểm tra Pop-up Sạc pin", "Test Charging Pop-up", "测试充电弹窗"),
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
    appLanguage: String = "vi",
    isEnglish: Boolean = appLanguage == "en",
    zoomProvider: () -> Float,
    onZoomChange: (Float) -> Unit,
    offsetXProvider: () -> Float,
    onOffsetXChange: (Float) -> Unit,
    offsetYProvider: () -> Float,
    onOffsetYChange: (Float) -> Unit,
    onResetFraming: () -> Unit
) {
    ExpandableCard(title = tr(appLanguage, "Kích thước và vị trí", "Size & Position", "画面位置与缩放"), initialExpanded = false) {
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
                text = tr(appLanguage, "Đặt lại Khung hình", "Reset Framing", "重置构图"),
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
    appLanguage: String = "vi",
    isEnglish: Boolean = appLanguage == "en"
) {
    ExpandableCard(title = title, initialExpanded = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(appLanguage, "Bật âm thanh khi phát", "Enable sound on play", "播放时开启声音"),
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
                        text = tr(appLanguage, "Âm lượng: ${(vol * 100).toInt()}%", "Volume: ${(vol * 100).toInt()}%", "音量: ${(vol * 100).toInt()}%"),
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
    appLanguage: String = "vi",
    onDismiss: () -> Unit,
    onColorSelected: (PaletteColorItem) -> Unit
) {
    // 0: Spectrum mode (Bảng màu), 1: Preset Grid (Mẫu có sẵn)
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

    val dialogTitle = tr(appLanguage, "Bảng chọn màu", "Color Palette", "调色板")
    val modeTitles = when (appLanguage) {
        "zh" -> listOf("调色板", "预设颜色")
        "en" -> listOf("Color Palette", "Presets")
        else -> listOf("Bảng màu", "Mẫu có sẵn")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dialogTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
                // Mode Switcher Tab
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                Text(tr(appLanguage, "Chọn", "Select", "选择"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr(appLanguage, "Hủy", "Cancel", "取消"))
            }
        }
    )
}

@Composable
fun ColorSettingRow(
    title: String,
    currentColorHex: String,
    buttonText: String = "Đổi màu",
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
                    text = buttonText,
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
                        text = tr(appLanguage, "QUAN TRỌNG: Vui lòng bật \"Cho phép hiển thị trên các ứng dụng khác\"", "IMPORTANT: Please enable Display over other apps permission", "重要提示：请开启“显示在其他应用上层”权限"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onOverlayPermissionClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(tr(appLanguage, "Đi tới Cài đặt", "Go to Settings", "前往设置"))
                    }
                }
            }
        }

        // Standardized Guide Card with OEM disclaimer
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = tr(appLanguage, "Hướng dẫn", "Guide", "指南"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                // Phone OEM Disclaimer
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = tr(
                            appLanguage,
                            "Lưu ý: Tùy thuộc vào từng dòng máy (Xiaomi, Samsung, OPPO, Vivo...), tên gọi và vị trí các mục cài đặt có thể sẽ khác nhau.",
                            "Note: Depending on your device brand (Samsung, Xiaomi, OPPO, Vivo...), setting names and locations may vary slightly.",
                            "提示：受不同手机品牌（小米、三星、OPPO、vivo 等）影响，各设置项名称及位置可能略有差异。"
                        ),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Text(
                    text = tr(
                        appLanguage,
                        "1. Khóa ứng dụng trong Đa nhiệm (Recent Apps):\nMở màn hình Đa nhiệm → Nhấn giữ hoặc bấm vào menu 3 chấm trên thẻ ứng dụng Apeiron → Chọn biểu tượng \"Khóa\" để ngăn hệ thống tự động xóa bộ nhớ.",
                        "1. Lock App in Recent Apps:\nOpen Recent Apps overview → Long-press or tap 3 dots on Apeiron card → Select \"Lock\" icon to prevent memory clearance.",
                        "1. 锁定后台多任务卡片：\n打开多任务后台界面 → 长按或点击 Apeiron 卡片右上角菜单 → 选择“加锁”图标，防止被系统清理后台。"
                    ),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = 17.sp
                )
                Text(
                    text = tr(
                        appLanguage,
                        "2. Cho phép hoạt động dưới nền không hạn chế:\nVào Cài đặt → Pin & Hiệu suất (hoặc Thông tin ứng dụng) → Tối ưu hóa pin / Sử dụng pin → Chuyển sang \"Không giới hạn\" hoặc \"Cho phép chạy ngầm\".",
                        "2. Unrestricted Battery Usage:\nOpen Settings → Battery (or App Info) → Battery Optimization → Set to \"Unrestricted\" or \"Allow background activity\".",
                        "2. 电池优化设为无限制：\n进入系统设置 → 电池与性能（或应用详情） → 电池优化/省电策略 → 选择“无限制”或“允许后台活动”。"
                    ),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = 17.sp
                )
                Text(
                    text = tr(
                        appLanguage,
                        "3. Cấp quyền Tự khởi chạy (Autostart):\nVào Cài đặt → Ứng dụng (hoặc Quản lý quyền) → Apeiron → Bật quyền \"Tự khởi chạy\" và \"Chạy dưới nền\" để ứng dụng luôn sẵn sàng kích hoạt pop-up khi cắm tai nghe hoặc dây sạc.",
                        "3. Enable Autostart & Background Launch:\nOpen Settings → Apps & Permissions → Apeiron → Enable \"Autostart\" and \"Run in background\" for instant pop-up triggers.",
                        "3. 开启自启动与后台运行权限：\n进入系统设置 → 应用管理/权限管理 → Apeiron → 开启“自启动”与“允许后台运行”，确保插拔设备时能即刻弹出界面。"
                    ),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = 17.sp
                )
            }
        }

        // 1. Color Customization Card
        ExpandableCard(title = tr(appLanguage, "Màu sắc Pop-up", "Pop-up Colors", "弹窗颜色设置"), initialExpanded = false) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var headsetColor by remember(refreshKey) { mutableStateOf(prefs.getString("headset_popup_color", "blue") ?: "blue") }
                var chargingColor by remember(refreshKey) { mutableStateOf(chargingPrefs.getString("charging_popup_color", "cyan") ?: "cyan") }
                var chargingRingColor by remember(refreshKey) { mutableStateOf(chargingPrefs.getString("charging_ring_color", "cyan") ?: "cyan") }
                
                var showHeadsetPalette by remember { mutableStateOf(false) }
                var showChargingPalette by remember { mutableStateOf(false) }
                var showChargingRingPalette by remember { mutableStateOf(false) }

                ColorSettingRow(
                    title = tr(appLanguage, "Màu sắc Pop-up Tai nghe", "Headset Pop-up Color", "耳机弹窗主题色"),
                    currentColorHex = headsetColor,
                    buttonText = tr(appLanguage, "Đổi màu", "Pick Color", "取色"),
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
                        appLanguage = appLanguage,
                        onDismiss = { showHeadsetPalette = false },
                        onColorSelected = { paletteItem ->
                            headsetColor = paletteItem.hexString
                            prefs.edit().putString("headset_popup_color", paletteItem.hexString).apply()
                            showHeadsetPalette = false
                        }
                    )
                }

                ColorSettingRow(
                    title = tr(appLanguage, "Màu sắc Pop-up Sạc pin", "Charging Pop-up Color", "充电弹窗主题色"),
                    currentColorHex = chargingColor,
                    buttonText = tr(appLanguage, "Đổi màu", "Pick Color", "取色"),
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
                        appLanguage = appLanguage,
                        onDismiss = { showChargingPalette = false },
                        onColorSelected = { paletteItem ->
                            chargingColor = paletteItem.hexString
                            chargingPrefs.edit().putString("charging_popup_color", paletteItem.hexString).apply()
                            showChargingPalette = false
                        }
                    )
                }

                ColorSettingRow(
                    title = tr(appLanguage, "Màu vòng tròn & Hiệu ứng sạc", "Charging Ring & Particles", "充电圆环与粒子特效颜色"),
                    currentColorHex = chargingRingColor,
                    buttonText = tr(appLanguage, "Đổi màu", "Pick Color", "取色"),
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
                        appLanguage = appLanguage,
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
        ExpandableCard(title = tr(appLanguage, "Màu nền Pop-up", "Pop-up Background", "弹窗背景颜色")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var bgStyle by remember(refreshKey) { mutableStateOf(prefs.getString("popup_bg_style", "default") ?: "default") }
                var customBgColor by remember(refreshKey) { mutableStateOf(prefs.getString("popup_bg_color", "") ?: "") }
                var showBgPalette by remember { mutableStateOf(false) }

                Text(
                    text = tr(appLanguage, "Kiểu nền mẫu:", "Preset Style:", "预设背景样式："),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                
                val bgOptions = listOf(
                    "default" to tr(appLanguage, "Mặc định", "Default", "默认"),
                    "black" to tr(appLanguage, "Đen", "Black", "黑色"),
                    "navy" to tr(appLanguage, "Navy", "Navy", "藏青"),
                    "carbon" to tr(appLanguage, "Carbon", "Carbon", "碳黑")
                )

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
                    title = tr(appLanguage, "Màu nền tùy chỉnh", "Custom Background Color", "自定义背景颜色"),
                    currentColorHex = if (customBgColor.isEmpty()) "#1E1E2C" else customBgColor,
                    buttonText = tr(appLanguage, "Đổi màu", "Pick Color", "取色"),
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
                        appLanguage = appLanguage,
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
        ExpandableCard(title = tr(appLanguage, "Giao diện, ngôn ngữ", "Theme & Language", "界面与语言")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = tr(appLanguage, "Ngôn ngữ giao diện", "App Language", "界面语言"),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                
                SegmentedSelector(
                    options = listOf(
                        "vi" to "Tiếng Việt",
                        "en" to "English",
                        "zh" to "简体中文"
                    ),
                    selectedKey = appLanguage,
                    onOptionSelected = { onLanguageChange(it) }
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tr(appLanguage, "Giao diện ứng dụng", "App Theme Mode", "应用主题模式"),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                
                val themeOptions = listOf(
                    "system" to tr(appLanguage, "Hệ thống", "System", "跟随系统"),
                    "light" to tr(appLanguage, "Sáng", "Light", "浅色"),
                    "dark" to tr(appLanguage, "Tối", "Dark", "深色")
                )

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
                    Text(text = tr(appLanguage, "Bật Pop-up Tai nghe", "Enable Headset Pop-up", "开启耳机弹窗"), fontSize = 13.sp, maxLines = 1)
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
                    Text(text = tr(appLanguage, "Bật Pop-up Sạc pin", "Enable Charging Pop-up", "开启充电弹窗"), fontSize = 13.sp, maxLines = 1)
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
                    Text(text = tr(appLanguage, "Bật Pop-up màn hình ngang", "Enable Landscape Pop-up", "开启横屏弹窗"), fontSize = 13.sp, maxLines = 1)
                    Switch(
                        checked = enableLandscape,
                        onCheckedChange = {
                            enableLandscape = it
                            prefs.edit().putBoolean("enable_landscape_popup", it).apply()
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }
                    )
                }

                var enableBlurScrim by remember { mutableStateOf(prefs.getBoolean("enable_blur_scrim", false)) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = tr(appLanguage, "Làm mờ nền hậu cảnh (Ảnh tĩnh)", "Blur Background Scrim (Static only)", "背景虚化 (仅限静态)"), fontSize = 13.sp, maxLines = 1)
                    Switch(
                        checked = enableBlurScrim,
                        onCheckedChange = {
                            enableBlurScrim = it
                            prefs.edit().putBoolean("enable_blur_scrim", it).apply()
                        },
                        modifier = Modifier.graphicsLayer { scaleX = 0.85f; scaleY = 0.85f }
                    )
                }
            }
        }

        // 3. Multi-slot Presets Card
        ExpandableCard(title = tr(appLanguage, "Quản lý Preset", "Preset Manager", "预设配置管理")) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (slot in 1..3) {
                    val slotExists = remember(refreshKey) { prefs.getBoolean("preset_${slot}_exists", false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Preset $slot ${if (slotExists) tr(appLanguage, "(Đã lưu)", "(Saved)", "(已保存)") else tr(appLanguage, "(Trống)", "(Empty)", "(未配置)")}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val currentHeadsetUri = prefs.getString("custom_media_uri", "") ?: ""
                                    val currentHeadsetType = prefs.getString("custom_media_type", "image") ?: "image"
                                    val savedHeadsetFilePath = if (currentHeadsetUri.isNotEmpty()) {
                                        val hExt = if (currentHeadsetType == "video") "mp4" else "jpg"
                                        val destFile = File(context.filesDir, "preset_${slot}_headset.$hExt")
                                        if (copyFileOrUri(context, currentHeadsetUri, destFile)) {
                                            destFile.absolutePath
                                        } else ""
                                    } else ""

                                    val currentChargingUri = chargingPrefs.getString("charging_media_uri", "") ?: ""
                                    val currentChargingType = chargingPrefs.getString("custom_media_type", "image") ?: "image"
                                    val isChargingVideo = chargingPrefs.getBoolean("is_charging_video", currentChargingType == "video")
                                    val savedChargingFilePath = if (currentChargingUri.isNotEmpty()) {
                                        val cExt = if (isChargingVideo) "mp4" else "jpg"
                                        val destFile = File(context.filesDir, "preset_${slot}_charging.$cExt")
                                        if (copyFileOrUri(context, currentChargingUri, destFile)) {
                                            destFile.absolutePath
                                        } else ""
                                    } else ""

                                    prefs.edit()
                                        .putBoolean("preset_${slot}_exists", true)
                                        .putString("preset_${slot}_headset_file", savedHeadsetFilePath)
                                        .putString("preset_${slot}_headset_type", currentHeadsetType)
                                        .putString("preset_${slot}_device_name", prefs.getString("device_custom_name", deviceName) ?: deviceName)
                                        .putFloat("preset_${slot}_zoom", prefs.getFloat("image_zoom", 1.0f))
                                        .putFloat("preset_${slot}_offset_x", prefs.getFloat("image_offset_x", 0f))
                                        .putFloat("preset_${slot}_offset_y", prefs.getFloat("image_offset_y", 0f))
                                        .putFloat("preset_${slot}_offset_x_norm", prefs.getFloat("image_offset_x_normalized", 0f))
                                        .putFloat("preset_${slot}_offset_y_norm", prefs.getFloat("image_offset_y_normalized", 0f))
                                        .putBoolean("preset_${slot}_sound", prefs.getBoolean("video_sound_enabled", false))
                                        .putFloat("preset_${slot}_volume", prefs.getFloat("video_volume", 0.5f))
                                        .putString("preset_${slot}_headset_color", prefs.getString("headset_popup_color", "blue") ?: "blue")
                                        .putInt("preset_${slot}_card_height", popupCardHeight)
                                        .putInt("preset_${slot}_card_width", prefs.getInt("popup_card_width", 0))
                                        .putString("preset_${slot}_bg_style", prefs.getString("popup_bg_style", "default") ?: "default")
                                        .putString("preset_${slot}_bg_color", prefs.getString("popup_bg_color", "") ?: "")
                                        .apply()

                                    chargingPrefs.edit()
                                        .putString("preset_${slot}_charging_file", savedChargingFilePath)
                                        .putString("preset_${slot}_charging_type", currentChargingType)
                                        .putBoolean("preset_${slot}_is_charging_video", isChargingVideo)
                                        .putFloat("preset_${slot}_charging_zoom", chargingPrefs.getFloat("charging_zoom", 1.0f))
                                        .putFloat("preset_${slot}_charging_offset_x", chargingPrefs.getFloat("charging_offset_x", 0f))
                                        .putFloat("preset_${slot}_charging_offset_y", chargingPrefs.getFloat("charging_offset_y", 0f))
                                        .putFloat("preset_${slot}_charging_offset_x_norm", chargingPrefs.getFloat("charging_offset_x_normalized", 0f))
                                        .putFloat("preset_${slot}_charging_offset_y_norm", chargingPrefs.getFloat("charging_offset_y_normalized", 0f))
                                        .putBoolean("preset_${slot}_charging_sound", chargingPrefs.getBoolean("charging_sound_enabled", false))
                                        .putFloat("preset_${slot}_charging_volume", chargingPrefs.getFloat("charging_volume", 0.5f))
                                        .putString("preset_${slot}_charging_color", chargingPrefs.getString("charging_popup_color", "cyan") ?: "cyan")
                                        .putString("preset_${slot}_charging_ring_color", chargingPrefs.getString("charging_ring_color", "cyan") ?: "cyan")
                                        .putBoolean("preset_${slot}_show_wattage", chargingPrefs.getBoolean("show_wattage", true))
                                        .putBoolean("preset_${slot}_show_temp", chargingPrefs.getBoolean("show_temp", true))
                                        .putBoolean("preset_${slot}_show_battery", chargingPrefs.getBoolean("show_battery", true))
                                        .putBoolean("preset_${slot}_show_voltage", chargingPrefs.getBoolean("show_voltage", true))
                                        .putBoolean("preset_${slot}_show_charging_particles", chargingPrefs.getBoolean("show_charging_particles", true))
                                        .apply()

                                    Toast.makeText(context, tr(appLanguage, "Đã lưu Preset $slot thành công!", "Preset $slot saved successfully!", "已成功保存预设 $slot！"), Toast.LENGTH_SHORT).show()
                                    refreshKey++
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(tr(appLanguage, "Lưu", "Save", "保存"), fontSize = 11.sp, maxLines = 1)
                            }

                            if (slotExists) {
                                OutlinedButton(
                                    onClick = {
                                        val hFilePath = prefs.getString("preset_${slot}_headset_file", "") ?: ""
                                        val hType = prefs.getString("preset_${slot}_headset_type", "image") ?: "image"
                                        val newHeadsetUri = if (hFilePath.isNotEmpty() && File(hFilePath).exists()) {
                                            val hExt = if (hType == "video") "mp4" else "jpg"
                                            val destFile = File(context.filesDir, "headset_media_file.$hExt")
                                            if (copyFileOrUri(context, hFilePath, destFile)) {
                                                Uri.fromFile(destFile).toString()
                                            } else {
                                                Uri.fromFile(File(hFilePath)).toString()
                                            }
                                        } else {
                                            ""
                                        }

                                        val dName = prefs.getString("preset_${slot}_device_name", "Audio USB") ?: "Audio USB"
                                        val hZoom = prefs.getFloat("preset_${slot}_zoom", 1.0f)
                                        val hOffX = prefs.getFloat("preset_${slot}_offset_x", 0f)
                                        val hOffY = prefs.getFloat("preset_${slot}_offset_y", 0f)
                                        val hOffXNorm = prefs.getFloat("preset_${slot}_offset_x_norm", 0f)
                                        val hOffYNorm = prefs.getFloat("preset_${slot}_offset_y_norm", 0f)
                                        val hSound = prefs.getBoolean("preset_${slot}_sound", false)
                                        val hVol = prefs.getFloat("preset_${slot}_volume", 0.5f)
                                        val hColor = prefs.getString("preset_${slot}_headset_color", "blue") ?: "blue"
                                        val savedCardHeight = prefs.getInt("preset_${slot}_card_height", 440)
                                        val savedCardWidth = prefs.getInt("preset_${slot}_card_width", 0)
                                        val savedBgStyle = prefs.getString("preset_${slot}_bg_style", "default") ?: "default"
                                        val savedBgColor = prefs.getString("preset_${slot}_bg_color", "") ?: ""

                                        prefs.edit()
                                            .putString("custom_media_uri", newHeadsetUri)
                                            .putString("custom_media_type", hType)
                                            .putString("device_custom_name", dName)
                                            .putFloat("image_zoom", hZoom)
                                            .putFloat("image_offset_x", hOffX)
                                            .putFloat("image_offset_y", hOffY)
                                            .putFloat("image_offset_x_normalized", hOffXNorm)
                                            .putFloat("image_offset_y_normalized", hOffYNorm)
                                            .putBoolean("video_sound_enabled", hSound)
                                            .putFloat("video_volume", hVol)
                                            .putString("headset_popup_color", hColor)
                                            .putInt("popup_card_height", savedCardHeight)
                                            .putInt("popup_card_width", savedCardWidth)
                                            .putString("popup_bg_style", savedBgStyle)
                                            .putString("popup_bg_color", savedBgColor)
                                            .apply()

                                        val cFilePath = chargingPrefs.getString("preset_${slot}_charging_file", "") ?: ""
                                        val cType = chargingPrefs.getString("preset_${slot}_charging_type", "image") ?: "image"
                                        val isCVideo = chargingPrefs.getBoolean("preset_${slot}_is_charging_video", cType == "video")
                                        val newChargingUri = if (cFilePath.isNotEmpty() && File(cFilePath).exists()) {
                                            val cExt = if (isCVideo) "mp4" else "jpg"
                                            val destFile = File(context.filesDir, "charging_media_file.$cExt")
                                            if (copyFileOrUri(context, cFilePath, destFile)) {
                                                Uri.fromFile(destFile).toString()
                                            } else {
                                                Uri.fromFile(File(cFilePath)).toString()
                                            }
                                        } else {
                                            ""
                                        }

                                        val cZoom = chargingPrefs.getFloat("preset_${slot}_charging_zoom", 1.0f)
                                        val cOffX = chargingPrefs.getFloat("preset_${slot}_charging_offset_x", 0f)
                                        val cOffY = chargingPrefs.getFloat("preset_${slot}_charging_offset_y", 0f)
                                        val cOffXNorm = chargingPrefs.getFloat("preset_${slot}_charging_offset_x_norm", 0f)
                                        val cOffYNorm = chargingPrefs.getFloat("preset_${slot}_charging_offset_y_norm", 0f)
                                        val cSound = chargingPrefs.getBoolean("preset_${slot}_charging_sound", false)
                                        val cVol = chargingPrefs.getFloat("preset_${slot}_charging_volume", 0.5f)
                                        val cColor = chargingPrefs.getString("preset_${slot}_charging_color", "cyan") ?: "cyan"
                                        val cRingColor = chargingPrefs.getString("preset_${slot}_charging_ring_color", "cyan") ?: "cyan"
                                        val sW = chargingPrefs.getBoolean("preset_${slot}_show_wattage", true)
                                        val sT = chargingPrefs.getBoolean("preset_${slot}_show_temp", true)
                                        val sB = chargingPrefs.getBoolean("preset_${slot}_show_battery", true)
                                        val sV = chargingPrefs.getBoolean("preset_${slot}_show_voltage", true)
                                        val sP = chargingPrefs.getBoolean("preset_${slot}_show_charging_particles", true)

                                        chargingPrefs.edit()
                                            .putString("charging_media_uri", newChargingUri)
                                            .putString("custom_media_type", cType)
                                            .putBoolean("is_charging_video", isCVideo)
                                            .putFloat("charging_zoom", cZoom)
                                            .putFloat("charging_offset_x", cOffX)
                                            .putFloat("charging_offset_y", cOffY)
                                            .putFloat("charging_offset_x_normalized", cOffXNorm)
                                            .putFloat("charging_offset_y_normalized", cOffYNorm)
                                            .putBoolean("charging_sound_enabled", cSound)
                                            .putFloat("charging_volume", cVol)
                                            .putString("charging_popup_color", cColor)
                                            .putString("charging_ring_color", cRingColor)
                                            .putBoolean("show_wattage", sW)
                                            .putBoolean("show_temp", sT)
                                            .putBoolean("show_battery", sB)
                                            .putBoolean("show_voltage", sV)
                                            .putBoolean("show_charging_particles", sP)
                                            .apply()

                                        onDeviceNameChange(dName)
                                        onPopupCardHeightChange(savedCardHeight)
                                        onPresetLoaded()
                                        refreshKey++
                                        Toast.makeText(context, tr(appLanguage, "Đã tải Preset $slot thành công!", "Preset $slot loaded successfully!", "已成功载入预设 $slot！"), Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(tr(appLanguage, "Tải", "Load", "载入"), fontSize = 11.sp, maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val hFilePath = prefs.getString("preset_${slot}_headset_file", "") ?: ""
                                        if (hFilePath.isNotEmpty()) File(hFilePath).delete()
                                        val cFilePath = chargingPrefs.getString("preset_${slot}_charging_file", "") ?: ""
                                        if (cFilePath.isNotEmpty()) File(cFilePath).delete()

                                        prefs.edit()
                                            .remove("preset_${slot}_exists")
                                            .remove("preset_${slot}_headset_file")
                                            .remove("preset_${slot}_headset_type")
                                            .remove("preset_${slot}_device_name")
                                            .remove("preset_${slot}_zoom")
                                            .remove("preset_${slot}_offset_x")
                                            .remove("preset_${slot}_offset_y")
                                            .remove("preset_${slot}_offset_x_norm")
                                            .remove("preset_${slot}_offset_y_norm")
                                            .remove("preset_${slot}_sound")
                                            .remove("preset_${slot}_volume")
                                            .remove("preset_${slot}_headset_color")
                                            .remove("preset_${slot}_card_height")
                                            .remove("preset_${slot}_card_width")
                                            .remove("preset_${slot}_bg_style")
                                            .remove("preset_${slot}_bg_color")
                                            .apply()

                                        chargingPrefs.edit()
                                            .remove("preset_${slot}_charging_file")
                                            .remove("preset_${slot}_charging_type")
                                            .remove("preset_${slot}_is_charging_video")
                                            .remove("preset_${slot}_charging_zoom")
                                            .remove("preset_${slot}_charging_offset_x")
                                            .remove("preset_${slot}_charging_offset_y")
                                            .remove("preset_${slot}_charging_offset_x_norm")
                                            .remove("preset_${slot}_charging_offset_y_norm")
                                            .remove("preset_${slot}_charging_sound")
                                            .remove("preset_${slot}_charging_volume")
                                            .remove("preset_${slot}_charging_color")
                                            .remove("preset_${slot}_charging_ring_color")
                                            .remove("preset_${slot}_show_wattage")
                                            .remove("preset_${slot}_show_temp")
                                            .remove("preset_${slot}_show_battery")
                                            .remove("preset_${slot}_show_voltage")
                                            .remove("preset_${slot}_show_charging_particles")
                                            .apply()

                                        Toast.makeText(context, tr(appLanguage, "Đã xóa Preset $slot!", "Preset $slot deleted!", "已删除预设 $slot！"), Toast.LENGTH_SHORT).show()
                                        refreshKey++
                                    },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(tr(appLanguage, "Xóa", "Delete", "删除"), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Device Name & Framing Card - NO TEXT CLIPPING ON RESET BUTTON!
        ExpandableCard(title = tr(appLanguage, "Thiết bị, kích thước", "Device & Dimensions", "设备与尺寸")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = tr(appLanguage, "Tên thiết bị hiển thị", "Display Device Name", "显示设备名称"), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                var textState by remember(deviceName, refreshKey) { mutableStateOf(deviceName) }
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
                        Text(text = tr(appLanguage, "Chiều cao Pop-up: ${popupCardHeight}dp", "Pop-up Height: ${popupCardHeight}dp", "弹窗高度: ${popupCardHeight}dp"), fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
                        Text(
                            text = tr(appLanguage, "Đặt lại", "Reset", "重置"),
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

                var popupCardWidth by remember(refreshKey) { mutableIntStateOf(prefs.getInt("popup_card_width", 0)) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (popupCardWidth > 0) tr(appLanguage, "Độ rộng Pop-up: ${popupCardWidth}dp", "Pop-up Width: ${popupCardWidth}dp", "弹窗宽度: ${popupCardWidth}dp")
                            else tr(appLanguage, "Độ rộng Pop-up: Toàn màn hình", "Pop-up Width: Full Width", "弹窗宽度: 全屏"),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Text(
                            text = tr(appLanguage, "Đặt lại", "Reset", "重置"),
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
                text = tr(appLanguage, "Phát triển bởi Nguyễn Chí Thanh", "Developed by Nguyen Chi Thanh", "由阮志成 (Nguyen Chi Thanh) 开发"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
