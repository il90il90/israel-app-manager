package com.il90.appmanager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.util.isolate
import com.il90.appmanager.util.ltr

/**
 * The ways to pass an app on to someone: a link that keeps pointing at its newest version,
 * or the APK file itself.
 *
 * @param link what "send a link" sends; null hides the link rows
 * @param apkNote version and size of the file "send the APK" sends; null hides that row
 * @param progress null while idle; while the file is being fetched, 0..1 or a negative
 * number when the amount is not known yet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    name: String,
    link: String?,
    apkNote: String?,
    progress: Float?,
    onSendLink: () -> Unit,
    onCopyLink: () -> Unit,
    onSendApk: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.share_title, isolate(name)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            )
            if (link != null) {
                val bare = ltr(link.removePrefix("https://").removePrefix("http://").trimEnd('/'))
                ShareRow(
                    icon = Icons.Rounded.Link,
                    title = stringResource(R.string.share_link_title),
                    note = if ("/releases/latest" in link) stringResource(R.string.share_link_latest) else bare,
                    onClick = onSendLink,
                )
                ShareRow(Icons.Rounded.ContentCopy, stringResource(R.string.share_copy_link), bare, onClick = onCopyLink)
            }
            if (apkNote != null) {
                ShareRow(
                    icon = Icons.Rounded.Android,
                    title = stringResource(R.string.share_apk_title),
                    note = if (progress != null) stringResource(R.string.share_apk_preparing) else apkNote,
                    enabled = progress == null,
                    onClick = onSendApk,
                )
                if (progress != null) {
                    val bar = Modifier.fillMaxWidth().padding(start = 82.dp, end = 24.dp, top = 2.dp)
                    if (progress < 0f) LinearProgressIndicator(modifier = bar, strokeCap = StrokeCap.Round)
                    else LinearProgressIndicator(progress = { progress }, modifier = bar, strokeCap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ShareRow(icon: ImageVector, title: String, note: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
            Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
