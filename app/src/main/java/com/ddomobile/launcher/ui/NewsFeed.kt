package com.ddomobile.launcher.ui

import android.graphics.Typeface
import android.text.Html
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ddomobile.launcher.ddo.DdoNews
import kotlinx.coroutines.launch

/**
 * The official launcher's news, under the login: each item's title, date and text, scrolling inside the room
 * [modifier] gives it. Titles and links open in the browser. A chevron at the bottom shows there is more below, and
 * a page down a small button goes back to the top.
 */
@Composable
fun NewsFeed(items: List<DdoNews.Item>, error: String?, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    fun open(url: String) {
        if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uri.openUri(url) }
    }
    if (items.isEmpty()) {
        Text(error ?: "Loading the news…", color = DdoColors.TextDim, fontSize = 13.sp)
        return
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxWidth().onSizeChanged { page = it.height }) {
        if (scroll.value < scroll.maxValue) {
            // Only the chevron takes taps: the fade lets a drag through to the news under it.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).zIndex(1f)
                    .background(Brush.verticalGradient(listOf(DdoColors.Panel.copy(alpha = 0f), DdoColors.Panel))),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text("›", color = DdoColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { scope.launch { scroll.animateScrollTo(scroll.value + page * 3 / 4) } }
                        .padding(horizontal = 16.dp).rotate(90f))
            }
        }
        AnimatedVisibility(page > 0 && scroll.value > page, Modifier.align(Alignment.BottomEnd).zIndex(2f), enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.size(32.dp).background(DdoColors.PanelHigh, CircleShape).border(1.dp, DdoColors.GoldDim, CircleShape)
                    .clickable { scope.launch { scroll.animateScrollTo(0) } },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = DdoColors.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, color = DdoColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = item.link.isNotEmpty()) { open(item.link) })
                    Text(item.date, color = DdoColors.Text, fontSize = 11.sp, fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    val text = remember(item.html) { html(item.html, DdoColors.Gold) }
                    ClickableText(text, style = TextStyle(color = DdoColors.Text, fontSize = 13.sp, lineHeight = 18.sp)) { offset ->
                        text.getStringAnnotations(LINK, offset, offset).firstOrNull()?.let { open(it.item) }
                    }
                }
            }
        }
    }
}

private const val LINK = "link"

/** U+FFFC, what Html puts where an image was. */
private const val OBJECT = '\uFFFC'

/** The feed's little HTML (paragraphs, bold, italics, line breaks, links) as styled text; images are left out. */
private fun html(source: String, link: Color): AnnotatedString {
    // Legacy mode keeps a blank line between paragraphs, as the official launcher's page shows them.
    val spanned = Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY)
    // An image becomes an object character; a space keeps the span offsets where they are.
    val text = spanned.toString().replace(OBJECT, ' ').trimEnd()
    return buildAnnotatedString {
        append(text)
        for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
            val start = spanned.getSpanStart(span)
            val end = minOf(spanned.getSpanEnd(span), text.length)
            if (start >= end) continue
            when (span) {
                is StyleSpan -> {
                    if (span.style and Typeface.BOLD != 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    if (span.style and Typeface.ITALIC != 0) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                }
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is URLSpan -> {
                    addStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline), start, end)
                    addStringAnnotation(LINK, span.url, start, end)
                }
            }
        }
    }
}
