package com.gptvideo2anime

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.gptvideo2anime.model.ModelManager
import com.gptvideo2anime.pipeline.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF07080D)
private val Card = Color(0xFF11131C)
private val CardLight = Color(0xFF181B27)
private val Accent = Color(0xFF9D62FF)
private val AccentBlue = Color(0xFF5F86FF)
private val White = Color(0xFFF8F7FF)
private val Muted = Color(0xFF9698A9)
private val Green = Color(0xFF65D88A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Video2AnimeApp()
        }
    }
}

@Composable
private fun Video2AnimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val modelManager = remember {
        ModelManager(context)
    }

    val videoProcessor = remember {
        VideoProcessor(context)
    }

    var selectedVideo by remember {
        mutableStateOf<Uri?>(null)
    }

    var resultVideo by remember {
        mutableStateOf<Uri?>(null)
    }

    var strength by remember {
        mutableIntStateOf(40)
    }

    var progress by remember {
        mutableFloatStateOf(0f)
    }

    var processing by remember {
        mutableStateOf(false)
    }

    var modelReady by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("Choose a video to begin")
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            if (uri == null) return@rememberLauncherForActivityResult

            selectedVideo = uri
            resultVideo = null
            progress = 0f
            error = null
            status = "Video selected"
        }

    LaunchedEffect(Unit) {
        try {
            modelManager.ensureModels()
            modelReady = true
            status =
                if (selectedVideo == null) {
                    "Choose a video to begin"
                } else {
                    "Ready to process"
                }
        } catch (exception: Exception) {
            error = exception.message ?: "Unable to install model."
            status = "Model setup failed"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            AppHeader()

            if (resultVideo != null) {

                ResultScreen(
                    uri = resultVideo!!,
                    onReset = {
                        resultVideo = null
                        selectedVideo = null
                        progress = 0f
                        status = "Choose a video to begin"
                    }
                )

            } else {

                VideoPickerCard(
                    selected = selectedVideo != null,
                    onClick = {
                        if (!processing) picker.launch("video/*")
                    }
                )

                StrengthCard(
                    strength = strength,
                    enabled = !processing,
                    onStrengthChanged = { strength = it }
                )

                AnimatedVisibility(visible = processing) {
                    ProgressCard(
                        progress = progress,
                        status = status
                    )
                }

                if (!processing) {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (!processing && error != null) {
                    ErrorCard(message = error!!)
                }

                if (!processing) {
                    Spacer(modifier = Modifier.weight(0.2f))
                }

                ProcessButton(
                    enabled = selectedVideo != null && modelReady && !processing,
                    processing = processing,
                    onClick = {

                        val input = selectedVideo ?: return@ProcessButton

                        error = null
                        processing = true
                        progress = 0f
                        status = "Preparing video..."

                        // PART 2 STARTS HERE
                        // Replace from this point with Part 2.
                                                scope.launch(Dispatchers.IO) {
                            try {
                                val result = videoProcessor.processVideo(
                                    uri = input,
                                    strengthPercent = strength,
                                    onProgress = { current: Long, total: Long, stage: String ->
                                        val value =
                                            if (total > 0L) {
                                                (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }

                                        scope.launch(Dispatchers.Main) {
                                            progress = value
                                            status = stage
                                        }
                                    }
                                )

                                withContext(Dispatchers.Main) {
                                    resultVideo =
                                        Uri.fromFile(
                                            java.io.File(result.outputPath)
                                        )

                                    progress = 1f
                                    status = "Your anime video is ready"
                                    processing = false
                                }

                            } catch (exception: Exception) {
                                withContext(Dispatchers.Main) {
                                    processing = false
                                    status = "Processing failed"
                                    error =
                                        exception.message
                                            ?: "Unknown processing error."
                                }
                            }
                        }
                    }
                )

                if (!processing) {
                    Text(
                        text = "Everything runs offline on your device",
                        color = Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Accent, AccentBlue)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(27.dp)
            )
        }

        Spacer(modifier = Modifier.width(13.dp))

        Column {
            Text(
                text = "Video2Anime",
                color = White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Hayao Studio",
                color = Muted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0xFF102219))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "OFFLINE",
                color = Green,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VideoPickerCard(
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(25.dp))
            .background(Card)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Accent.copy(alpha = 0.45f),
                        AccentBlue.copy(alpha = 0.25f)
                    )
                ),
                shape = RoundedCornerShape(25.dp)
            )
            .clickable(onClick = onClick)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Accent.copy(alpha = 0.25f),
                            AccentBlue.copy(alpha = 0.18f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.VideoFile,
                contentDescription = null,
                tint = White,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = if (selected) "VIDEO SELECTED" else "CHOOSE VIDEO",
            color = White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text =
                if (selected) {
                    "Tap to choose another video"
                } else {
                    "MP4, MOV, MKV and supported video formats"
                },
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StrengthCard(
    strength: Int,
    enabled: Boolean,
    onStrengthChanged: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(18.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column {
                Text(
                    text = "HAYAO STRENGTH",
                    color = White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "Controls the anime blend intensity",
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "$strength%",
                color = Accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            listOf(30, 40, 50, 60).forEach { value ->
                StrengthChip(
                    value = value,
                    selected = value == strength,
                    enabled = enabled,
                    onClick = { onStrengthChanged(value) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StrengthChip(
    value: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) Accent else CardLight)
            .border(
                width = 1.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(13.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$value%",
            color = if (selected) Color.White else Muted,
            fontSize = 13.sp,
            fontWeight =
                if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ProgressCard(
    progress: Float,
    status: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Card)
            .padding(18.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PROCESSING",
                    color = White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = status,
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }

            Text(
                text = "${(progress * 100).toInt()}%",
                color = Accent,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(13.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape),
            color = Accent,
            trackColor = CardLight
        )
    }
}

@Composable
private fun ProcessButton(
    enabled: Boolean,
    processing: Boolean,
    onClick: () -> Unit
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(59.dp),
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            disabledContainerColor = CardLight
        ),
        onClick = onClick
    ) {

        if (processing) {

            CircularProgressIndicator(
                modifier = Modifier.size(21.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(text = "PROCESSING...")

        } else {

            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(9.dp))

            Text(
                text = "PROCESS VIDEO",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF29151A))
            .padding(14.dp)
    ) {
        Text(
            text = message,
            color = Color(0xFFFF9B9B),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ResultScreen(
    uri: Uri,
    onReset: () -> Unit
) {
    val context =
        androidx.compose.ui.platform.LocalContext.current

    val player =
        remember(uri) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
        }

    androidx.compose.runtime.DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Green,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(9.dp))

            Column {
                Text(
                    text = "Anime video ready",
                    color = White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Your final result is below",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(23.dp))
                .background(Color.Black)
        ) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                    }
                },
                update = { view ->
                    view.player = player
                }
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "HAYAO",
                    color = White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            onClick = onReset,
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CardLight
            )
        ) {

            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "PROCESS ANOTHER VIDEO",
                fontWeight = FontWeight.Bold
            )
        }
    }
}
