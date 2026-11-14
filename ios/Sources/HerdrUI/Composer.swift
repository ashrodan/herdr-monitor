import SwiftUI
import PhotosUI
import ImageIO
import UniformTypeIdentifiers
import HerdrKit

private let MAX_IMAGES = 4
private let DIALOG_KEYS: [(key: String, label: String)] = [("up", "↑"), ("down", "↓"), ("enter", "⏎"), ("esc", "Esc"), ("tab", "Tab"), ("1", "1"), ("2", "2"), ("3", "3")]

struct Attachment: Identifiable, Equatable {
    enum State: Equatable { case uploading, ready, failed }
    let id = UUID()
    var jpeg: Data?
    var state = State.uploading
    var path: String?
    var error: String?
}

// The server takes PNG, JPEG, GIF and WebP, 8 MB at most. Photos are often
// HEIC and larger: every picked image becomes a JPEG, 2048 px at most.
func jpegForUpload(_ data: Data, maxPixel: Int = 2048) -> Data? {
    guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                 kCGImageSourceThumbnailMaxPixelSize: maxPixel]
    guard let img = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
    let out = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(dest, img, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
    return CGImageDestinationFinalize(dest) ? out as Data : nil
}

// The reply bar. A reply goes out at once when the link is up; if the link
// is down, or the send fails, it goes to the outbox and is sent later, in
// order. Either way the field clears. Interrupts and keys are live: they
// never queue. Images upload first; an image that does not upload stays
// here to try again.
struct Composer<Pills: View>: View {
    let address: String
    var online: Bool
    var blocked: Bool
    var kind: String?
    var working: Bool
    var harness: String?
    @ViewBuilder var pills: Pills

    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var note: String?
    @State private var sending = false
    @State private var attached: [Attachment] = []
    @State private var picks: [PhotosPickerItem] = []
    @State private var ok = 0
    @State private var failed = 0
    @FocusState private var focused: Bool

    private var uploading: Bool { attached.contains { $0.state == .uploading } }
    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || attached.contains { $0.path != nil } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let line = note ?? info {
                Text(line).font(.footnote)
                    .foregroundStyle(line.hasPrefix("Not") ? Color.red : (blocked && note == nil ? Color.orange : Color.secondary))
                    .accessibilityIdentifier("composer-note")
            }
            if blocked {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(DIALOG_KEYS, id: \.key) { k in
                            Button(k.label) { keys(k.key) }.buttonStyle(.bordered).controlSize(.small).monospaced()
                                .accessibilityLabel("Press \(k.key)")
                        }
                    }
                }
            }
            if !attached.isEmpty { attachmentStrip }
            pills
            HStack(alignment: .bottom, spacing: 8) {
                HStack(alignment: .bottom, spacing: 2) {
                    PhotosPicker(selection: $picks, maxSelectionCount: MAX_IMAGES, matching: .images) {
                        Image(systemName: "plus").font(.body.weight(.medium)).frame(width: 36, height: 36)
                    }
                    .disabled(attached.count >= MAX_IMAGES)
                    .accessibilityLabel("Attach an image")
                    TextField("Steer \(harnessName(harness))…", text: $text, axis: .vertical)
                        .lineLimit(1...6)
                        .focused($focused)
                        .padding(.vertical, 8)
                        .accessibilityIdentifier("composer-field")
                }
                .padding(.leading, 4).padding(.trailing, 14)
                .background(Color.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                sendButton
            }
        }
        .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 10)
        .frame(maxWidth: 784)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .sensoryFeedback(.success, trigger: ok)
        .sensoryFeedback(.error, trigger: failed)
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            picks = []
            for item in items.prefix(MAX_IMAGES - attached.count) { add(item) }
        }
        .task(id: note) {
            // A note that is not an error goes away by itself.
            guard let n = note, !n.hasPrefix("Not") else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { note = nil }
        }
    }

    private var info: String? {
        if !online { return "Offline. Replies wait in the outbox." }
        if blocked { return "Waiting at a \(kind ?? "dialog"). Tap an answer in the chat, press keys, or type here." }
        return nil
    }

    // One field and one button. Field empty while the agent runs: a red
    // Stop. Text in the field: Send. Agent idle and field empty: Send, off.
    @ViewBuilder private var sendButton: some View {
        let showStop = !hasText && online && (working || blocked)
        if showStop {
            Button(action: interrupt) {
                Image(systemName: "stop.fill").font(.body).frame(width: 40, height: 40)
                    .foregroundStyle(.white).background(Color.red, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Stop the agent")
            .accessibilityIdentifier("composer-stop")
        } else {
            let enabled = hasText && !sending && !uploading
            Button(action: send) {
                Image(systemName: "arrow.up").font(.body.weight(.semibold)).frame(width: 40, height: 40)
                    .foregroundStyle(enabled ? Color.white : Color.secondary)
                    .background(enabled ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color.card), in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            // With a keyboard on the iPad: Command-Return sends.
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel("Send")
            .accessibilityIdentifier("composer-send")
        }
    }

    private var attachmentStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(attached) { a in
                    ZStack(alignment: .topTrailing) {
                        Group {
                            if let d = a.jpeg, let img = image(from: d) { img.resizable().scaledToFill() } else { Color.card }
                        }
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay {
                            if a.state == .uploading { ProgressView() }
                            if a.state == .failed {
                                Button { retry(a) } label: { Image(systemName: "arrow.clockwise.circle.fill").font(.title2).foregroundStyle(.white, .red) }
                                    .buttonStyle(.plain).accessibilityLabel("Upload again")
                            }
                        }
                        Button { attached.removeAll { $0.id == a.id } } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.white, .black.opacity(0.6))
                        }
                        .buttonStyle(.plain).offset(x: 5, y: -5).accessibilityLabel("Remove the image")
                    }
                }
            }
            .padding(.top, 5)
        }
    }

    private func set(_ id: UUID, _ change: (inout Attachment) -> Void) {
        if let i = attached.firstIndex(where: { $0.id == id }) { change(&attached[i]) }
    }

    private func add(_ item: PhotosPickerItem) {
        let a = Attachment()
        attached.append(a)
        Task {
            guard let raw = try? await item.loadTransferable(type: Data.self), let jpeg = await Task.detached(operation: { jpegForUpload(raw) }).value else {
                set(a.id) { $0.state = .failed; $0.error = "Not an image the app can read" }
                note = "Not uploaded: the image could not be read"
                return
            }
            set(a.id) { $0.jpeg = jpeg }
            await upload(a.id, jpeg)
        }
    }

    private func retry(_ a: Attachment) {
        guard let jpeg = a.jpeg else { attached.removeAll { $0.id == a.id }; return }
        set(a.id) { $0.state = .uploading; $0.error = nil }
        Task { await upload(a.id, jpeg) }
    }

    private func upload(_ id: UUID, _ jpeg: Data) async {
        do {
            let path = try await model.api.upload(address, bytes: jpeg, type: "image/jpeg")
            set(id) { $0.state = .ready; $0.path = path }
        } catch {
            set(id) { $0.state = .failed; $0.error = describe(error) }
            note = "Not uploaded: \(describe(error))"
        }
    }

    private func send() {
        let full = ([text.trimmingCharacters(in: .whitespacesAndNewlines)] + attached.compactMap(\.path)).filter { !$0.isEmpty }.joined(separator: " ")
        guard !full.isEmpty, !sending, !uploading else { return }
        // At a dialog, force: the text goes to the dialog (an "Other" answer).
        let force = blocked, server = model.server, api = model.api
        func queued(_ why: String?) {
            model.outbox.add(server: server, address: address, text: full, force: force, error: why)
            text = ""; attached = []
            note = "Queued. It goes when the link is back."
        }
        if !online { queued("Offline"); return }
        sending = true
        Task {
            do {
                try await api.send(address, text: full, force: force)
                ok += 1; text = ""; attached = []; note = "Sent."
            } catch {
                failed += 1
                switch failureOf(error) {
                // It can pass (no link, a 5xx): it goes later.
                case .retry: queued(describe(error))
                // The server said no; the text stays here to change.
                case .refused: note = "Not sent: \(describe(error))"
                // It may have arrived: never send it again by itself.
                case .maybeSent: note = "Not confirmed: no answer from the server. Look in the chat before you send it again."
                }
            }
            sending = false
        }
    }

    private func keys(_ k: String) {
        Task { do { try await model.api.keys(address, [k]) } catch { failed += 1; note = "Not sent: \(describe(error))" } }
    }

    private func interrupt() {
        Task {
            do { try await model.api.interrupt(address); ok += 1; note = "Stop key sent." } catch { failed += 1; note = "Not sent: \(describe(error))" }
        }
    }
}
