import Foundation
import Testing
@testable import HerdrKit

// Port of android ScopedKeyTest.kt. Seen, opened and pinned are kept per
// server: the same address on two servers has two records.
@Suite struct ScopedKeyTests {
    @Test func theSameRecordOnTwoServersHasTwoKeys() {
        let a = scopedKey("posted", "https://mac.ts.net:7400")
        let b = scopedKey("posted", "http://localhost:7410")
        #expect(a != b)
        #expect(a == "posted@https://mac.ts.net:7400")
        #expect(scopedKey("seen", "x") != scopedKey("posted", "x"))
    }
}
