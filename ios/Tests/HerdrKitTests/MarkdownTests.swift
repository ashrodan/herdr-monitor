import Foundation
import Testing
@testable import HerdrKit

// Port of android MarkdownTest.kt.
@Suite struct MarkdownTests {
    @Test func blocksInOrder() {
        let md = """
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
            """
        #expect(Markdown.blocks(md) == [
            .heading(level: 2, text: "Plan"),
            .para("First line goes on."),
            .items(ordered: false, start: 1, items: ["one", "two more"]),
            .items(ordered: true, start: 1, items: ["a", "b"]),
            .code(lang: "kotlin", text: "val x = 1"),
            .table(header: ["Name", "Port"], rows: [["vite", "5173"]]),
            .quote("quoted"),
            .rule,
        ])
    }

    @Test func keepBreaksForYourOwnPrompts() {
        #expect(Markdown.blocks("a\nb", keepBreaks: true) == [.para("a\nb")])
        #expect(Markdown.blocks("a\nb") == [.para("a b")])
    }

    @Test func unclosedFenceTakesTheRest() {
        #expect(Markdown.blocks("x\n```\ncode\nmore") == [.para("x"), .code(lang: "", text: "code\nmore")])
    }

    @Test func inlineSpans() {
        #expect(Markdown.spans("a **bold** and `x()` *it*") ==
            [Span("a "), Span("bold", bold: true), Span(" and "), Span("x()", code: true), Span(" "), Span("it", italic: true)])
        #expect(Markdown.spans("see [docs](https://x.dev/a).") == [Span("see "), Span("docs", url: "https://x.dev/a"), Span(".")])
        #expect(Markdown.spans("at https://x.dev/a.") == [Span("at "), Span("https://x.dev/a", url: "https://x.dev/a"), Span(".")])
        #expect(Markdown.spans("snake_case_name and 2*3*4") == [Span("snake_case_name and 2*3*4")])
        #expect(Markdown.spans("in **bold `code`**") == [Span("in "), Span("bold ", bold: true), Span("code", bold: true, code: true)])
    }
}
