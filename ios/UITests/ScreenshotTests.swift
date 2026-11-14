import XCTest

// The app on an iPad simulator, with the demo server. Each test checks one
// flow and keeps screenshots; CI uploads them (the "ios-screenshots"
// artifact), so every push shows what the iPad shows.
final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ extra: [String] = [], orientation: UIDeviceOrientation = .landscapeLeft) {
        XCUIDevice.shared.orientation = orientation
        app.launchArguments = ["-demo"] + extra
        app.launch()
    }

    private func shot(_ name: String) {
        let a = XCTAttachment(image: upright(XCUIScreen.main.screenshot().image))
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    // The screen's picture is always portrait. In landscape (these tests use
    // landscapeLeft) the top of the interface is at the picture's right
    // edge: turn it a quarter turn counter-clockwise.
    private func upright(_ image: UIImage) -> UIImage {
        guard XCUIDevice.shared.orientation.isLandscape, image.size.width < image.size.height else { return image }
        let size = CGSize(width: image.size.height, height: image.size.width)
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: size, format: format).image { c in
            c.cgContext.translateBy(x: 0, y: size.height)
            c.cgContext.rotate(by: -.pi / 2)
            image.draw(at: .zero)
        }
    }

    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

    func testInboxAndAnswerAQuestion() {
        launch()
        XCTAssertTrue(element("session-t1").waitForExistence(timeout: 20), "the Needs you session shows")
        XCTAssertTrue(app.staticTexts["Needs you"].firstMatch.exists)
        // The card of a waiting session gets its answers from the transcript.
        XCTAssertTrue(element("answer-t1-0").waitForExistence(timeout: 15), "the quick answers show on the card")
        shot("01-inbox-landscape")

        // Tap the name: the middle of this card is an answer button.
        app.staticTexts["shop · checkout"].firstMatch.tap()
        XCTAssertTrue(element("option-0-1").waitForExistence(timeout: 15), "the question shows in the chat")
        shot("02-question-landscape")

        element("option-0-1").tap()
        XCTAssertTrue(app.staticTexts["Answer sent."].waitForExistence(timeout: 10))
        shot("03-answered-landscape")
    }

    func testWorkingSessionPortrait() {
        launch(["-demo-select", "t2"], orientation: .portrait)
        XCTAssertTrue(element("live-step").waitForExistence(timeout: 20), "the running tool shows")
        shot("04-working-portrait")

        element("pill-commits").tap()
        XCTAssertTrue(app.staticTexts["Cart: apply the discount before tax"].waitForExistence(timeout: 10))
        shot("05-commits-portrait")
        app.buttons["Done"].firstMatch.tap()

        element("space-map").tap()
        XCTAssertTrue(app.staticTexts["Space map"].waitForExistence(timeout: 10))
        shot("06-space-map-portrait")
    }

    func testReplyIsSent() {
        launch(["-demo-select", "t3"])
        let field = element("composer-field")
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        field.tap()
        field.typeText("Thanks. Open the PR.")
        shot("07-reply-typed")
        element("composer-send").tap()
        XCTAssertTrue(app.staticTexts["Sent."].waitForExistence(timeout: 10))
    }

    func testSpacesFoldAndSettings() {
        launch()
        XCTAssertTrue(element("session-t1").waitForExistence(timeout: 20))
        app.buttons["Spaces"].firstMatch.tap()
        XCTAssertTrue(element("fold-sp-shop").waitForExistence(timeout: 10), "a space with a worktree can fold")
        XCTAssertTrue(element("terminal-t2").exists, "the worktree's session shows while open")
        shot("08-spaces-open")
        element("fold-sp-shop").tap()
        XCTAssertFalse(element("terminal-t2").waitForExistence(timeout: 2), "folded: the worktree's session is hidden")
        shot("09-spaces-folded")

        element("settings").tap()
        // The row reads as one element: "State, Connected".
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Connected'")).firstMatch.waitForExistence(timeout: 10), "the link state shows")
        shot("10-settings")
        element("settings-done").tap()

        element("new-session").tap()
        XCTAssertTrue(element("task-field").waitForExistence(timeout: 10))
        shot("11-new-session")
    }
}
