package com.il90.appmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.install.FailReason
import com.il90.appmanager.ui.theme.LocalDarkTheme
import com.il90.appmanager.util.ltr

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

@Composable
fun categoryLabel(key: String): String = when (key.lowercase()) {
    "ai" -> stringResource(R.string.cat_ai)
    "video" -> stringResource(R.string.cat_video)
    "personalization" -> stringResource(R.string.cat_personalization)
    "devtools" -> stringResource(R.string.cat_devtools)
    "communication" -> stringResource(R.string.cat_communication)
    "photos" -> stringResource(R.string.cat_photos)
    "sports" -> stringResource(R.string.cat_sports)
    "tools" -> stringResource(R.string.cat_tools)
    "kids" -> stringResource(R.string.cat_kids)
    else -> key
}

@Composable
fun formFactorLabel(formFactor: String): String = when (formFactor) {
    FormFactor.TV -> stringResource(R.string.form_tv)
    FormFactor.WATCH -> stringResource(R.string.form_watch)
    else -> stringResource(R.string.form_phone)
}

@Composable
fun failureMessage(reason: FailReason): String = stringResource(
    when (reason) {
        FailReason.NETWORK -> R.string.fail_network
        FailReason.STORAGE -> R.string.fail_storage
        FailReason.CORRUPT -> R.string.fail_corrupt
        FailReason.CONFLICT -> R.string.fail_conflict
        FailReason.INCOMPATIBLE -> R.string.fail_incompatible
        FailReason.BLOCKED -> R.string.fail_blocked
        FailReason.INVALID -> R.string.fail_invalid
        FailReason.DOWNGRADE -> R.string.fail_downgrade
        FailReason.GENERIC -> R.string.fail_generic
    }
)

/** The short label shown instead of a button when an app cannot be installed here. */
@Composable
fun unavailableLabel(compatibility: Compatibility): String = when (compatibility) {
    is Compatibility.OtherDevice ->
        if (FormFactor.TV in compatibility.formFactors) stringResource(R.string.state_tv_only)
        else stringResource(R.string.state_watch_only)
    Compatibility.NoRelease -> stringResource(R.string.state_soon)
    else -> stringResource(R.string.state_incompatible)
}

/** The gold that marks everything about premium apps. */
@Composable
fun premiumColor(): Color = if (LocalDarkTheme.current) Color(0xFFF2C14E) else Color(0xFF8A5A00)

/** A small gold tag on an app that needs a license. */
@Composable
fun LicenseTag(modifier: Modifier = Modifier) {
    val gold = premiumColor()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(gold.copy(alpha = 0.14f))
            .padding(start = 6.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WorkspacePremium, null, Modifier.size(13.dp), tint = gold)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.license_required), style = MaterialTheme.typography.labelMedium, color = gold, maxLines = 1)
    }
}

/** "@name" for a t.me link. */
fun telegramHandle(url: String): String = "@" + url.trimEnd('/').substringAfterLast('/')

/** The way to reach the developer on Telegram: a wide blue button with the handle on it. */
@Composable
fun TelegramButton(url: String, title: String, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF1E88E5), Color(0xFF1565C0))))
            .clickable(role = Role.Button) { onOpenUrl(url) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.20f)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(20.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ltr(telegramHandle(url)), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.88f), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.9f))
    }
}
