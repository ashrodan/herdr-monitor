# herdr-monitor

A small web page to watch and answer the coding agents that run in the
[Herdr](https://herdr.dev) app. It talks to herdr-core (`hc`) through its
JSON-RPC socket. It has no dependencies.

## Run it

    npm start          # or: npm run dev (restarts when a file changes)

Open <http://127.0.0.1:7400>. Node 20 or later is necessary.

| Variable    | Default                                                            |
| ----------- | ------------------------------------------------------------------ |
| `PORT`      | `7400`                                                             |
| `HOST`      | `127.0.0.1`                                                        |
| `HC_SOCKET` | `$HC_TERMINAL_SOCKET`, else `~/.local/state/herdr-core/default/default.sock` |

## What it shows

- **Agent list.** Every agent, with live status from `hc` events. Blocked
  and failed agents come first. Shells and finished terminals are hidden
  unless you select the filters.
- **Output tab.** The agent's current screen. It refreshes while the agent
  works. "Load history" reads the scrollback one time. A history read makes
  `hc` scroll a full-screen agent, so it takes about 2 seconds.
- **Commits tab.** The PR for the agent's branch (from `gh pr view`): its
  title, state, base and head, size, and commits. Commits that are not pushed
  are tagged. Click a commit to see its message and changed files. If the
  branch has no PR, the tab shows the commits since the default branch.
- **Reply box.** Enter sends text to the agent (`terminal.send`).
  "Interrupt" presses the agent's stop key.
- **Deep links.** `#t2s/commits` opens agent `t2s` on the Commits tab.

## Safety

- The server listens on 127.0.0.1 only. There is no login. For access from
  a phone, use Tailscale or an SSH tunnel. Do not expose the port to the
  internet.
- A POST must come from the page's own origin. Other websites cannot send
  text to your agents.
- The page does not answer approval dialogs. When an agent is blocked, the
  Send button is off. `terminal.send` is called without `force`.

## How it works

    browser ── SSE /api/events ──┐
            ── fetch /api/... ───┤  server.js ── 2 sockets ── hc (herdr-core)
                                 │   events.subscribe  (status, title)
                                 │   terminal.get/read/send/interrupt
                                 └─ lib/git.js ── git, gh (Commits tab)

`hc schema` prints the full `hc` API.

## Test

    npm test
