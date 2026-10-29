package com.ddomobile.launcher.session

import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import com.ddomobile.launcher.runtime.Paths
import java.io.File

/** The proot command line for the runtime: the guest root, the host paths the session needs, and a GPU node. */
class Proot(private val paths: Paths) {
    val binary = File(paths.nativeLibs, "libproot.so")
    val loader = File(paths.nativeLibs, "libproot-loader.so")

    fun hostEnvironment(): Map<String, String> = mapOf(
        "PROOT_LOADER" to loader.path,
        "PROOT_TMP_DIR" to paths.prootTmp.apply { mkdirs() }.path,
    )

    fun command(guest: List<String>): List<String> {
        // The app's own uid inside, with proot answering set*id: Xwayland changes ids, and files keep one owner.
        val uid = android.os.Process.myUid()
        val cmd = mutableListOf(binary.path, "--kill-on-exit", "--kernel-release=6.6.0-ddo", "-i", "$uid:$uid", "-r", paths.rootfs.path, "-w", "/root")
        fun bind(spec: String) { cmd += "-b"; cmd += spec }
        bind("/dev")
        bind("/proc")
        bind("/sys")
        bind("/dev/urandom:/dev/random")
        bind("/proc/self/fd:/dev/fd")
        bind("/proc/self/fd/0:/dev/stdin")
        bind("/proc/self/fd/1:/dev/stdout")
        bind("/proc/self/fd/2:/dev/stderr")
        bind("${emptyDir("empty").path}:/sys/fs/selinux")
        bind("${paths.run.apply { mkdirs() }.path}:${Paths.GUEST_RUN}")
        bind("${File(paths.files, "shm").apply { mkdirs() }.path}:/dev/shm")
        val storage = Environment.getExternalStorageDirectory()
        if (storage.canRead()) bind("${storage.path}:${Paths.GUEST_STORAGE}")
        fakeProcFiles().forEach(::bind)
        gpuNode()?.forEach(::bind)
        cmd += guest
        return cmd
    }

    /** Gives the app's uid a name and the /root home inside, so getpwuid() works for Wine and Python. */
    fun prepareAccount() {
        val uid = android.os.Process.myUid()
        val passwd = File(paths.rootfs, "etc/passwd")
        val group = File(paths.rootfs, "etc/group")
        if (passwd.isFile && passwd.readLines().none { it.split(':').getOrNull(2) == uid.toString() }) {
            passwd.appendText("ddo:x:$uid:$uid:DDO Mobile:/root:/bin/bash\n")
        }
        if (group.isFile && group.readLines().none { it.split(':').getOrNull(2) == uid.toString() }) {
            group.appendText("ddo:x:$uid:\n")
        }
    }

    private fun emptyDir(name: String) = File(paths.files, "proot/$name").apply { mkdirs() }

    /** Android hides some /proc files from apps; Wine and Mesa read them, so the guest gets plausible copies. */
    private fun fakeProcFiles(): List<String> {
        val dir = File(paths.files, "proot/proc").apply { mkdirs() }
        val fakes = mapOf(
            "/proc/sys/fs/file-max" to "9223372036854775807\n",
            "/proc/sys/vm/overcommit_memory" to "0\n",
            "/proc/sys/kernel/cap_last_cap" to "40\n",
            "/proc/bus/pci/devices" to "",
        )
        return fakes.mapNotNull { (guest, content) ->
            if (File(guest).canRead()) return@mapNotNull null
            val file = File(dir, guest.trimStart('/').replace('/', '_'))
            if (!file.isFile) file.writeText(content)
            "${file.path}:$guest"
        }
    }

    /** Presents /dev/kgsl-3d0 as a DRM render node with the sysfs entries libdrm and Mesa look for. */
    private fun gpuNode(): List<String>? {
        val st = try { Os.stat(KGSL) } catch (e: ErrnoException) { return null }
        val dev = st.st_rdev
        val major = ((dev shr 8) and 0xfff) or ((dev shr 32) and 0xfff.inv().toLong())
        val minor = (dev and 0xff) or ((dev shr 12) and 0xff.inv().toLong())
        val node = "renderD$minor"
        val base = File(paths.files, "proot/drm")
        val dri = File(base, "dri").apply { mkdirs() }
        val device = File(base, "sys/$major:$minor/device")
        val drm = File(device, "drm/$node").apply { mkdirs() }
        try {
            File(dri, node).createNewFile()
            File(drm, "dev").writeText("$major:$minor\n")
            File(device, "uevent").writeText("DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n")
            symlink("/sys/bus/platform", File(device, "subsystem"))
            symlink("/sys/bus/platform/drivers/msm_drm", File(device, "driver"))
        } catch (e: Exception) {
            return null
        }
        val binds = mutableListOf("${File(base, "sys").path}:/sys/dev/char", "${dri.path}:/dev/dri", "$KGSL:/dev/dri/$node")
        if (File("/sys/class/drm").list() == null) {
            val cls = File(base, "class").apply { mkdirs() }
            cls.listFiles()?.forEach { it.delete() }
            symlink("/sys/dev/char/$major:$minor", File(cls, node))
            binds += "${cls.path}:/sys/class/drm"
        }
        return binds
    }

    private fun symlink(target: String, link: File) {
        if (java.nio.file.Files.isSymbolicLink(link.toPath())) return
        link.delete()
        Os.symlink(target, link.path)
    }

    companion object {
        private const val KGSL = "/dev/kgsl-3d0"
    }
}
