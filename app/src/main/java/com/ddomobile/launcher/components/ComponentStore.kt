package com.ddomobile.launcher.components

import android.content.Context
import android.system.Os
import com.ddomobile.launcher.core.Archives
import com.ddomobile.launcher.core.Http
import com.ddomobile.launcher.core.Progress
import com.ddomobile.launcher.runtime.Paths
import com.ddomobile.launcher.session.GameSession
import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stored component packages and what each Proton has swapped in.
 *
 * Before a package overwrites or removes a file of a Proton, that file is saved under originals/<Proton>/<kind>, and a
 * file the Proton did not have is listed as added; restoring puts the saved files back and deletes the added ones, so
 * every swap starts from the Proton as it shipped. DDO Mobile's own FEX build is the FEX default: ddo-run swaps it in at
 * launch unless a FEX choice of this page is active, and the Proton's libarm64ecfex.dll.orig it leaves is the original.
 *
 * A swap while the game runs waits in the queue until it closes.
 */
class ComponentStore(context: Context) {
    private val paths = Paths(context)
    private val root = File(context.filesDir, "components")
    private val packages = File(root, "packages")
    private val originals = File(root, "originals")
    private val stateFile = File(root, "state.json")
    private val staging = File(context.cacheDir, "components-staging")

    /** A choice for one kind in one Proton: the default build, the Proton's original, or a stored package. */
    sealed interface Choice {
        data object Default : Choice
        data object Original : Choice
        data class Package(val file: String) : Choice
    }

    class Stored(val file: String, val kind: ComponentKind, val version: String, val description: String, val size: Long)

    class Active(val choice: Choice, val label: String, val at: Long)

    private val runtimeFex get() = File(paths.rootfs, "usr/local/lib/ddo-mobile/libarm64ecfex.dll")

    /** Whether DDO Mobile's FEX build is there to be the default. */
    fun hasRuntimeFex(): Boolean = runtimeFex.isFile

    fun protonDir(proton: String) = File(paths.proton, proton)

    // ---- stored packages

    @Synchronized
    fun stored(kind: ComponentKind? = null): List<Stored> = packages.listFiles { f -> f.name.endsWith(".wcp") }.orEmpty()
        .mapNotNull { f ->
            val meta = runCatching { JSONObject(File(packages, f.name + ".json").readText()) }.getOrNull() ?: return@mapNotNull null
            val k = ComponentKind.ofId(meta.optString("comp")) ?: return@mapNotNull null
            if (kind != null && k != kind) null else Stored(f.name, k, meta.optString("versionName"), meta.optString("description"), f.length())
        }.sortedWith { a, b -> -Catalog.compareVersions(a.version, b.version) }

    fun download(item: CatalogItem, progress: Progress): Stored {
        if (Catalog.TRUSTED.none { item.url.startsWith(it) }) throw IOException("${item.file} is not from a component page")
        val tmp = File(staging, item.file)
        Http.download(item.url, tmp, item.sha256, progress)
        return store(tmp, item.file, item.kind)
    }

    /** A .wcp picked from the phone's storage; its kind comes from its profile.json. */
    fun import(file: File, name: String): Stored = store(file, name, null)

    @Synchronized
    private fun store(source: File, name: String, expected: ComponentKind?): Stored {
        try {
            val profile = profile(source) ?: throw IOException("$name is not a component package (no profile.json)")
            val kind = ComponentKind.ofType(profile.optString("type"))
                ?: throw IOException("$name is a ${profile.optString("type").ifEmpty { "unknown" }} package, which DDO Mobile does not use")
            if (expected != null && kind != expected) throw IOException("$name holds ${kind.label}, not ${expected.label}")
            val safe = name.replace(Regex("[^A-Za-z0-9._+-]+"), "_").let { if (it.endsWith(".wcp")) it else "$it.wcp" }
            if (inUse(safe)) throw IOException("$safe is in use; choose another build first")
            packages.mkdirs()
            val target = File(packages, safe)
            source.copyTo(target, overwrite = true)
            File(packages, "$safe.json").writeText(JSONObject().put("comp", kind.id)
                .put("versionName", profile.optString("versionName").ifEmpty { safe.removeSuffix(".wcp") })
                .put("description", profile.optString("description")).toString())
            return stored(kind).first { it.file == safe }
        } finally {
            if (source.parentFile == staging) source.delete()
        }
    }

    @Synchronized
    fun remove(file: String) {
        if (inUse(file)) throw IOException("$file is in use; choose another build first")
        File(packages, file).delete()
        File(packages, "$file.json").delete()
    }

    private fun inUse(file: String): Boolean {
        val state = state()
        return listOf(state.optJSONObject("active"), state.optJSONObject("queued")).any { section ->
            section?.keys()?.asSequence()?.any { proton ->
                val kinds = section.getJSONObject(proton)
                kinds.keys().asSequence().any { kinds.getJSONObject(it).optString("file") == file }
            } == true
        }
    }

    // ---- what a Proton has in use

    @Synchronized
    fun active(proton: String, kind: ComponentKind): Active {
        val o = state().optJSONObject("active")?.optJSONObject(proton)?.optJSONObject(kind.id)
            ?: return Active(Choice.Default, defaultLabel(proton, kind), 0)
        return Active(choice(o.optString("file")), o.optString("label"), o.optLong("at"))
    }

    @Synchronized
    fun queued(proton: String, kind: ComponentKind): Choice? =
        state().optJSONObject("queued")?.optJSONObject(proton)?.optJSONObject(kind.id)?.let { choice(it.optString("file")) }

    /** What the Proton shipped with, as its version file or FEX build string says. */
    fun originalVersion(proton: String, kind: ComponentKind): String {
        val dir = protonDir(proton)
        return when (kind) {
            ComponentKind.FEX -> {
                val dll = File(dir, "files/lib/wine/aarch64-windows/libarm64ecfex.dll")
                val saved = File(originals, "$proton/fex/files/lib/wine/aarch64-windows/libarm64ecfex.dll")
                fexVersion(listOf(saved, File(dll.path + ".orig"), dll).firstOrNull { it.isFile })
            }
            // "<commit> dxvk (v3.1.1-134-g2cb4018)"
            else -> runCatching { File(dir, kind.dirs[0] + "/version").readLines().first().trim() }.getOrNull()
                ?.let { Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1) ?: it }?.ifEmpty { null } ?: "unknown"
        }
    }

    fun runtimeFexVersion(): String = fexVersion(runtimeFex.takeIf { it.isFile })

    private fun defaultLabel(proton: String, kind: ComponentKind) =
        if (kind == ComponentKind.FEX && hasRuntimeFex()) "DDO Mobile's build · ${runtimeFexVersion()}" else "Original · ${originalVersion(proton, kind)}"

    /** True while a FEX choice of this page is in the Proton, which ddo-run must then leave alone. */
    fun keepsProtonFex(proton: String): Boolean = active(proton, ComponentKind.FEX).choice != Choice.Default

    /**
     * Puts [choice] into [proton], or queues it while the game runs. Returns true when applied now. For DXVK and
     * VKD3D the Default is the original, so both mean the same.
     */
    @Synchronized
    fun select(proton: String, kind: ComponentKind, choice: Choice): Boolean {
        if (GameSession.running) {
            val state = state()
            val queued = state.optJSONObject("queued") ?: JSONObject().also { state.put("queued", it) }
            val forProton = queued.optJSONObject(proton) ?: JSONObject().also { queued.put(proton, it) }
            forProton.put(kind.id, JSONObject().put("file", key(choice)))
            save(state)
            return false
        }
        apply(proton, kind, choice)
        return true
    }

    @Synchronized
    fun cancelQueued(proton: String, kind: ComponentKind) {
        val state = state()
        state.optJSONObject("queued")?.optJSONObject(proton)?.remove(kind.id)
        save(state)
    }

    @Synchronized
    fun hasQueued(): Boolean = state().optJSONObject("queued")?.let { q ->
        q.keys().asSequence().any { q.getJSONObject(it).length() > 0 }
    } == true

    /** Applies what waited for the game to close. */
    @Synchronized
    fun applyQueued(): List<String> {
        if (GameSession.running) return emptyList()
        val state = state()
        val queued = state.optJSONObject("queued") ?: return emptyList()
        val done = ArrayList<String>()
        for (proton in queued.keys().asSequence().toList()) {
            val kinds = queued.getJSONObject(proton)
            for (id in kinds.keys().asSequence().toList()) {
                val kind = ComponentKind.ofId(id) ?: continue
                runCatching { apply(proton, kind, choice(kinds.getJSONObject(id).optString("file"))) }
                    .onSuccess { done += "${kind.label}: ${active(proton, kind).label}" }
            }
        }
        val after = state()
        after.remove("queued")
        save(after)
        return done
    }

    private fun apply(proton: String, kind: ComponentKind, choice: Choice) {
        val dir = protonDir(proton)
        if (!File(dir, "proton").isFile) throw IOException("$proton is not installed")
        restore(proton, kind)
        val record = when (choice) {
            Choice.Default -> null
            Choice.Original -> if (kind == ComponentKind.FEX && hasRuntimeFex()) entry(choice, "Original · ${originalVersion(proton, kind)}") else null
            is Choice.Package -> {
                val pkg = File(packages, choice.file)
                if (!pkg.isFile) throw IOException("${choice.file} is no longer stored")
                install(proton, kind, pkg)
                entry(choice, stored(kind).firstOrNull { it.file == choice.file }?.version ?: choice.file)
            }
        }
        val state = state()
        val active = state.optJSONObject("active") ?: JSONObject().also { state.put("active", it) }
        val forProton = active.optJSONObject(proton) ?: JSONObject().also { active.put(proton, it) }
        if (record == null) forProton.remove(kind.id) else forProton.put(kind.id, record.put("added", addedList(proton, kind)))
        save(state)
    }

    private fun entry(choice: Choice, label: String) = JSONObject().put("file", key(choice)).put("label", label)
        .put("at", System.currentTimeMillis())

    private fun install(proton: String, kind: ComponentKind, pkg: File) {
        val dir = protonDir(proton)
        val unpacked = File(staging, "unpacked")
        unpacked.deleteRecursively()
        try {
            Archives.extractTar(pkg, unpacked)
            val files = File(unpacked, "files")
            if (!files.isDirectory) throw IOException("${pkg.name} has no files/ folder")
            val incoming = files.walkTopDown().filter { it.isFile }
                .map { "files/" + it.relativeTo(files).invariantSeparatorsPath to it }
                .filter { (rel, _) -> kind.dirs.any { rel.startsWith("$it/") } }
                .toMap()
            if (incoming.isEmpty()) throw IOException("${pkg.name} has nothing for ${kind.label}")
            if (kind.managed.isNotEmpty()) {
                val archDirs = incoming.keys.map { it.substringBeforeLast('/') }.toSet()
                for (arch in archDirs) for (dll in kind.managed) {
                    val rel = "$arch/$dll"
                    if (rel !in incoming && File(dir, rel).isFile) {
                        saveOriginal(proton, kind, rel)
                        File(dir, rel).delete()
                    }
                }
            }
            for ((rel, source) in incoming) {
                saveOriginal(proton, kind, rel)
                val target = File(dir, rel)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".ddo-new")
                source.copyTo(tmp, overwrite = true)
                if (rel.endsWith(".so") || rel.endsWith(".dll")) Os.chmod(tmp.path, 493)
                if (!tmp.renameTo(target)) throw IOException("Could not write $rel")
            }
        } finally {
            unpacked.deleteRecursively()
        }
    }

    /** Saves the Proton's own copy of [rel] once, or notes that it had none. */
    private fun saveOriginal(proton: String, kind: ComponentKind, rel: String) {
        val base = File(originals, "$proton/${kind.id}")
        val saved = File(base, rel)
        val added = File(base, "added.txt")
        if (saved.exists() || added.isFile && rel in added.readLines()) return
        val current = File(protonDir(proton), rel)
        // ddo-run swaps DDO Mobile's FEX in and keeps the Proton's own beside it.
        val own = File(current.path + ".orig").takeIf { it.isFile } ?: current
        if (own.isFile) {
            saved.parentFile?.mkdirs()
            own.copyTo(saved)
        } else {
            base.mkdirs()
            added.appendText(rel + "\n")
        }
    }

    private fun addedList(proton: String, kind: ComponentKind): JSONArray =
        JSONArray(File(originals, "$proton/${kind.id}/added.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty())

    /** Puts back what the kind's swaps changed in the Proton. */
    private fun restore(proton: String, kind: ComponentKind) {
        val base = File(originals, "$proton/${kind.id}")
        if (!base.isDirectory) return
        val dir = protonDir(proton)
        File(base, "added.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.forEach { File(dir, it).delete() }
        val files = File(base, "files")
        if (files.isDirectory) {
            files.walkTopDown().filter { it.isFile }.forEach { saved ->
                val rel = "files/" + saved.relativeTo(files).invariantSeparatorsPath
                val target = File(dir, rel)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".ddo-new")
                saved.copyTo(tmp, overwrite = true)
                if (rel.endsWith(".so") || rel.endsWith(".dll")) Os.chmod(tmp.path, 493)
                if (!tmp.renameTo(target)) throw IOException("Could not restore $rel")
            }
        }
    }

    /** Forgets a removed Proton's choices and saved originals. */
    @Synchronized
    fun forgetProton(proton: String) {
        File(originals, proton).deleteRecursively()
        val state = state()
        state.optJSONObject("active")?.remove(proton)
        state.optJSONObject("queued")?.remove(proton)
        save(state)
    }

    // ---- state file

    private fun state(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())

    private fun save(state: JSONObject) {
        root.mkdirs()
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(state.toString())
        if (!tmp.renameTo(stateFile)) throw IOException("Could not save the component choices")
    }

    private fun key(choice: Choice) = when (choice) {
        Choice.Default -> "default"
        Choice.Original -> "original"
        is Choice.Package -> choice.file
    }

    private fun choice(key: String): Choice = when (key) {
        "", "default" -> Choice.Default
        "original" -> Choice.Original
        else -> Choice.Package(key)
    }

    companion object {
        private val FEX_VERSION = Regex("FEX-\\d{4}[0-9A-Za-z.+-]*")

        fun fexVersion(file: File?): String {
            if (file == null) return "unknown"
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            return FEX_VERSION.findAll(text).map { it.value }.maxByOrNull { it.length } ?: "unknown"
        }

        /** profile.json of a .wcp, read without unpacking the rest. */
        fun profile(wcp: File): JSONObject? = runCatching {
            TarArchiveInputStream(ZstdInputStream(BufferedInputStream(wcp.inputStream(), 1 shl 16))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (e.name.removePrefix("./") == "profile.json") return@runCatching JSONObject(tar.readBytes().toString(Charsets.UTF_8))
                }
                null
            }
        }.getOrNull()
    }
}
