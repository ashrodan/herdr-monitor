import SwiftUI
import HerdrKit

// Agent prose, drawn from HerdrKit's Markdown blocks. The blocks are parsed
// once per text (the parent keeps the view's identity stable).
struct MarkdownView: View {
    private let blocks: [Block]

    init(_ text: String, keepBreaks: Bool = false) { blocks = Markdown.blocks(text, keepBreaks: keepBreaks) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in block(b) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func block(_ b: Block) -> some View {
        switch b {
        case .heading(let level, let text):
            Text(inline(text)).font(level <= 1 ? .title2.bold() : level == 2 ? .title3.bold() : .headline).padding(.top, 4)
        case .para(let text):
            Text(inline(text))
        case .code(_, let text):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(text).font(.system(.footnote, design: .monospaced)).padding(10)
            }
            .background(Color.codeBackground, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        case .items(let ordered, let start, let items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(ordered ? "\(start + i)." : "•").foregroundStyle(.secondary).monospacedDigit()
                        Text(inline(item))
                    }
                }
            }
        case .table(let header, let rows):
            ScrollView(.horizontal, showsIndicators: false) {
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                    GridRow { ForEach(Array(header.enumerated()), id: \.offset) { _, c in Text(inline(c)).fontWeight(.semibold) } }
                    Divider()
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                        GridRow { ForEach(Array(r.enumerated()), id: \.offset) { _, c in Text(inline(c)) } }
                    }
                }
                .font(.subheadline)
                .padding(10)
            }
            .background(Color.card, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        case .quote(let text):
            Text(inline(text)).foregroundStyle(.secondary).padding(.leading, 10)
                .overlay(alignment: .leading) { Rectangle().fill(.tertiary).frame(width: 3) }
        case .rule:
            Divider()
        }
    }

    private func inline(_ text: String) -> AttributedString {
        var out = AttributedString()
        for s in Markdown.spans(text) {
            var a = AttributedString(s.text)
            var intent: InlinePresentationIntent = []
            if s.bold { intent.insert(.stronglyEmphasized) }
            if s.italic { intent.insert(.emphasized) }
            if s.code { intent.insert(.code); a.backgroundColor = Color.codeBackground }
            if !intent.isEmpty { a.inlinePresentationIntent = intent }
            if let u = s.url, let url = URL(string: u) { a.link = url }
            out += a
        }
        return out
    }
}
