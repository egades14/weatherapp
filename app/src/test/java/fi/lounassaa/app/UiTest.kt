package fi.lounassaa.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTest {
    private fun ui(w: Float, h: Float) = Ui(2f, w, h)

    @Test
    fun a11PlusIsReference() {
        val u = ui(1280f, 800f)
        assertEquals(1f, u.scale, 0.001f)
        assertEquals(LayoutMode.WIDE, u.mode)
    }

    @Test
    fun largerTabletScalesUpKeepingLayout() {
        val u = ui(1480f, 924f)
        assertEquals(1.155f, u.scale, 0.01f)
        assertEquals(LayoutMode.WIDE, u.mode)
    }

    @Test
    fun fourByThreeKeepsSidePanelAndGivesExtraHeightToChart() {
        val u = ui(1024f, 768f)
        assertEquals(0.8f, u.scale, 0.001f)
        assertEquals(LayoutMode.WIDE, u.mode)
        assertEquals(960f, u.refHeight, 0.5f)
    }

    @Test
    fun smallerFiveByThreeTablet() {
        val u = ui(1340f / 1.5f * 1.5f, 800f)
        assertEquals(LayoutMode.WIDE, u.mode)
    }

    @Test
    fun portraitAndSplitScreen() {
        assertEquals(LayoutMode.STACKED, ui(800f, 1280f).mode)
        assertEquals(LayoutMode.STACKED, ui(640f, 800f).mode)
        assertEquals(LayoutMode.COMPACT, ui(1280f, 390f).mode)
    }
}
