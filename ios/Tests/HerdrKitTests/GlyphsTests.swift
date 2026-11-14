import Foundation
import Testing
@testable import HerdrKit

// Port of android GlyphsTest.kt. The private use glyphs are written as
// escapes, so an editor cannot lose them.
@Suite struct GlyphsTests {
    @Test func labelDropsPrivateUseAndBoxDrawing() {
        // U+E0B0 (Powerline), U+F0A1E (Nerd Font, outside the BMP), U+2502 (│).
        #expect(Glyphs.label("π \u{E0B0} PANE: \u{F0A1E} omp \u{2502}") == "π PANE: omp")
        #expect(Glyphs.label(nil) == nil)
        #expect(Glyphs.label("plain text") == "plain text")
        // A spinner frame in a title: every frame gives the same label.
        #expect(Glyphs.label("π \u{283C} \u{F0379} PANE: Install") == "π PANE: Install")
        #expect(Glyphs.label("π \u{280F} PANE: Install") == Glyphs.label("π \u{283C} PANE: Install"))
    }

    @Test func labelKeepsEmojiAndOtherScripts() {
        #expect(Glyphs.label("ship it 🚀 · déjà vu ⋮") == "ship it 🚀 · déjà vu ⋮")
    }

    @Test func proseKeepsBoxDrawingInCodeBlocks() {
        let src = "Tree \u{2502} below:\n```\nsrc\n\u{251C}\u{2500}\u{2500} a.kt\n```\nDone \u{E0B0}"
        #expect(Glyphs.prose(src) == "Tree  below:\n```\nsrc\n\u{251C}\u{2500}\u{2500} a.kt\n```\nDone ")
    }

    @Test func monoKeepsBoxDrawing() {
        #expect(Glyphs.mono("\u{250C}\u{2500}\u{2510} \u{E0A0}x") == "\u{250C}\u{2500}\u{2510} x")
    }
}
