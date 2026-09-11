package com.gptvideo2anime

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val Background = Color(0xFF07080D)
private val Card = Color(0xFF11131C)
private val CardLight = Color(0xFF181B27)
private val Accent = Color(0xFF9D62FF)
private val AccentBlue = Color(0xFF5F86FF)
private val White = Color(0xFFF8F7FF)
private val Muted = Color(0xFF9698A9)
private val Green = Color(0xFF65D88A)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            Video2AnimeApp(
                uiState = uiState,
                onVideoSelected = viewModel::onVideoSelected,
                onStrengthChanged = viewModel::onStrengthChanged,
                onProcessClick = viewModel::processVideo,
                onResetClick = viewModel::resetState
            )
        }
    }
}

@Composable
private fun Video2AnimeApp(
    uiState: UiState,
    onVideoSelected: (Uri?) -> Unit,
    onStrengthChanged: (Int) -> Unit,
    onProcessClick: () -> Unit,
    onResetClick: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        onVideoSelected(uri)
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

            if (uiState.resultVideo != null) {
                ResultScreen(
                    uri = uiState.resultVideo,
                    onReset = onResetClick
                )
            } else {
                VideoPickerCard(
                    selected = uiState.selectedVideo != null,
                    onClick = {
                        if (!uiState.isProcessing) {
                            picker.launch("video/*")
                        }
                    }
                )

                StrengthCard(
                    strength = uiState.strength,
                    enabled = !uiState.isProcessing,
                    onStrengthChanged = onStrengthChanged
                )

                AnimatedVisibility(visible = uiState.isProcessing) {
                    ProgressCard(
                        progress = uiState.progress,
                        status = uiState.status
                    )
                }

                if (!uiState.isProcessing) {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (!uiState.isProcessing && uiState.error != null) {
                    ErrorCard(message = uiState.error)
                }

                if (!uiState.isProcessing) {
                    Spacer(modifier = Modifier.weight(0.2f))
                }

                ProcessButton(
                    enabled = uiState.selectedVideo != null && uiState.isModelReady && !uiState.isProcessing,
                    processing = uiState.isProcessing,
                    onClick = onProcessClick
                )

                if (!uiState.isProcessing) {
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
                .background(Brush.linearGradient(listOf(Accent, AccentBlue))),
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
            text = if (selected) "Tap to choose another video" else "MP4, MOV, MKV and supported video formats",
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
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
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
private fun ErrorCard(message: String) {
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
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
                        player = exoPlayer
                        useController = true
                    }
                },
                update = { view ->
                    view.player = exoPlayer
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
