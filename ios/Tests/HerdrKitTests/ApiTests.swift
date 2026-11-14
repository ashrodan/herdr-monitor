import Foundation
import Testing
@testable import HerdrKit

// Port of android ApiTest.kt.
@Suite struct ApiTests {
    @Test func normalizeServerUrls() {
        #expect(normalizeServer("box.ts.net:7400") == "https://box.ts.net:7400")
        #expect(normalizeServer(" https://box.ts.net:7400/ ") == "https://box.ts.net:7400")
        #expect(normalizeServer("http://10.0.2.2:7410") == "http://10.0.2.2:7410")
        #expect(normalizeServer("") == nil)
        #expect(normalizeServer("ftp://box") == nil)
    }

    // The server's sameOrigin check: new URL(origin).host === Host header.
    @Test func originMatchesWhatTheHostHeaderWillBe() {
        #expect(originOf("https://box.ts.net:7400") == "https://box.ts.net:7400")
        #expect(originOf("https://box.ts.net:443") == "https://box.ts.net")
        #expect(originOf("https://box.ts.net") == "https://box.ts.net")
        #expect(originOf("http://10.0.2.2:7410") == "http://10.0.2.2:7410")
    }

    @Test func shortHostAndPort() {
        #expect(shortHost("https://ashleys-macbook-pro-2.tail5265d4.ts.net:7400") == "ashleys-macbook-pro-2")
        #expect(shortHost("http://10.0.2.2:7410") == "10.0.2.2")
        #expect(portOf("https://box.ts.net:7400") == 7400)
        #expect(portOf("https://box.ts.net") == 443)
    }

    @Test func appUrlUsesTheMachineTailnetNameNeverLocalhost() {
        let local = Terminal(address: "t8t", listen: Listen(scheme: "http", port: 5223, host: nil))
        #expect(appUrl(local, serverUrl: "https://box.ts.net:7400") == "http://box.ts.net:5223")
        #expect(appUrl(Terminal(address: "dai/t1", listen: Listen(scheme: "https", port: 8443, host: "herdr-1.ts.net")), serverUrl: "https://box.ts.net:7400")
            == "https://herdr-1.ts.net:8443")
        #expect(appUrl(Terminal(address: "dai/t2", listen: Listen(scheme: "http", port: 3000, host: nil)), serverUrl: "https://box.ts.net:7400") == nil)
        #expect(appUrl(Terminal(address: "t1"), serverUrl: "https://box.ts.net:7400") == nil)
    }

    @Test func pathPartEncodes() {
        #expect(pathPart("t1x") == "t1x")
        #expect(pathPart("a b/c") == "a%20b%2Fc")
    }
}
