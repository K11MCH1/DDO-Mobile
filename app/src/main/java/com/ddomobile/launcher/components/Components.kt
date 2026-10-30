package com.ddomobile.launcher.components

/**
 * The parts of a Proton that can be swapped for another build, as DroidDeck's Components page does: packages are
 * `.wcp` files (a zstd tar with profile.json and files/lib/wine/...) from The412Banner's Nightlies and DroidDeck's
 * own nightlies. [dirs] are the Proton folders a package of the kind writes into; in [managed] ones, the DLLs of
 * that list a package leaves out are removed, so a swap never mixes two builds.
 */
enum class ComponentKind(val id: String, val label: String, val wcpType: String, val dirs: List<String>, val managed: Set<String>) {
    DXVK("dxvk", "DXVK", "DXVK-Linux", listOf("files/lib/wine/dxvk"),
        setOf("d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")),
    VKD3D("vkd3d", "VKD3D-Proton", "VKD3D-Linux", listOf("files/lib/wine/vkd3d-proton"), setOf("d3d12.dll", "d3d12core.dll")),
    FEX("fex", "FEX", "FEXCore-Linux", listOf("files/lib/wine/aarch64-windows", "files/lib/wine/aarch64-unix"), emptySet());

    companion object {
        fun ofType(type: String): ComponentKind? = entries.firstOrNull { it.wcpType.equals(type, ignoreCase = true) }
        fun ofId(id: String): ComponentKind? = entries.firstOrNull { it.id == id }
    }
}

/** One package on a release page. [nightly] is DroidDeck's linux-nightly channel; the rest is stable. */
data class CatalogItem(
    val file: String,
    val kind: ComponentKind,
    val release: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val nightly: Boolean,
)

/** A line of the list: a stable build, the nightly paired with it, or a nightly alone. */
data class CatalogRow(val stable: CatalogItem?, val nightly: CatalogItem?) {
    val primary: CatalogItem get() = stable ?: nightly!!
}

object Catalog {
    class Source(val repo: String, val tag: String, val kind: ComponentKind?, val nightly: Boolean)

    val SOURCES = listOf(
        Source("The412Banner/Nightlies", "FexCore-Linux", ComponentKind.FEX, false),
        Source("The412Banner/Nightlies", "Dxvk-Linux", ComponentKind.DXVK, false),
        Source("The412Banner/Nightlies", "Dxvk-arm64ec-Linux", ComponentKind.DXVK, false),
        Source("The412Banner/Nightlies", "Dxvk-gplasync-Linux", ComponentKind.DXVK, false),
        Source("The412Banner/Nightlies", "Vkd3d-proton-Linux", ComponentKind.VKD3D, false),
        Source("Droid-Deck/DroidDeck-Components", "linux-nightly", null, true),
    )

    /** Downloads are only taken from these release pages. */
    val TRUSTED = listOf("The412Banner/Nightlies", "Droid-Deck/DroidDeck-Components")
        .map { "https://github.com/$it/releases/download/" }

    private val STABLE = Regex("^(.+?)-(\\d[^/]*?)-linux\\.wcp$", RegexOption.IGNORE_CASE)
    private val NIGHTLY = Regex("^DD-(.+?)-(\\d[^/]*?)-nightly\\.wcp$", RegexOption.IGNORE_CASE)
    private val NIGHTLY_KINDS = listOf("DD-dxvk-" to ComponentKind.DXVK, "DD-vkd3d-proton-" to ComponentKind.VKD3D,
        "DD-FEXCore-" to ComponentKind.FEX)

    /** The kind of a linux-nightly file, by its name. */
    fun nightlyKind(file: String): ComponentKind? =
        NIGHTLY_KINDS.firstOrNull { file.startsWith(it.first, ignoreCase = true) }?.second

    /** The family and version in a file name: dxvk-gplasync and 2.6.2-1 for dxvk-gplasync-2.6.2-1-linux.wcp. */
    fun parse(file: String): Pair<String, String>? {
        val m = NIGHTLY.find(file) ?: STABLE.find(file) ?: return null
        return m.groupValues[1].lowercase() to m.groupValues[2]
    }

    /** Orders "3.1.1+168" after "3.1.1" and "2.10" after "2.9". */
    fun compareVersions(a: String, b: String): Int {
        val x = Regex("\\d+|[A-Za-z]+").findAll(a).map { it.value }.toList()
        val y = Regex("\\d+|[A-Za-z]+").findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q, ignoreCase = true)
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Stable builds newest first; a DroidDeck nightly rides with the newest stable build of its family (dxvk,
     * dxvk-arm64ec, FEXCore...) as that row's Nightly choice, and stands alone when the family has none. Files
     * already stored are left out.
     */
    fun rows(items: List<CatalogItem>, kind: ComponentKind, stored: Set<String>): List<CatalogRow> {
        val mine = items.filter { it.kind == kind && it.file !in stored }
        val byVersion = compareBy<CatalogItem>({ parse(it.file)?.first ?: it.file }).thenComparator { a, b ->
            -compareVersions(parse(a.file)?.second ?: "", parse(b.file)?.second ?: "")
        }
        val stable = mine.filter { !it.nightly }.sortedWith(byVersion)
        val newest = stable.groupBy { parse(it.file)?.first }.mapValues { it.value.first() }
        val nightlies = mine.filter { it.nightly }.sortedWith(byVersion)
        val paired = HashMap<CatalogItem, CatalogItem>()
        val alone = ArrayList<CatalogItem>()
        for (n in nightlies) {
            val head = newest[parse(n.file)?.first]
            if (head != null && head !in paired) paired[head] = n else alone += n
        }
        return stable.map { CatalogRow(it, paired[it]) } + alone.map { CatalogRow(null, it) }
    }
}
