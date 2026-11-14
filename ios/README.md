# Herdr Monitor for iPad

A native app (Swift, SwiftUI) for the iPad mini first. It talks to the
herdr-monitor server over the same HTTP API as the web UI and the Android
app. It does not show the web UI in a web view.

## Goal

The functions of the Android app, with the feel of an iPad app:

- The session list and the open session show side by side
  (`NavigationSplitView`). On a narrow width (Slide Over, an iPhone) the
  list comes first and a session opens on top of it.
- System controls, system colours, Dynamic Type, dark mode, VoiceOver
  labels, a hardware keyboard (Command-N: new session, Command-Return:
  send).
- It stays correct when the link drops: cached sessions at a cold start, a
  reply queue, a reconnect when the app comes back to the front.

## Why Swift, not shared Kotlin

Kotlin Multiplatform could share the rules (about 2,000 lines) but not the
screens. Compose on iOS does not feel native, so the screens must be
SwiftUI in both cases. To share the rules, the Android module must first be
split (it uses `org.json`, `HttpURLConnection` and Android types), and the
iOS build then needs Gradle and Xcode together. That cost is larger than a
port of the rules, and the port is checked by the same test cases. The
Kotlin tests were ported with the code; the server's JSON is the contract
between the two apps.

## Layout

    Package.swift          HerdrKit + HerdrUI as a Swift package
    Sources/HerdrKit       models, API client, SSE store, sessions, transcript,
                           Markdown, outbox, cache, the demo server (no UI)
    Sources/HerdrUI        every screen
    Sources/Snapshots      a Mac tool: renders screens to PNG without Xcode
    Tests/HerdrKitTests    unit tests (Swift Testing)
    App/                   the app target: a thin shell over HerdrUI
    UITests/               XCUITest flows on an iPad simulator, with screenshots
    project.yml            the Xcode project, generated with XcodeGen

All the code is in the package, so `swift build` on a Mac checks the
screens too (they compile for macOS; the few iOS-only calls are behind
`#if os(iOS)`).

## What is in

| Area | State |
| --- | --- |
| Sessions inbox: Needs you, Working, Done, Ready to review, Idle (folded); filter chips with counts; search; pin; unread; "Quiet for" | done |
| Answer buttons on a waiting card (one single-choice question) | done |
| Pull requests list; Spaces list with agents and app servers | done |
| Spaces: fold a space's worktrees; the folded row rolls up state, count and commits behind | done (new here) |
| Chat: prose as Markdown, folded activity cards, tool output, the live step, question cards with Submit | done |
| Reply: send, dialog keys, Stop, attach images (converted to JPEG), offline queue with retry and delete | done |
| PR, Commits, Status pills and panels; Raw terminal; sibling shells; share; copy branch | done |
| Space map with panes; New tab; worktrees with no space | done |
| New session (space, harness, task) | done |
| Settings: server, Find on tailnet, recent servers, link state, look | done |
| Fast start from the cache (sessions, PRs, last words) | done |
| Alerts: a local notification when an agent needs you | only while the app runs (see below) |

## What is not in yet

1. **Alerts while the app is closed.** iOS stops the network link of an app
   in the background, so the Android design (one link held open all day)
   cannot work. The fix is Apple push (APNs) from the server. It needs a
   paid Apple Developer account and a push key, and a small addition to the
   server. Until then, the web app installed on the Home Screen still gets
   Web Push.
2. **Widget and Control Center controls** (the Android widget and tiles).
   These are separate extension targets (WidgetKit). They also need push to
   stay current.
3. **Answer buttons on a notification.** They come with item 1.
4. **Machines list and remote chat** (Android PR #14, still a draft).
5. **The collie brand art.** The icon is the web app's icon for now.
6. **Paste an image** into the reply box (attach from Photos works).

## The loops

There are three, from fast to real:

1. **Unit tests, on any Mac, no Xcode** (seconds):

       ./test.sh

2. **Pictures of the screens, on any Mac, no Xcode** (seconds). macOS draws
   lists and bars its own way, so this checks layout and data, not the
   iPad's look:

       swift run Snapshots shots

3. **The real app on an iPad mini simulator** (`.github/workflows/ios.yml`,
   about 10 minutes on a GitHub macOS runner): unit tests, a build, and the
   UI tests in `UITests/` against the demo server. The screenshots are in
   the run's `ios-screenshots` artifact:

       gh run download <run-id> -n ios-screenshots

The demo server (`HerdrKit/Demo.swift`, launch argument `-demo`) answers
with the same JSON as the real server, so the path from the bytes to the
screen is the real one.

## Build it and put it on your iPad

You need Xcode 16 or later (from the App Store) and an Apple ID.

    brew install xcodegen
    cd ios && xcodegen generate
    open HerdrMonitor.xcodeproj

1. In Xcode: the **HerdrMonitor** target → **Signing & Capabilities** →
   pick your team. A free Apple ID works; the app then stops after 7 days
   and you install it again. A paid account gives one year and TestFlight.
2. Connect the iPad (cable, or the same Wi-Fi), pick it as the run
   destination, and press Run.
3. On the iPad, the first time: **Settings → General → VPN & Device
   Management** → trust your developer certificate. On iPadOS 16 or later,
   also turn on **Settings → Privacy & Security → Developer Mode**.
4. Open the app and type the server address, for example
   `https://<machine>.<tailnet>.ts.net:7400`. The iPad must be on the same
   tailnet. The app sends an `Origin` header equal to the server URL, so
   the server's same-origin check applies to it unchanged.

To try it with no server, add `-demo` to the scheme's launch arguments.

To run the UI tests yourself:

    xcodebuild test -project HerdrMonitor.xcodeproj -scheme HerdrMonitor \
      -destination 'platform=iOS Simulator,name=iPad mini (A17 Pro)'
