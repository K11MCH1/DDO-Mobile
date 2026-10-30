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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(DdoColors.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, DdoColors.Line, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), color = DdoColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Serif)
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

/** The launcher's pages, as a row of tabs in the panels' style: equal widths where they fit, else scrolling sideways. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(DdoColors.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, DdoColors.Line, RoundedCornerShape(10.dp))
            .padding(4.dp),
    ) {
        val fits = maxWidth >= 112.dp * tabs.size
        val scroll = rememberScrollState()
        Row(
            if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { i, title ->
                val on = i == selected
                Box(
                    (if (fits) Modifier.weight(1f) else Modifier.widthIn(min = 96.dp))
                        .background(if (on) DdoColors.PanelHigh else DdoColors.Panel, RoundedCornerShape(8.dp))
                        .border(1.dp, if (on) DdoColors.GoldDim else DdoColors.Panel, RoundedCornerShape(8.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.uppercase(), color = if (on) DdoColors.Gold else DdoColors.TextDim, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false)
                }
            }
        }
        if (!fits) {
            val scope = rememberCoroutineScope()
            Box(Modifier.matchParentSize()) {
                if (scroll.value > 0) ScrollEdge(Alignment.CenterStart, "‹") { scope.launch { scroll.animateScrollTo(0) } }
                if (scroll.value < scroll.maxValue) ScrollEdge(Alignment.CenterEnd, "›") { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
            }
        }
    }
}

/** A fade over the tab bar's edge with a chevron: more tabs lie that way, and a tap scrolls to them. */
@Composable
private fun BoxScope.ScrollEdge(side: Alignment, chevron: String, onClick: () -> Unit) {
    val start = side == Alignment.CenterStart
    Box(
        Modifier.align(side).fillMaxHeight().width(32.dp)
            .background(Brush.horizontalGradient(if (start) listOf(DdoColors.Panel, DdoColors.Panel.copy(alpha = 0f))
                else listOf(DdoColors.Panel.copy(alpha = 0f), DdoColors.Panel)))
            .clickable(onClick = onClick),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Text(chevron, color = DdoColors.Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}
