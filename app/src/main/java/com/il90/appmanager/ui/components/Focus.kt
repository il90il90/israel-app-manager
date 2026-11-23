package com.il90.appmanager.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.il90.appmanager.data.Device
import com.il90.appmanager.data.FormFactor
import kotlinx.coroutines.delay

/** The device the app runs on, for the few places the screens differ (a TV, a remote). */
val LocalDevice = staticCompositionLocalOf { Device(FormFactor.PHONE, emptyList(), 0) }

/**
 * A clear ring just outside the element while it has the focus of a TV remote, a keyboard or
 * a D-pad. Touch never gives elements focus, so on a phone it never shows.
 *
 * Put it before the element's own click handling (as the first modifier of a button, a card or
 * a clickable row): it lights up only when that element itself is focused, not when a button
 * inside it is.
 */
fun Modifier.focusRing(shape: Shape, width: Dp = 3.dp, gap: Dp = 2.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface
    onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused) {
            val stroke = width.toPx()
            val out = gap.toPx() + stroke / 2
            val outline = shape.createOutline(Size(size.width + 2 * out, size.height + 2 * out), layoutDirection, this)
            translate(-out, -out) { drawOutline(outline, color, style = Stroke(stroke)) }
        }
    }
}

/**
 * Lets the remote stop on a block of text (a section of an app's page) on devices without a
 * touchscreen, so the screen scrolls to it: a TV can only scroll by moving the focus.
 */
fun Modifier.remoteStop(shape: Shape): Modifier = composed {
    if (LocalDevice.current.touch) this else focusRing(shape).focusable()
}

/**
 * On a device driven by a remote, puts the focus on this element (or the first focusable one
 * inside it) when the screen opens, so the first key press already does something. Requested
 * again when [key] changes.
 */
fun Modifier.initialFocus(key: Any? = Unit): Modifier = composed {
    val remote = !LocalDevice.current.touch
    val requester = remember { FocusRequester() }
    LaunchedEffect(remote, key) {
        if (!remote) return@LaunchedEffect
        // Items of a lazy list are composed after this effect starts: wait for them.
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    focusRequester(requester)
}
