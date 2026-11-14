import Foundation
import Testing
@testable import HerdrKit

// Port of android CacheTest.kt.
@Suite struct CacheTests {
    private final class Clock: @unchecked Sendable { var now: Int64 = 1_000 }

    private func dir() throws -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("cache-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    @Test func writesAndReadsPerServer() throws {
        let clock = Clock()
        let c = Cache(dir: try dir()) { clock.now }
        c.write("http://10.0.2.2:7410", Cache.snapshot, "{\"a\":1}\nsecond line")
        let got = c.read("http://10.0.2.2:7410", Cache.snapshot)
        #expect(got?.at == 1_000)
        #expect(got?.text == "{\"a\":1}\nsecond line")
        #expect(c.read("https://other:7400", Cache.snapshot) == nil)
        clock.now += Cache.maxAgeMs + 1
        #expect(c.read("http://10.0.2.2:7410", Cache.snapshot) == nil, "older than a day")
    }

    @Test func slotIsAFolderName() throws {
        let c = Cache(dir: try dir())
        #expect(c.slot("https://Host.tail.ts.net:7400") == "https_host_tail_ts_net_7400")
        #expect(c.slot("://") == "default")
    }

    @Test func missingOrBrokenFileIsNull() throws {
        let d = try dir()
        let c = Cache(dir: d)
        #expect(c.read("http://x", Cache.overview) == nil)
        let folder = d.appendingPathComponent(c.slot("http://x"), isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("not a time\n{}".utf8).write(to: folder.appendingPathComponent("overview.txt"))
        #expect(c.read("http://x", Cache.overview) == nil)
    }

    @MainActor @Test func chatCacheDropsLeastRecentlyUsed() {
        let c = ChatCache(2)
        let a = Chat(available: true, version: "1")
        let b = Chat(available: true, version: "2")
        let d = Chat(available: true, version: "3")
        c.put("s", "a", a); c.put("s", "b", b)
        #expect(c.get("s", "a") == a)
        c.put("s", "d", d)
        #expect(c.get("s", "b") == nil, "b was used least recently")
        #expect(c.get("s", "a") == a)
        #expect(c.get("s", "d") == d)
        #expect(c.get("t", "a") == nil, "another server")
    }
}
