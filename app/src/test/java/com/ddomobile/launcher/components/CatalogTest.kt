package com.ddomobile.launcher.components

import com.ddomobile.launcher.runtime.TurnipCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun item(file: String, kind: ComponentKind, nightly: Boolean = false) =
        CatalogItem(file, kind, if (nightly) "linux-nightly" else "Dxvk-Linux", "https://github.com/x/releases/download/t/$file", 1, "0".repeat(64), nightly)

    @Test
    fun versionsCompareByNumber() {
        assertTrue(Catalog.compareVersions("2.10", "2.9") > 0)
        assertTrue(Catalog.compareVersions("3.1.1+168", "3.1.1") > 0)
        assertEquals(0, Catalog.compareVersions("2.7.1", "2.7.1"))
    }

    @Test
    fun aNightlyRidesWithTheNewestStableOfItsFamily() {
        val items = listOf(
            item("dxvk-2.6.2-linux.wcp", ComponentKind.DXVK),
            item("dxvk-2.7.1-linux.wcp", ComponentKind.DXVK),
            item("dxvk-arm64ec-2.7.1-linux.wcp", ComponentKind.DXVK),
            item("DD-dxvk-3.1.1+168-nightly.wcp", ComponentKind.DXVK, nightly = true),
            item("DD-dxvk-gplasync-3.1.1+168-nightly.wcp", ComponentKind.DXVK, nightly = true),
            item("vkd3d-proton-2.14.1-linux.wcp", ComponentKind.VKD3D),
        )
        val rows = Catalog.rows(items, ComponentKind.DXVK, emptySet())
        val paired = rows.single { it.nightly?.file == "DD-dxvk-3.1.1+168-nightly.wcp" }
        assertEquals("dxvk-2.7.1-linux.wcp", paired.stable?.file)
        assertNull(rows.single { it.stable?.file == "dxvk-2.6.2-linux.wcp" }.nightly)
        // No gplasync stable build here: its nightly stands alone, last.
        assertEquals("DD-dxvk-gplasync-3.1.1+168-nightly.wcp", rows.last().nightly?.file)
        assertNull(rows.last().stable)
        assertEquals(4, rows.size)
    }

    @Test
    fun storedFilesAreLeftOut() {
        val items = listOf(item("dxvk-2.7.1-linux.wcp", ComponentKind.DXVK), item("dxvk-2.6.2-linux.wcp", ComponentKind.DXVK))
        assertEquals(listOf("dxvk-2.6.2-linux.wcp"), Catalog.rows(items, ComponentKind.DXVK, setOf("dxvk-2.7.1-linux.wcp")).map { it.primary.file })
    }

    @Test
    fun nightlyKindsComeFromTheirNames() {
        assertEquals(ComponentKind.DXVK, Catalog.nightlyKind("DD-dxvk-arm64ec-3.1.1+168-nightly.wcp"))
        assertEquals(ComponentKind.VKD3D, Catalog.nightlyKind("DD-vkd3d-proton-3.1-nightly.wcp"))
        assertEquals(ComponentKind.FEX, Catalog.nightlyKind("DD-FEXCore-2610+114-nightly.wcp"))
        assertNull(Catalog.nightlyKind("state.json"))
    }

    @Test
    fun driverHalvesComeFromTheirNames() {
        assertEquals(TurnipCatalog.Half.BOTH, TurnipCatalog.half("Droid-Deck/Drivers-CI", "DD-Turnip-v0.3.4.zip"))
        assertNull(TurnipCatalog.half("Droid-Deck/Drivers-CI", "DD-Turnip-v0.3.4-source.tar.xz"))
        assertEquals(TurnipCatalog.Half.LINUX, TurnipCatalog.half("The412Banner/Banners-Turnip", "Turnip-v27.0.0-20261030-r2-A8xx-Linux.zip"))
        assertEquals(TurnipCatalog.Half.ANDROID, TurnipCatalog.half("The412Banner/Banners-Turnip", "Turnip-v27.0.0-20261030-r2-A8xx.zip"))
        assertNull(TurnipCatalog.half("The412Banner/Banners-Turnip", "Turnip-v27.0.0-20261030-r2-A8xx-Wayland.zip"))
        assertEquals(TurnipCatalog.Half.LINUX, TurnipCatalog.half("WinNative-Emu/Drivers", "WN-Linux-Turnip-0.1.2-b_Axxx.zip"))
        assertEquals(TurnipCatalog.Half.ANDROID, TurnipCatalog.half("WinNative-Emu/Drivers", "WN-Turnip-1.19-b_Axxx.zip"))
    }
}
