package com.ddomobile.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A section of a tab; null leaves out the title, for a section the tab's own name already heads. */
@Composable
fun Panel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(DdoColors.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, DdoColors.Line, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), color = DdoColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Serif)
        }
        content()
    }
}

@Composable
fun Toggle(label: String, value: Boolean, detail: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) DdoColors.Text else DdoColors.TextDim, fontSize = 14.sp)
            detail?.let { Text(it, color = DdoColors.TextDim, fontSize = 12.sp) }
        }
        Switch(value, onChange, enabled = enabled)
    }
}

/** Choice chips in the launcher's gold rather than Material's default. */
@Composable
fun ddoChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = DdoColors.PanelHigh,
    selectedLabelColor = DdoColors.Gold,
    labelColor = DdoColors.TextDim,
)

/**
 * A row that scrolls sideways, with a ‹ or › at an edge that has more beyond it; a tap on one scrolls there.
 * [fade] is the colour behind the row, which the edges fade into.
 */
@Composable
fun ScrollingRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    fade: Color = DdoColors.Panel,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(modifier) {
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = verticalAlignment, content = content)
        Box(Modifier.matchParentSize()) {
            if (scroll.value > 0) ScrollEdge(Alignment.CenterStart, "‹", fade) { scope.launch { scroll.animateScrollTo(0) } }
            if (scroll.value < scroll.maxValue) {
                ScrollEdge(Alignment.CenterEnd, "›", fade) { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
            }
        }
    }
}

@Composable
private fun BoxScope.ScrollEdge(side: Alignment, chevron: String, fade: Color, onClick: () -> Unit) {
    val start = side == Alignment.CenterStart
    Box(
        Modifier.align(side).fillMaxHeight().width(32.dp)
            .background(Brush.horizontalGradient(if (start) listOf(fade, fade.copy(alpha = 0f)) else listOf(fade.copy(alpha = 0f), fade)))
            .clickable(onClick = onClick),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Text(chevron, color = DdoColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** The launcher's pages, as a row of tabs in the panels' style: equal widths where they fit, else scrolling sideways. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(DdoColors.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, DdoColors.Line, RoundedCornerShape(10.dp))
            .padding(4.dp),
    ) {
        @Composable
        fun Tab(i: Int, modifier: Modifier) {
            val on = i == selected
            Box(
                modifier
                    .background(if (on) DdoColors.PanelHigh else DdoColors.Panel, RoundedCornerShape(8.dp))
                    .border(1.dp, if (on) DdoColors.GoldDim else DdoColors.Panel, RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tabs[i].uppercase(), color = if (on) DdoColors.Gold else DdoColors.TextDim, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false)
            }
        }
        if (maxWidth >= 112.dp * tabs.size) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.indices.forEach { Tab(it, Modifier.weight(1f)) }
            }
        } else {
            ScrollingRow(spacing = 4.dp) { tabs.indices.forEach { Tab(it, Modifier.widthIn(min = 96.dp)) } }
        }
    }
}
