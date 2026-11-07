package io.github.ashrodan.herdrmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun blocksInOrder() {
        val md = """
            ## Plan

            First line
            goes on.

            - one
            - two
              more
            1. a
            2. b

            ```kotlin
            val x = 1
            ```

            | Name | Port |
            | --- | ---: |
            | vite | 5173 |

            > quoted
            ---
        """.trimIndent()
        assertEquals(listOf(
            Block.Heading(2, "Plan"),
            Block.Para("First line goes on."),
            Block.Items(false, 1, listOf("one", "two more")),
            Block.Items(true, 1, listOf("a", "b")),
            Block.Code("kotlin", "val x = 1"),
            Block.Table(listOf("Name", "Port"), listOf(listOf("vite", "5173"))),
            Block.Quote("quoted"),
            Block.Rule,
        ), Markdown.blocks(md))
    }

    @Test fun keepBreaksForYourOwnPrompts() {
        assertEquals(listOf(Block.Para("a\nb")), Markdown.blocks("a\nb", keepBreaks = true))
        assertEquals(listOf(Block.Para("a b")), Markdown.blocks("a\nb"))
    }

    @Test fun unclosedFenceTakesTheRest() {
        assertEquals(listOf(Block.Para("x"), Block.Code("", "code\nmore")), Markdown.blocks("x\n```\ncode\nmore"))
    }

    @Test fun inlineSpans() {
        assertEquals(listOf(Span("a "), Span("bold", bold = true), Span(" and "), Span("x()", code = true), Span(" "), Span("it", italic = true)),
            Markdown.spans("a **bold** and `x()` *it*"))
        assertEquals(listOf(Span("see "), Span("docs", url = "https://x.dev/a"), Span(".")), Markdown.spans("see [docs](https://x.dev/a)."))
        assertEquals(listOf(Span("at "), Span("https://x.dev/a", url = "https://x.dev/a"), Span(".")), Markdown.spans("at https://x.dev/a."))
        assertEquals(listOf(Span("snake_case_name and 2*3*4")), Markdown.spans("snake_case_name and 2*3*4"))
        assertEquals(listOf(Span("in "), Span("bold ", bold = true), Span("code", bold = true, code = true)), Markdown.spans("in **bold `code`**"))
    }
}
