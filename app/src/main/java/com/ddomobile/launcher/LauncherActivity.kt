package com.ddomobile.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddomobile.launcher.core.ChildProcessLimit
import com.ddomobile.launcher.ddo.DdoGls
import com.ddomobile.launcher.ddo.DdoLaunch
import com.ddomobile.launcher.ddo.DdoQuality
import com.ddomobile.launcher.ddo.DdoRenderer
import com.ddomobile.launcher.ddo.DdoSettings
import com.ddomobile.launcher.ddo.DdoWorld
import com.ddomobile.launcher.runtime.DriverStore
import com.ddomobile.launcher.runtime.Paths
import com.ddomobile.launcher.runtime.ProtonStore
import com.ddomobile.launcher.runtime.RuntimeStore
import com.ddomobile.launcher.session.GameSession
import com.ddomobile.launcher.ui.DdoColors
import com.ddomobile.launcher.ui.DdoTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LauncherActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val runtime by lazy { RuntimeStore(this) }
    private val protons by lazy { ProtonStore(this) }
    private val drivers by lazy { DriverStore(this) }

    private var storageAllowed by mutableStateOf(true)
    private var installProblem by mutableStateOf<String?>(null)
    private var runtimeVersion by mutableStateOf<String?>(null)
    private var runtimeArchive by mutableStateOf<File?>(null)
    private var protonName by mutableStateOf<String?>(null)
    private var driverName by mutableStateOf<String?>(null)
    private var childLimit by mutableStateOf(false)

    private var busy by mutableStateOf<String?>(null)
    private var busyProgress by mutableStateOf(-1f)
    private var busyError by mutableStateOf<String?>(null)

    private var worlds by mutableStateOf<List<DdoWorld>>(emptyList())
    private var worldError by mutableStateOf<String?>(null)
    private var world by mutableStateOf<String?>(null)
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var remember by mutableStateOf(false)
    private var playing by mutableStateOf(false)
    private var playStatus by mutableStateOf<String?>(null)
    private var playError by mutableStateOf<String?>(null)
    private val cancel = AtomicBoolean(false)

    private var installPath by mutableStateOf("")
    private var renderer by mutableStateOf(DdoRenderer.D3D11)
    private var quality by mutableStateOf(DdoQuality.MEDIUM)
    private var scale by mutableIntStateOf(50)
    private var fps by mutableIntStateOf(60)
    private var highRes by mutableStateOf(true)
    private var hud by mutableStateOf(false)
    private var fastCpu by mutableStateOf(true)
    private var diagnostics by mutableStateOf(false)

    private val pickDriver = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importDriver) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        username = DdoSettings.username(this)
        remember = DdoSettings.rememberPassword(this)
        password = DdoSettings.savedPassword(this).orEmpty()
        world = DdoSettings.world(this)
        installPath = DdoSettings.installDir(this).absolutePath
        renderer = DdoSettings.renderer(this)
        quality = DdoSettings.quality(this)
        scale = DdoSettings.renderScale(this)
        fps = DdoSettings.frameLimit(this)
        highRes = DdoSettings.highRes(this)
        hud = DdoSettings.showHud(this)
        fastCpu = DdoSettings.fastCpu(this)
        diagnostics = DdoSettings.diagnostics(this)
        setContent { DdoTheme { Screen() } }
        loadWorlds()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (GameSession.running) startActivity(Intent(this, GameActivity::class.java))
    }

    private fun refresh() {
        storageAllowed = Environment.isExternalStorageManager()
        installProblem = if (storageAllowed) DdoSettings.installProblem(this) else "Allow access to all files so the game folder can be read"
        runtimeVersion = runtime.installedVersion()
        runtimeArchive = if (storageAllowed) runtime.localArchive() else null
        protonName = protons.selected()
        childLimit = ChildProcessLimit.blocks(this)
        driverName = drivers.selectedLinux()?.let { id -> drivers.installed().firstOrNull { it.id == id }?.name ?: id }
    }

    private fun loadWorlds() {
        worldError = null
        Thread({
            val result = runCatching { DdoGls.datacenter().worlds }
            ui.post {
                result.onSuccess { list ->
                    worlds = list
                    if (world == null || list.none { it.name == world }) world = list.firstOrNull()?.name
                }.onFailure { worldError = it.message ?: "Could not reach the DDO servers" }
            }
        }, "worlds").start()
    }

    private fun work(label: String, job: ((String, Float) -> Unit) -> Unit) {
        if (busy != null) return
        busy = label; busyProgress = -1f; busyError = null
        Thread({
            val result = runCatching { job { stage, p -> ui.post { busy = stage; busyProgress = p } } }
            ui.post {
                busy = null
                busyError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                refresh()
            }
        }, "setup").start()
    }

    private fun installRuntime() {
        val archive = runtimeArchive ?: return
        work("Installing the runtime") { stage -> runtime.install(archive, null, stage) }
    }

    private fun installProton() = work("Installing Proton") { stage -> protons.install(ProtonStore.DROIDDECK, stage) }

    private fun downloadDriver() = work("Installing DD-Turnip") { stage -> drivers.downloadLatest(stage) }

    /** Whatever is still missing, in order: runtime, Proton, Turnip. */
    private fun setUpEverything() = work("Setting up") { stage ->
        if (runtime.installedVersion() == null) {
            val archive = runtime.localArchive() ?: throw java.io.IOException("Put ddo-runtime-*.tar.zst in Download first")
            runtime.install(archive, null, stage)
        }
        if (protons.selected() == null) protons.install(ProtonStore.DROIDDECK, stage)
        if (drivers.selectedLinux() == null) drivers.downloadLatest(stage)
    }

    private val needsSetup get() = runtimeVersion == null || protonName == null || driverName == null

    private fun importDriver(uri: Uri) = work("Importing the driver") { stage ->
        stage("Copying the driver", -1f)
        val copy = File(cacheDir, "import-driver.zip")
        contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
        try { drivers.import(copy) } finally { copy.delete() }
    }

    private fun requestStorage() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    /** The game's render size: the panel's landscape resolution at the chosen share, kept even for the encoder paths. */
    private fun outputSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        val short = minOf(metrics.widthPixels, metrics.heightPixels)
        fun even(v: Int) = (v / 2) * 2
        return even(long * scale / 100) to even(short * scale / 100)
    }

    private fun launch(request: String) {
        val (w, h) = outputSize()
        GameSession.start(this, w, h, request, emptyMap())
        startActivity(Intent(this, GameActivity::class.java))
    }

    private fun play() {
        if (playing) return
        if (GameSession.running) { startActivity(Intent(this, GameActivity::class.java)); return }
        val chosen = world ?: return
        val user = username.trim()
        val pass = password
        DdoSettings.setUsername(this, user)
        DdoSettings.storePassword(this, remember, pass)
        playing = true; playError = null; playStatus = "Starting…"
        cancel.set(false)
        Thread({
            val result = runCatching { DdoLaunch.prepare(this, user, pass, chosen, { s -> ui.post { playStatus = s } }, { cancel.get() }) }
            ui.post {
                playing = false
                playStatus = null
                result.onSuccess(::launch).onFailure { e -> if (e !is InterruptedException) playError = e.message ?: e.javaClass.simpleName }
            }
        }, "play").start()
    }

    private fun testLaunch() {
        if (playing || GameSession.running) return
        playError = null
        runCatching { DdoLaunch.prepareTest(this) }.onSuccess(::launch).onFailure { playError = it.message ?: it.javaClass.simpleName }
    }

    private val ready get() = installProblem == null && runtimeVersion != null && protonName != null && busy == null && !childLimit

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(DdoColors.Background).safeDrawingPadding()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header()
                    PlayPanel()
                    SetupPanel()
                    GraphicsPanel()
                    ToolsPanel()
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DUNGEONS & DRAGONS", color = DdoColors.Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
            Text("ONLINE", color = DdoColors.Ember, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif,
                letterSpacing = 6.sp)
            Spacer(Modifier.height(4.dp))
            Text("DDO Mobile", color = DdoColors.TextDim, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
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
    private fun PlayPanel() {
        Panel("Play") {
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it })
                Text("Remember password (stored encrypted on this phone)", color = DdoColors.TextDim, fontSize = 13.sp)
            }
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ open = true }, enabled = worlds.isNotEmpty() && !playing, modifier = Modifier.fillMaxWidth()) {
                    Text(world?.let { "World: $it" } ?: if (worldError != null) "Servers unavailable" else "Loading worlds…")
                }
                DropdownMenu(open, { open = false }) {
                    worlds.forEach { w -> DropdownMenuItem(text = { Text(w.name) }, onClick = { world = w.name; open = false }) }
                }
            }
            worldError?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = DdoColors.Ember, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(::loadWorlds) { Text("Retry") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(::play, enabled = ready && !playing && username.isNotBlank() && password.isNotEmpty() && world != null,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DdoColors.Ember, contentColor = DdoColors.Text)) {
                    if (playing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = DdoColors.Text)
                    else Text(if (GameSession.running) "RETURN TO GAME" else "PLAY", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif, letterSpacing = 3.sp)
                }
                if (playing) OutlinedButton({ cancel.set(true) }, Modifier.height(56.dp)) { Text("Cancel") }
            }
            playStatus?.let { Text(it, color = DdoColors.TextDim, fontSize = 13.sp) }
            playError?.let { Text(it, color = DdoColors.Ember, fontSize = 13.sp) }
        }
    }

    @Composable
    private fun Check(label: String, ok: Boolean, detail: String, action: String? = null, onAction: () -> Unit = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (ok) DdoColors.Good else DdoColors.Ember, RoundedCornerShape(5.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = DdoColors.Text, fontSize = 14.sp)
                Text(detail, color = DdoColors.TextDim, fontSize = 12.sp)
            }
            if (action != null) TextButton(onAction, enabled = busy == null) { Text(action) }
        }
    }

    @Composable
    private fun SetupPanel() {
        Panel("Setup") {
            if (needsSetup && (runtimeVersion != null || runtimeArchive != null)) {
                Button(::setUpEverything, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Set up everything") }
            }
            Check("Game files", installProblem == null, installProblem ?: installPath,
                if (!storageAllowed) "Allow" else null, ::requestStorage)
            Check("Runtime", runtimeVersion != null,
                runtimeVersion?.let { "ddo-runtime $it" } ?: runtimeArchive?.let { "Ready to install ${it.name}" }
                ?: "Put ddo-runtime-*.tar.zst in Download",
                if (runtimeArchive != null && runtimeArchive?.name != "ddo-runtime-$runtimeVersion.tar.zst") "Install" else null,
                ::installRuntime)
            Check("Proton (ARM64, FEX for x86)", protonName != null,
                protonName ?: if (runtimeVersion == null) "Install the runtime first" else "DroidDeck-Proton, about 600 MB",
                if (runtimeVersion != null) (if (protonName == null) "Install" else "Update") else null, ::installProton)
            Check("Turnip driver", driverName != null,
                driverName ?: if (runtimeVersion == null) "Install the runtime first" else "DD-Turnip, about 30 MB",
                if (runtimeVersion != null) (if (driverName == null) "Install" else "Update") else null, ::downloadDriver)
            Check("Child process limit", !childLimit, if (childLimit) ChildProcessLimit.INSTRUCTIONS else "Off",
                if (childLimit) "Settings" else null) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            if (runtimeVersion != null) {
                TextButton({ pickDriver.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = busy == null) {
                    Text("Import a Turnip zip (DD-Turnip, adrenotools or Linux)")
                }
            }
            busy?.let { stage ->
                Text(stage, color = DdoColors.TextDim, fontSize = 12.sp)
                if (busyProgress in 0f..1f) LinearProgressIndicator(progress = { busyProgress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            busyError?.let { Text(it, color = DdoColors.Ember, fontSize = 12.sp) }
        }
    }

    @Composable
    private fun GraphicsPanel() {
        Panel("Graphics") {
            Text("Renderer", color = DdoColors.TextDim, fontSize = 13.sp)
            DdoRenderer.entries.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(renderer == r, { renderer = r; DdoSettings.setRenderer(this@LauncherActivity, r) })
                    Text(r.label, color = DdoColors.Text, fontSize = 14.sp)
                }
            }
            Text("Quality", color = DdoColors.TextDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DdoQuality.entries.forEach { q ->
                    FilterChip(quality == q, { quality = q; DdoSettings.setQuality(this@LauncherActivity, q) }, label = { Text(q.label) })
                }
            }
            Text("Render resolution", color = DdoColors.TextDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DdoSettings.RENDER_SCALES.forEach { s ->
                    FilterChip(scale == s, { scale = s; DdoSettings.setRenderScale(this@LauncherActivity, s) }, label = { Text("$s%") })
                }
            }
            Text("Frame limit", color = DdoColors.TextDim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 45, 60, 120).forEach { f ->
                    FilterChip(fps == f, { fps = f; DdoSettings.setFrameLimit(this@LauncherActivity, f) }, label = { Text("$f") })
                }
            }
            Toggle("High-resolution textures", highRes) { highRes = it; DdoSettings.setHighRes(this, it) }
            Toggle("Performance overlay (DXVK)", hud) { hud = it; DdoSettings.setShowHud(this, it) }
            Toggle("Fast CPU mode (turn off if the game hangs or crashes)", fastCpu) { fastCpu = it; DdoSettings.setFastCpu(this, it) }
        }
    }

    @Composable
    private fun ToolsPanel() {
        Panel("Tools") {
            Text("Game folder", color = DdoColors.TextDim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(installPath, { installPath = it }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton({ DdoSettings.setInstallDir(this@LauncherActivity, File(installPath.trim())); refresh() }) { Text("Use") }
            }
            OutlinedButton(::testLaunch, enabled = ready && !playing, modifier = Modifier.fillMaxWidth()) {
                Text("Test launch (no sign-in)")
            }
            Toggle("Performance diagnostics (FEX statistics in the session log)", diagnostics) {
                diagnostics = it; DdoSettings.setDiagnostics(this, it)
            }
            Text("Logs:${Paths(this@LauncherActivity).logs.absolutePath}", color = DdoColors.TextDim, fontSize = 11.sp)
        }
    }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = DdoColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(value, onChange)
        }
    }
}
