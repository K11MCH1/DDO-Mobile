package com.ddomobile.launcher.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddomobile.launcher.components.ComponentKind
import com.ddomobile.launcher.components.ComponentStore
import com.ddomobile.launcher.ddo.DdoLaunch
import com.ddomobile.launcher.ddo.FexPreset
import com.ddomobile.launcher.ddo.GameEnvironment
import com.ddomobile.launcher.ddo.GameEnvironmentStore
import com.ddomobile.launcher.ddo.ProtonOptions
import com.ddomobile.launcher.ddo.WineSync
import com.ddomobile.launcher.runtime.ProtonStore

/** Bumped when a Proton option changes, so every place that shows one reads it again. */
private val optionsVersion = mutableIntStateOf(0)

/** State and actions of the Proton tab. */
class ProtonModel(private val activity: ComponentActivity) {
    val jobs = Jobs(activity)
    private val protons = ProtonStore(activity)
    private val components = ComponentStore(activity)
    private val env = GameEnvironmentStore(activity)

    var installed by mutableStateOf(emptyList<String>())
        private set
    var selected by mutableStateOf<String?>(null)
        private set
    var edits by mutableStateOf(emptyMap<String, String?>())
        private set
    var editing by mutableStateOf(false)
    var confirm by mutableStateOf<ComponentsModel.Confirm?>(null)
    /** The variable being changed or added; a blank name is a custom one still to be named. */
    var change by mutableStateOf<Pair<String, String>?>(null)

    fun load() {
        installed = protons.installed()
        selected = protons.selected()
        edits = env.edits()
    }

    fun select(name: String) { protons.select(name); load() }

    fun swapped(proton: String) = ComponentKind.entries.count { components.active(proton, it).choice != ComponentStore.Choice.Default }

    fun install(source: ProtonStore.Source) = jobs.run("Installing ${source.label}", source.id, ::load) { stage ->
        "${protons.install(source, stage)} is installed and in use."
    }

    fun remove(name: String) {
        confirm = ComponentsModel.Confirm("Remove $name?", "Deletes this Proton and the component choices made for it.", "Remove", danger = true) {
            protons.remove(name)
            components.forgetProton(name)
            load()
        }
    }

    fun setSync(value: WineSync) { ProtonOptions.setWineSync(activity, value); optionsVersion.intValue++ }
    fun setSsbs(value: Boolean) { ProtonOptions.setForceSsbs(activity, value); optionsVersion.intValue++ }

    fun defaults(): Map<String, String> = DdoLaunch.defaultEnvironment(activity)

    fun save(name: String, value: String?) { env.set(name, value); load() }
    fun forget(name: String) { env.forget(name); load() }
    fun clear() {
        confirm = ComponentsModel.Confirm("Clear environment overrides?", "Every variable goes back to DDO Mobile's default.", "Clear", danger = true) {
            env.clear(); load()
        }
    }
}

@Composable
fun ProtonTab(m: ProtonModel) {
    @Suppress("UNUSED_VARIABLE") val v = optionsVersion.intValue
    val context = LocalContext.current
    val idle = m.jobs.busy == null
    Panel("Proton") {
        Text("INSTALLED", color = DdoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (m.installed.isEmpty()) Text("No Proton yet. Get DroidDeck-Proton below.", color = DdoColors.TextDim, fontSize = 12.sp)
        m.installed.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable(enabled = idle && p != m.selected) { m.select(p) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(p == m.selected, { m.select(p) }, enabled = idle)
                Column(Modifier.weight(1f)) {
                    Text(p, color = DdoColors.Text, fontSize = 14.sp)
                    val n = m.swapped(p)
                    Text(if (n == 0) "All original" else "$n swapped", color = DdoColors.TextDim, fontSize = 12.sp)
                }
                TextButton({ m.remove(p) }, enabled = idle) { Text("Remove", color = DdoColors.Ember, fontSize = 12.sp) }
            }
        }
        Text("AVAILABLE", color = DdoColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        ProtonStore.SOURCES.forEach { s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, color = DdoColors.Text, fontSize = 14.sp)
                        Text(s.repo, color = DdoColors.TextDim, fontSize = 11.sp)
                    }
                    TextButton({ m.install(s) }, enabled = idle) { Text("Get latest") }
                }
                if (m.jobs.target == s.id) {
                    Text(m.jobs.busy.orEmpty(), color = DdoColors.TextDim, fontSize = 11.sp)
                    val p = m.jobs.progress
                    if (p in 0f..1f) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    Panel("For DDO") {
        Text("Wine sync", color = DdoColors.Text, fontSize = 14.sp)
        Text("How the game keeps its threads in step. Applies the next time the game starts.", color = DdoColors.TextDim, fontSize = 12.sp)
        val sync = ProtonOptions.wineSync(context)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WineSync.entries.forEach { s ->
                FilterChip(s == sync, { m.setSync(s) }, label = { Text(s.label, softWrap = false) }, colors = ddoChipColors())
            }
        }
        Text("FEX preset", color = DdoColors.Text, fontSize = 14.sp)
        FexPresetPicker()
        Toggle("Force SSBS", ProtonOptions.forceSsbs(context),
            "Keeps a CPU speed setting on that Wine keeps turning off. Can make the game faster on Snapdragon 8 Elite; " +
                "little change seen on older chips. Applies on the next game launch.") { m.setSsbs(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Game environment", color = DdoColors.Text, fontSize = 14.sp)
                val n = m.edits.size
                Text(if (n == 0) "Environment variables for DDO" else "$n override${if (n == 1) "" else "s"}", color = DdoColors.TextDim, fontSize = 12.sp)
            }
            TextButton({ m.editing = !m.editing }) { Text(if (m.editing) "Done" else "Edit") }
        }
        if (m.editing) EnvironmentEditor(m)
    }
    m.change?.let { (name, value) -> ChangeDialog(m, name, value) }
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

/** DroidDeck's FEX presets; also on the Components tab's FEX page. */
@Composable
fun FexPresetPicker() {
    @Suppress("UNUSED_VARIABLE") val v = optionsVersion.intValue
    val context = LocalContext.current
    val preset = ProtonOptions.fexPreset(context)
    var open by remember { mutableStateOf(false) }
    Column {
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("${preset.label} ▾") }
            DropdownMenu(open, { open = false }) {
                FexPreset.ALL.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.label, color = if (p.id == preset.id) DdoColors.Gold else DdoColors.Text, fontSize = 14.sp)
                                Text(p.detail, color = DdoColors.TextDim, fontSize = 11.sp)
                            }
                        },
                        onClick = { ProtonOptions.setFexPreset(context, p); optionsVersion.intValue++; open = false },
                    )
                }
            }
        }
        Text(preset.detail + if (preset.env.isEmpty()) "" else " " + preset.env.entries.joinToString(" ") { "${it.key.removePrefix("FEX_")}=${it.value}" },
            color = DdoColors.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun EnvironmentEditor(m: ProtonModel) {
    val entries = GameEnvironment.entries(m.defaults(), m.edits)
    Column(
        Modifier.fillMaxWidth().border(1.dp, DdoColors.Line, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { e -> EnvironmentRow(m, e) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var adding by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ adding = true }) { Text("Add variable") }
                DropdownMenu(adding, { adding = false }, Modifier.heightIn(max = 420.dp)) {
                    val present = entries.map { it.name }.toSet()
                    GameEnvironment.OPTIONS.filter { it.name !in present }.forEach { o ->
                        DropdownMenuItem(text = { Text(o.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                            onClick = { adding = false; m.change = o.name to o.starter })
                    }
                    DropdownMenuItem(text = { Text("Custom variable…") }, onClick = { adding = false; m.change = "" to "" })
                }
            }
            if (m.edits.isNotEmpty()) TextButton(m::clear) { Text("Clear environment overrides", color = DdoColors.Ember) }
        }
    }
}

@Composable
private fun EnvironmentRow(m: ProtonModel, e: GameEnvironment.Entry) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, color = DdoColors.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                if (!e.edited) Text("Default", color = DdoColors.TextDim, fontSize = 10.sp)
                else if (e.default != null) Text("Default: ${e.default}", color = DdoColors.TextDim, fontSize = 10.sp)
            }
            Text(e.value ?: "Off", color = if (e.edited) DdoColors.Gold else DdoColors.Text, fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.background(DdoColors.PanelHigh, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change…") }, onClick = { menu = false; m.change = e.name to (e.value ?: GameEnvironment.find(e.name)?.starter.orEmpty()) })
            if (e.value != null) DropdownMenuItem(text = { Text(if (e.default != null) "Unset for DDO" else "Remove") },
                onClick = { menu = false; if (e.default != null) m.save(e.name, null) else m.forget(e.name) })
            if (e.edited && e.default != null) DropdownMenuItem(text = { Text("Back to default") }, onClick = { menu = false; m.forget(e.name) })
        }
    }
}

@Composable
private fun ChangeDialog(m: ProtonModel, initialName: String, initialValue: String) {
    val custom = initialName.isEmpty()
    var name by remember(initialName) { mutableStateOf(initialName) }
    var value by remember(initialName) { mutableStateOf(initialValue) }
    val option = GameEnvironment.find(name)
    val valid = GameEnvironment.valid(name, value)
    AlertDialog(
        onDismissRequest = { m.change = null },
        title = { Text(if (custom) "Custom variable" else name, fontFamily = if (custom) null else FontFamily.Monospace) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                option?.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, color = DdoColors.TextDim, fontSize = 12.sp) }
                if (custom) OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true)
                when (option?.type) {
                    GameEnvironment.Type.TOGGLE, GameEnvironment.Type.CHOICE -> option.choices.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { value = c }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(value == c, { value = c })
                            Text(c, fontSize = 14.sp)
                        }
                    }
                    GameEnvironment.Type.MULTIPLE -> option.choices.forEach { c ->
                        val on = c in value.split(',')
                        Row(Modifier.fillMaxWidth().clickable { value = GameEnvironment.toggle(value, c) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { value = GameEnvironment.toggle(value, c) })
                            Text(c, fontSize = 14.sp)
                        }
                    }
                    else -> OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = option?.type == GameEnvironment.Type.NUMBER)
                }
                if (name.isNotEmpty() && GameEnvironment.unsupported(name)) {
                    Text("DDO Mobile's Wine and runtime do not read $name.", color = DdoColors.Gold, fontSize = 12.sp)
                }
                if (name.isNotEmpty() && !valid) Text("Names are letters, digits and _, not starting with a digit.", color = DdoColors.Ember, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ m.change = null; m.save(name, value) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton({ m.change = null }) { Text("Cancel") } },
    )
}
