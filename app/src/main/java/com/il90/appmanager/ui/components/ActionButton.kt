package com.il90.appmanager.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.install.TaskState
import com.il90.appmanager.ui.AppRow
import com.il90.appmanager.ui.PrimaryAction
import com.il90.appmanager.ui.primaryAction

/** The compact button at the end of a row: Install, Update, Open, or a progress ring. */
@Composable
fun ActionButton(row: AppRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val action = row.primaryAction()
    val state = row.task?.state
    val kind = when {
        state is TaskState.Queued || state is TaskState.Downloading -> "progress"
        state is TaskState.Verifying || state is TaskState.Installing || state is TaskState.AwaitingUser -> "busy"
        else -> action.name
    }
    AnimatedContent(
        targetState = kind,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.85f)) },
        contentAlignment = Alignment.Center,
        label = "action",
        modifier = modifier,
    ) { target ->
        val padding = PaddingValues(horizontal = 14.dp)
        val size = Modifier.focusRing(RoundedCornerShape(50)).height(40.dp).defaultMinSize(minWidth = 76.dp)
        when (target) {
            "progress" -> {
                val fraction = (state as? TaskState.Downloading)?.fraction
                val animated by animateFloatAsState(fraction ?: 0f, label = "progress")
                val cancel = stringResource(R.string.action_cancel)
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (fraction == null || fraction <= 0f) {
                        CircularProgressIndicator(Modifier.size(38.dp), strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
                    } else {
                        CircularProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.size(38.dp),
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                    IconButton(onClick, Modifier.focusRing(CircleShape).size(44.dp).semantics { contentDescription = cancel }) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            "busy" -> Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
            }
            PrimaryAction.UPDATE.name -> Button(onClick, size, contentPadding = padding) {
                Text(stringResource(R.string.action_update), maxLines = 1)
            }
            PrimaryAction.INSTALL.name -> FilledTonalButton(
                onClick, size, contentPadding = padding,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Text(stringResource(R.string.action_install), maxLines = 1) }
            PrimaryAction.OPEN.name -> OutlinedButton(onClick, size, contentPadding = padding) {
                Text(stringResource(R.string.action_open), maxLines = 1)
            }
            PrimaryAction.RETRY.name -> FilledTonalButton(
                onClick, size, contentPadding = padding,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text(stringResource(R.string.action_retry), maxLines = 1) }
            else -> Box(Modifier.size(1.dp))
        }
    }
}
