package com.ddomobile.launcher.core

import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/** Unpacks Linux userspace archives with their symlinks, hard links and permissions intact. */
object Archives {
    fun extractTar(archive: File, target: File, stripComponents: Int = 0, progress: Progress = {}) {
        val total = archive.length().coerceAtLeast(1)
        val counted = CountingStream(BufferedInputStream(archive.inputStream(), 1 shl 16))
        val raw: InputStream = when {
            archive.name.endsWith(".zst") || archive.name.endsWith(".wcp") -> ZstdInputStream(counted)
            archive.name.endsWith(".gz") || archive.name.endsWith(".tgz") -> GZIPInputStream(counted, 1 shl 16)
            archive.name.endsWith(".xz") -> XZInputStream(counted)
            else -> counted
        }
        target.mkdirs()
        val base = target.canonicalFile
        val links = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(raw).use { tar ->
            var last = 0L
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.split('/').filter { it.isNotEmpty() && it != "." }.drop(stripComponents)
                if (name.isEmpty()) continue
                val out = File(base, name.joinToString("/"))
                if (!out.canonicalPath.startsWith(base.path) && !entry.isSymbolicLink) throw IOException("Unsafe path ${entry.name}")
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isLink -> {
                        val linked = entry.linkName.split('/').filter { it.isNotEmpty() && it != "." }.drop(stripComponents)
                        links += out to linked.joinToString("/")
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        Os.chmod(out.path, entry.mode and 0x1ff)
                    }
                }
                if (counted.count - last > (4 shl 20)) {
                    last = counted.count
                    progress(counted.count.toFloat() / total)
                }
            }
        }
        for ((out, source) in links) {
            out.parentFile?.mkdirs()
            out.delete()
            val from = File(base, source)
            try {
                Os.link(from.path, out.path)
            } catch (e: Exception) {
                from.copyTo(out, overwrite = true)
                if (from.canExecute()) out.setExecutable(true, false)
            }
        }
    }

    fun extractZip(archive: File, target: File, filter: (String) -> Boolean = { true }) {
        target.mkdirs()
        val base = target.canonicalFile
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !filter(entry.name)) continue
                val out = File(base, entry.name)
                if (!out.canonicalPath.startsWith(base.path)) throw IOException("Unsafe path ${entry.name}")
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private class CountingStream(input: InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
