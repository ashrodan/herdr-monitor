package io.github.ashrodan.herdrmonitor

// Terminal glyphs that must not reach UI text: the Unicode private use
// areas (Nerd Font and Powerline icons; they draw as a tofu box) and
// box-drawing characters (pane chrome).
object Glyphs {
    private fun privateUse(cp: Int) = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD
    private fun boxDrawing(cp: Int) = cp in 0x2500..0x257F

    private fun strip(s: String, box: Boolean): String {
        if (s.none { it.isSurrogate() || privateUse(it.code) || (box && boxDrawing(it.code)) }) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!privateUse(cp) && !(box && boxDrawing(cp))) b.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return b.toString()
    }

    // A one-line label (a name, a title, a status message): both kinds go,
    // and the gaps they leave close up.
    // Braille patterns go too: TUIs spin them in titles ("⠼ ⠴ ⠦"), ten
    // times a second, and a label must not change with each frame.
    fun label(s: String?): String? = s?.let { strip(it, box = true).replace(BRAILLE, "").replace(Regex("[ \\t]{2,}"), " ").trim() }
    private val BRAILLE = Regex("[\\u2800-\\u28FF]")

    // Chat prose: private use glyphs go everywhere; box-drawing goes outside
    // ``` code blocks only (a tree or a table in a code block keeps it).
    fun prose(s: String): String {
        if (!s.contains("```")) return strip(s, box = true)
        val parts = s.split("```")
        return parts.mapIndexed { i, p -> strip(p, box = i % 2 == 0) }.joinToString("```")
    }

    // The terminal screen and tool output: JetBrains Mono draws box-drawing,
    // so only the private use glyphs go.
    fun mono(s: String): String = strip(s, box = false)
}
