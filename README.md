# herdr-monitor

A web app to watch and answer the coding agents that run in the
[Herdr](https://herdr.dev) app, made for a phone first. It talks to
herdr-core (`hc`) through its JSON-RPC socket. The server has no runtime
dependencies. The UI is Vite + React + TypeScript + Tailwind + shadcn/ui,
built to `dist/`.

## Run it

    npm install        # or: bun install
    npm start          # builds the UI, then starts the server
    npm run dev        # rebuilds the UI and restarts the server on change

Open <http://127.0.0.1:7400>. Node 20 or later is necessary.

| Variable            | Default                                                            |
| ------------------- | ------------------------------------------------------------------ |
| `PORT`              | `7400`                                                             |
| `HOST`              | `127.0.0.1`                                                        |
| `HC_SOCKET`         | `$HC_TERMINAL_SOCKET`, else `~/.local/state/herdr-core/default/default.sock` |
| `HERDR_APP_DIR`     | `~/.config/herdr-app` (space colours, agent store, icons; read only) |
| `HERDR_MONITOR_DIR` | `~/.config/herdr-monitor` (push keys and subscriptions, mode 600)  |
| `VAPID_SUBJECT`     | `mailto:herdr-monitor@localhost`                                   |
| `REDIRECT_PORT`     | `7480` (http → https helper for Tailscale Serve; `0` turns it off) |
| `HTTPS_PORT`        | `$PORT` (the https port the redirect points to)                    |

### On a phone

Use Tailscale Serve for https (Web Push and the service worker need it):

    tailscale serve --bg --https=7400 http://127.0.0.1:7400
    tailscale serve --bg --http=80 http://127.0.0.1:7480   # optional: http → https

Open `https://<machine>.<tailnet>.ts.net:7400`. With the second line,
`http://<machine>` and `http://<machine>.<tailnet>.ts.net` redirect there
(the server answers on 127.0.0.1:7480; `REDIRECT_PORT=0` turns it off).
`http://…:7400` cannot redirect: that port speaks only https.

To install: tap the bell. Chrome on Android and desktop gets an Install
button. On iPhone, follow the steps in the menu (Share → Add to Home Screen),
then open the app from the Home Screen. iOS sends Web Push only to a
home-screen app.

## What it shows

- **Agents.** Grouped like Herdr's sidebar: Blocked, Error, Done, Working,
  Idle, with the time since the last change. Sub-agents sit under their
  parent. "Done" turns into Idle once you open the agent here or in Herdr.
- **Spaces.** Herdr's spaces in Herdr's order, with their colour, branch
  (when it is not the default branch) and `↓N` when behind the upstream.
  Worktrees sit under their main repo.
- **Overview.** How many agents wait for you, work, or finished unseen, and
  the open PRs of the spaces' repos. Tap a number to see the list.
- **Agent.** One header with the Output | Commits | Status switch.
  - Output: the agent's screen. It refreshes while the agent works. "Load
    history" reads the scrollback (about 2 seconds: `hc` scrolls the agent).
  - Commits: the PR for the agent's branch (`gh pr view`), or the commits
    since the default branch. Tap a commit for its message and files.
  - Status: whether the PR can merge and why not, reviews and checks.
  - Reply box: on a phone, Enter adds a line and the arrow sends. On a
    desktop, Enter sends. The square button presses the agent's stop key.
- **Notifications.** The bell turns on Web Push for this device. You get
  one when an agent becomes blocked, done or fails (at most one per agent
  and state in 30 s). A tap opens the agent.
- **Deep links.** `#t2s/commits`, `#space/<id>`, `#list/waiting`.

Program icons come from your Herdr install (`~/.config/herdr-app/icons`),
not from this repo.

## Safety

- The server listens on 127.0.0.1 only. There is no login. For access from
  a phone, use Tailscale Serve (your tailnet only). Do not expose the port
  to the internet: do not use Tailscale Funnel.
- A POST must come from the page's own origin. Other websites cannot send
  text to your agents.
- The page can answer dialogs (questions and permission prompts). While an
  agent is blocked, a key pad sends navigation keys only (arrows, Tab, Space,
  Enter, Esc, Backspace, single letters and digits; no Ctrl or Alt), and Send
  types into the dialog with `force`. Otherwise `terminal.send` is called
  without `force`, so hc refuses to type into an agent that just became
  blocked.

## How it works

    browser ── SSE /api/events ──┐
            ── fetch /api/... ───┤  server.js ── 2 sockets ── hc (herdr-core)
                                 │   events.subscribe  (status, title, doc)
                                 │   doc.get herdr-app (spaces and tabs)
                                 │   terminal.get/read/send/interrupt
                                 ├─ lib/sidebar.js ── git (repo, worktree, branch)
                                 ├─ lib/git.js, lib/pr.js ── git, gh
                                 └─ lib/push.js ── Web Push (VAPID, aes128gcm)

    web/ (React) ── vite build ──> dist/ (served by server.js)

`hc schema` prints the full `hc` API.

## Test

    npm test           # server and library tests (node:test)
    npm run typecheck  # the UI
