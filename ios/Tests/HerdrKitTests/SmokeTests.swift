import Foundation
import Testing
@testable import HerdrKit

@Test func demoSnapshotDecodes() {
    let o = DemoTransport(now: 1_800_000_000_000).snapshot
    let terminals = Decode.terminals(o.objs("terminals"))
    let spaces = Decode.spaces(o.objs("spaces"))
    #expect(terminals.count == 7)
    #expect(terminals["t1"]?.status == "blocked")
    #expect(terminals["t7"]?.isApp == true)
    #expect(spaces.map(\.id) == ["sp-shop", "sp-shop-wt", "sp-api", "sp-docs"])
    #expect(spaces[0].tabs[0].panes.map(\.terminal) == ["t1", "t6"])
    #expect(rollup(spaces[0], spaces, terminals) == SpaceRollup(state: "blocked", worktrees: 1, behind: 2))
}

@MainActor @Test func storeGoesOnlineFromTheStream() async throws {
    let store = Store(api: Api(DemoTransport.server, transport: DemoTransport()))
    store.start()
    for _ in 0..<200 where store.live.link != .online { try await Task.sleep(nanoseconds: 10_000_000) }
    #expect(store.live.link == .online)
    #expect(store.live.terminals.count == 7)
    for _ in 0..<200 where !store.live.prsLoaded { try await Task.sleep(nanoseconds: 10_000_000) }
    #expect(store.live.prs.count == 2)
    store.stop()
}
