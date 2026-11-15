package com.il90.appmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.il90.appmanager.util.parseColor

/** An app's launcher icon in the launcher's rounded shape, with a lettered tile as fallback. */
@Composable
fun AppIcon(
    url: String?,
    name: String,
    accent: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 27)
    val tint = parseColor(accent) ?: MaterialTheme.colorScheme.primary
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.12f), lerp(tint, Color.Black, 0.35f))))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                text = name.trim().take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
