package com.ddomobile.launcher.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddomobile.launcher.components.Catalog
import com.ddomobile.launcher.components.CatalogItem
import com.ddomobile.launcher.components.ComponentCatalog
import com.ddomobile.launcher.components.ComponentKind
import com.ddomobile.launcher.components.ComponentStore
import com.ddomobile.launcher.components.ComponentStore.Choice
import com.ddomobile.launcher.runtime.DriverStore
import com.ddomobile.launcher.runtime.ProtonStore
import com.ddomobile.launcher.runtime.TurnipCatalog
import com.ddomobile.launcher.session.GameSession
import java.io.File

/** State and actions of the Components tab: DroidDeck's Components page for DDO's one Proton at a time. */
class ComponentsModel(private val activity: ComponentActivity) {
    enum class Page(val title: String, val kind: ComponentKind?) {
        GPU("GPU drivers", null), DXVK("DXVK", ComponentKind.DXVK), VKD3D("VKD3D-Proton", ComponentKind.VKD3D), FEX("FEX", ComponentKind.FEX)
    }

    class Confirm(val title: String, val body: String, val action: String, val danger: Boolean = false, val run: () -> Unit)

    val jobs = Jobs(activity)
    val store = ComponentStore(activity)
    val drivers = DriverStore(activity)
    private val catalog = ComponentCatalog(activity)
    private val turnips = TurnipCatalog(activity)
    private val protons = ProtonStore(activity)

    var page by mutableStateOf(Page.GPU)
    var proton by mutableStateOf<String?>(null)
    var protonList by mutableStateOf(emptyList<String>())
        private set
    var items by mutableStateOf(emptyList<CatalogItem>())
        private set
    var builds by mutableStateOf(emptyList<TurnipCatalog.Build>())
        private set
    var checkedAt by mutableLongStateOf(0L)
        private set
    var confirm by mutableStateOf<Confirm?>(null)
    /** Bumped after every change on disk, so the page reads the stores again. */
    var version by mutableIntStateOf(0)
        private set

    var pickWcp: () -> Unit = {}
    var pickDriver: () -> Unit = {}

    /** Reads everything again, and puts in what waited for the game to close. */
    fun load() {
        reload()
        if (!GameSession.running && store.hasQueued()) {
            jobs.run("Applying swaps that waited for the game", done = ::reload) { _ ->
                store.applyQueued().takeIf { it.isNotEmpty() }?.let { "Applied after the game closed: " + it.joinToString("; ") }
            }
        }
    }

    private fun reload() {
        protonList = protons.installed()
        if (proton == null || proton !in protonList) proton = protons.selected()
        val c = catalog.cached()
        items = c.items
        builds = turnips.cached().builds
        checkedAt = c.fetchedAt
        version++
    }

    fun refresh() = jobs.run("Checking the release pages", done = ::load) { stage ->
        stage("Checking the component pages", -1f)
        val components = runCatching { catalog.refresh() }
        stage("Checking the driver pages", -1f)
        val drivers = runCatching { turnips.refresh() }
        if (components.isFailure && drivers.isFailure) throw components.exceptionOrNull()!!
        null
    }

    fun download(item: CatalogItem) = jobs.run("Downloading ${item.file}", item.file, ::load) { stage ->
        val stored = store.download(item) { stage("Downloading ${item.file}", it) }
        "${stored.version} stored. Choose it under Installed to put it in."
    }

    fun importWcp(file: File, name: String) = jobs.run("Importing $name", done = ::load) { _ ->
        try { "${store.import(file, name).version} stored." } finally { file.delete() }
    }

    fun choose(kind: ComponentKind, choice: Choice, label: String) {
        val target = proton ?: return
        val applies = if (GameSession.running) "It waits until the game closes." else "Applies the next time the game starts."
        val body = when (choice) {
            is Choice.Package -> "Put $label into $target? Its original files stay saved. $applies"
            else -> "Put $label back into $target? $applies"
        }
        confirm = Confirm("Swap ${kind.label}?", body, "Swap") {
            jobs.run("Swapping ${kind.label}", done = ::load) { _ ->
                if (store.select(target, kind, choice)) "${kind.label}: $label is in $target." else "${kind.label}: $label waits until the game closes."
            }
        }
    }

    fun cancelQueued(kind: ComponentKind) {
        proton?.let { store.cancelQueued(it, kind) }
        version++
    }

    fun deletePackage(s: ComponentStore.Stored) {
        confirm = Confirm("Delete ${s.version}?", "Removes the stored package ${s.file}.", "Delete", danger = true) {
            jobs.run("Deleting ${s.file}", done = ::load) { _ -> store.remove(s.file); null }
        }
    }

    fun downloadDriver(build: TurnipCatalog.Build) = jobs.run("Downloading ${build.name}", build.name, ::load) { stage ->
        val d = drivers.download(build) { stage("Downloading ${build.name}", it) }
        "${d.name} installed and in use."
    }

    fun importDriver(file: File) = jobs.run("Importing the driver", done = ::load) { _ ->
        try { "${drivers.import(file).name} installed and in use." } finally { file.delete() }
    }

    fun deleteDriver(d: DriverStore.Driver) {
        confirm = Confirm("Delete ${d.name}?", "Removes both halves of this driver from DDO Mobile.", "Delete", danger = true) {
            drivers.remove(d.id); load()
        }
    }

    fun selectDisplay(id: String?) { drivers.selectAndroid(id); version++ }
    fun selectRuntime(id: String?) { drivers.selectLinux(id); version++ }

    /** The display driver loads once per process, so a new choice needs DDO Mobile to start again. */
    val restartNeeded: Boolean get() = GameSession.displayDriverLoaded?.let { it != drivers.selectedAndroid().orEmpty() } == true

    fun restart() {
        if (GameSession.running) return jobs.toast("Close the game first")
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

private fun ago(seconds: Long): String {
    if (seconds <= 0) return "Not checked yet"
    val minutes = (System.currentTimeMillis() / 1000 - seconds) / 60
    return when {
        minutes < 1 -> "Checked just now"
        minutes < 60 -> "Checked $minutes min ago"
        minutes < 48 * 60 -> "Checked ${minutes / 60} h ago"
        else -> "Checked ${minutes / 1440} days ago"
    }
}

@Composable
fun ComponentsTab(m: ComponentsModel) {
    @Suppress("UNUSED_VARIABLE") val v = m.version
    Panel("Components") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.jobs.busy?.let { "$it…" } ?: ago(m.checkedAt), color = DdoColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(m::refresh, enabled = m.jobs.busy == null) { Text("Refresh") }
        }
        if (m.jobs.busy != null && m.jobs.target == null) Progress(m.jobs.progress)
        ProtonPicker(m)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComponentsModel.Page.entries.forEach { p -> PageCard(m, p) }
        }
    }
    when (val kind = m.page.kind) {
        null -> GpuDrivers(m)
        else -> KindPanel(m, kind)
    }
    m.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { m.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.body) },
            confirmButton = { TextButton({ m.confirm = null; c.run() }) { Text(c.action, color = if (c.danger) DdoColors.Ember else DdoColors.Gold) } },
            dismissButton = { TextButton({ m.confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProtonPicker(m: ComponentsModel) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("THIS PROTON", color = DdoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        Box {
            TextButton({ open = true }, enabled = m.protonList.isNotEmpty()) { Text((m.proton ?: "No Proton installed") + " ▾") }
            DropdownMenu(open, { open = false }) {
                m.protonList.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { m.proton = p; open = false; m.load() }) }
            }
        }
    }
}

@Composable
private fun PageCard(m: ComponentsModel, page: ComponentsModel.Page) {
    val on = m.page == page
    val (value, swapped) = when (val kind = page.kind) {
        null -> {
            val display = m.drivers.selectedAndroid()?.let { id -> m.drivers.installed().firstOrNull { it.id == id }?.name ?: id } ?: "Phone's driver"
            val runtime = m.drivers.selectedLinux()?.let { id -> m.drivers.installed().firstOrNull { it.id == id }?.name ?: id } ?: "Built-in Mesa"
            "$display · $runtime" to false
        }
        else -> m.proton?.let { p ->
            val a = m.store.active(p, kind)
            a.label to (a.choice != Choice.Default)
        } ?: ("—" to false)
    }
    Column(
        Modifier.widthIn(min = 120.dp, max = 220.dp)
            .background(if (on) DdoColors.PanelHigh else DdoColors.Background, RoundedCornerShape(8.dp))
            .border(1.dp, if (on) DdoColors.GoldDim else DdoColors.Line, RoundedCornerShape(8.dp))
            .clickable { m.page = page }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(page.title, color = if (on) DdoColors.Gold else DdoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (swapped) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).background(DdoColors.Gold, RoundedCornerShape(4.dp)))
            }
        }
        Text(value, color = DdoColors.TextDim, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun Progress(p: Float) {
    if (p in 0f..1f) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), color = DdoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, detail: String, badges: List<Pair<String, Color>>, enabled: Boolean,
                      onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = DdoColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                badges.forEach { (t, c) -> Badge(t, c) }
            }
            if (detail.isNotEmpty()) Text(detail, color = DdoColors.TextDim, fontSize = 12.sp)
        }
        if (onDelete != null) TextButton(onDelete, enabled = enabled) { Text("Delete", color = DdoColors.Ember, fontSize = 12.sp) }
    }
}

@Composable
private fun KindPanel(m: ComponentsModel, kind: ComponentKind) {
    val proton = m.proton
    Panel(kind.label) {
        if (proton == null) {
            Text("Install a Proton on the Proton tab first.", color = DdoColors.TextDim, fontSize = 13.sp)
            return@Panel
        }
        val active = m.store.active(proton, kind)
        val queued = m.store.queued(proton, kind)
        val stored = m.store.stored(kind)
        val idle = m.jobs.busy == null
        // In use
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (queued == null) DdoColors.Good else DdoColors.Gold, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${kind.label} in use: ${active.label}", color = DdoColors.Text, fontSize = 13.sp)
                if (queued != null) Text("Next: ${choiceLabel(m, proton, kind, queued, stored)} · after the game closes", color = DdoColors.Gold, fontSize = 12.sp)
                Text(if (GameSession.running) "Waits until the game closes" else "Applies the next time the game starts", color = DdoColors.TextDim, fontSize = 11.sp)
            }
            if (queued != null) TextButton({ m.cancelQueued(kind) }) { Text("Cancel") }
        }
        val current = queued ?: active.choice
        Group("Installed · ${stored.size + if (kind == ComponentKind.FEX && m.store.hasRuntimeFex()) 2 else 1}") {
            if (kind == ComponentKind.FEX && m.store.hasRuntimeFex()) {
                ChoiceRow(current == Choice.Default, "DDO Mobile's build", "${m.store.runtimeFexVersion()} · FEX with DDO Mobile's fixes for Wine",
                    listOf("Default" to DdoColors.Gold), idle, { m.choose(kind, Choice.Default, "DDO Mobile's build") }, null)
                ChoiceRow(current == Choice.Original, "Original · ${m.store.originalVersion(proton, kind)}", "The FEX $proton shipped with",
                    listOf("Original" to DdoColors.GoldDim), idle, { m.choose(kind, Choice.Original, "the original FEX") }, null)
            } else {
                ChoiceRow(current == Choice.Default || current == Choice.Original, "Original · ${m.store.originalVersion(proton, kind)}",
                    "As $proton shipped it", listOf("Original" to DdoColors.Gold), idle,
                    { m.choose(kind, Choice.Default, "the original ${kind.label}") }, null)
            }
            stored.forEach { s ->
                val inUse = current == Choice.Package(s.file)
                ChoiceRow(inUse, s.version, listOf(s.description, mb(s.size)).filter { it.isNotEmpty() }.joinToString(" · "),
                    if (inUse) listOf("In use" to DdoColors.Good) else listOf("Stored" to DdoColors.TextDim), idle,
                    { m.choose(kind, Choice.Package(s.file), s.version) }, { m.deletePackage(s) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(m.pickWcp, enabled = idle) { Text("Import .wcp") }
            if (active.choice != Choice.Default) {
                val back = if (kind == ComponentKind.FEX && m.store.hasRuntimeFex()) "DDO Mobile's build" else "the original ${kind.label}"
                OutlinedButton({ m.choose(kind, Choice.Default, back) }, enabled = idle) {
                    Text(if (kind == ComponentKind.FEX && m.store.hasRuntimeFex()) "Back to DDO Mobile's build" else "Restore the original")
                }
            }
        }
        if (kind == ComponentKind.FEX) {
            Group("FEX preset") { FexPresetPicker() }
        }
        val rows = Catalog.rows(m.items, kind, stored.map { it.file }.toSet())
        Group("On the nightlies · ${rows.size}") {
            if (rows.isEmpty()) Text(if (m.items.isEmpty()) "Tap Refresh to read the release pages." else "Nothing new for ${kind.label}.",
                color = DdoColors.TextDim, fontSize = 12.sp)
            rows.forEach { row -> NightlyRow(m, row) }
        }
    }
}

private fun choiceLabel(m: ComponentsModel, proton: String, kind: ComponentKind, c: Choice, stored: List<ComponentStore.Stored>) = when (c) {
    Choice.Default -> if (kind == ComponentKind.FEX && m.store.hasRuntimeFex()) "DDO Mobile's build" else "Original · ${m.store.originalVersion(proton, kind)}"
    Choice.Original -> "Original · ${m.store.originalVersion(proton, kind)}"
    is Choice.Package -> stored.firstOrNull { it.file == c.file }?.version ?: c.file
}

@Composable
private fun NightlyRow(m: ComponentsModel, row: com.ddomobile.launcher.components.CatalogRow) {
    var nightly by remember(row) { mutableStateOf(false) }
    val item = if (nightly && row.nightly != null) row.nightly else row.primary
    val downloading = m.jobs.target == item.file
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.file.removeSuffix(".wcp"), color = DdoColors.Text, fontSize = 13.sp)
                Text("${item.release} · ${mb(item.size)}", color = DdoColors.TextDim, fontSize = 11.sp)
            }
            if (row.stable != null && row.nightly != null) {
                Row(Modifier.border(1.dp, DdoColors.Line, RoundedCornerShape(6.dp))) {
                    listOf(false to "Stable", true to "Nightly").forEach { (n, label) ->
                        Text(label, color = if (nightly == n) DdoColors.Gold else DdoColors.TextDim, fontSize = 11.sp,
                            modifier = Modifier.background(if (nightly == n) DdoColors.PanelHigh else DdoColors.Panel, RoundedCornerShape(6.dp))
                                .clickable { nightly = n }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            TextButton({ m.download(item) }, enabled = m.jobs.busy == null) { Text("Get") }
        }
        if (downloading) Progress(m.jobs.progress)
    }
}

@Composable
private fun GpuDrivers(m: ComponentsModel) {
    val installed = m.drivers.installed()
    val display = m.drivers.selectedAndroid()
    val runtime = m.drivers.selectedLinux()
    val idle = m.jobs.busy == null
    Panel("GPU drivers") {
        Text("The display driver draws DDO Mobile's screen; the runtime driver runs the game. A DD-Turnip bundle brings both.",
            color = DdoColors.TextDim, fontSize = 12.sp)
        if (m.restartNeeded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("The new display driver loads when DDO Mobile starts again.", color = DdoColors.Gold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(m::restart, enabled = !GameSession.running) { Text("Restart now") }
            }
        }
        Group("Display driver") {
            ChoiceRow(display == null, "Phone's own driver", "Qualcomm's driver; it cannot show gamescope's frames on every phone",
                emptyList(), idle, { m.selectDisplay(null) }, null)
            installed.filter { it.android }.forEach { d ->
                ChoiceRow(display == d.id, d.name, if (d.linux) "Also a runtime driver" else "", if (display == d.id) listOf("In use" to DdoColors.Good) else emptyList(),
                    idle, { m.selectDisplay(d.id) }, { m.deleteDriver(d) })
            }
        }
        Group("Runtime driver") {
            ChoiceRow(runtime == null, "Built-in Mesa", "The runtime's own Turnip", emptyList(), idle, { m.selectRuntime(null) }, null)
            installed.filter { it.linux }.forEach { d ->
                ChoiceRow(runtime == d.id, d.name, if (d.android) "Also a display driver" else "",
                    if (runtime == d.id) listOf("In use" to DdoColors.Good) else emptyList(), idle, { m.selectRuntime(d.id) }, { m.deleteDriver(d) })
            }
        }
        val have = installed.map { it.id }.toSet()
        val available = m.builds.filter { it.id !in have }
        Group("Available · ${available.size}") {
            if (available.isEmpty()) Text(if (m.builds.isEmpty()) "Tap Refresh to read the driver pages." else "Every listed build is installed.",
                color = DdoColors.TextDim, fontSize = 12.sp)
            available.forEach { b ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name.removeSuffix(".zip"), color = DdoColors.Text, fontSize = 13.sp)
                            val half = when (b.half) {
                                TurnipCatalog.Half.ANDROID -> "Display"
                                TurnipCatalog.Half.LINUX -> "Runtime"
                                TurnipCatalog.Half.BOTH -> "Display + runtime"
                            }
                            Text("$half · ${b.repo.substringBefore('/')} · ${mb(b.size)}", color = DdoColors.TextDim, fontSize = 11.sp)
                        }
                        TextButton({ m.downloadDriver(b) }, enabled = idle) { Text("Get") }
                    }
                    if (m.jobs.target == b.name) Progress(m.jobs.progress)
                }
            }
        }
        OutlinedButton(m.pickDriver, enabled = idle) { Text("Import a driver zip") }
    }
}
