package com.il90.appmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.install.FailReason

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
