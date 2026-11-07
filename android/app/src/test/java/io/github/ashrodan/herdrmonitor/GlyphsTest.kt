package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphsTest {
    @Test fun labelDropsPrivateUseAndBoxDrawing() {
        // U+E0B0 (Powerline), U+F0A1E (Nerd Font, a surrogate pair), U+2502 (│).
        assertEquals("π PANE: omp", Glyphs.label("π  PANE: 󰨞 omp │"))
        assertEquals(null, Glyphs.label(null))
        assertEquals("plain text", Glyphs.label("plain text"))
        // A spinner frame in a title: every frame gives the same label.
        assertEquals("π PANE: Install", Glyphs.label("π ⠼ \uDB80\uDF79 PANE: Install"))
        assertEquals(Glyphs.label("π ⠼ PANE: Install"), Glyphs.label("π ⠏ PANE: Install"))
    }

    @Test fun labelKeepsEmojiAndOtherScripts() {
        assertEquals("ship it 🚀 · déjà vu ⋮", Glyphs.label("ship it 🚀 · déjà vu ⋮"))
    }

    @Test fun proseKeepsBoxDrawingInCodeBlocks() {
        val src = "Tree │ below:\n```\nsrc\n├── a.kt\n```\nDone "
        assertEquals("Tree  below:\n```\nsrc\n├── a.kt\n```\nDone ", Glyphs.prose(src))
    }

    @Test fun monoKeepsBoxDrawing() {
        assertEquals("┌─┐ x", Glyphs.mono("┌─┐ x"))
    }
}
