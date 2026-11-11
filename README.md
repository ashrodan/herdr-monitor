# herdr-monitor

A web app to watch and answer the coding agents that run in the
[Herdr](https://herdr.dev) app, made for a phone first. It talks to
herdr-core (`hc`) through its JSON-RPC socket. The server has no runtime
dependencies. The UI is Vite + React + TypeScript + Tailwind + shadcn/ui,
built to `dist/`.

![Answering an agent's questions from a phone](docs/demo.gif)

- **Agents by state**: waiting, working and done, across every Herdr space.
  You can also group them by space, or nest them under the agent that
  started them.
- **Chat**: Claude Code, omp and pi conversations, rendered for a phone.
- **Answer questions**: tap the options of an agent's question form.
- **Reply**: type, paste or attach an image.
- **Push notifications** when an agent needs you (installed as a PWA).
- **Overview**: every open PR in your spaces, with checks and reviews.

## Run it

    npm install        # or: bun install
    npm start          # builds the UI, then starts the server
    npm run dev        # rebuilds the UI and restarts the server on change

Open <http://127.0.0.1:7400>. Node 20 or later is necessary. Bun (tested with
1.4.3) also runs it: `npm run serve:bun`. The server uses only `node:` APIs,
and the tests run on both runtimes. Under Bun the busy routes answer about
1.4× faster (`/api/terminals`) to 2× faster (a gzipped transcript); Node uses
less memory. Either one is fine for a few phones and browsers.

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

### Keep it running

`scripts/service.js` makes the server a launchd agent: it starts at login,
and launchd starts it again if it stops.

    npm run build
    node scripts/service.js print            # show the plist; changes nothing
    node scripts/service.js install [--bun]  # write it and start the agent
    node scripts/service.js status
    node scripts/service.js uninstall

Run `install` from the checkout you want to serve, with the same `PORT`,
`HOST` and other settings you start the server with: it copies them and your
`PATH` into the plist. It stops if something already listens on the port:
stop the server you started by hand first. The log is
`~/Library/Logs/herdr-monitor/server.log`. The server stops cleanly on
SIGTERM: it ends every stream, so clients reconnect at once.

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

## Android app

A native Android app (Kotlin, Jetpack Compose) in [`android/`](android/). It
talks to this server over its HTTP API. It does not show the web UI in a
WebView.

- **Home**: an inbox of sessions (agents), in sections: **Needs you**
  (waiting at a question, or failed), **Working**, **Done**, **Ready to
  review**, and one collapsed **Idle** row. Filter chips above the list show
  the count of each section. A waiting card shows the question and up to three answer
  buttons. Each card shows the space colour, the status, the name, the task
  and the agent's last sentences. A session with no output for 30 minutes
  shows how long it has been quiet. **Ask Herdr** filters the list on the
  phone; it sends nothing. Long-press a card to pin it, stop it, open its
  terminal text or copy its branch. The bottom bar has **Sessions**, **PRs**
  and **Spaces**. The avatar at the top right shows the server and its link
  state.
- **New session**: the button on Home. Pick a space and a harness (Claude
  Code or omp) and type the task. The server starts the agent in that
  space's folder (`POST /api/sessions`) and the app opens it.
- **Spaces**: opening a space shows its agent sessions in Herdr order. Swipe
  left or right to move between them. App servers and raw shells are not
  swipe pages.
- **Agent**: Chat is the primary surface. PR, commits, status and running
  app-server pills sit above the reply box. **Session options** holds the
  raw terminal, sibling shells, stop, branch copy and share actions.
- **Fast start**: the app keeps the last sessions, PRs and last words it
  read from each server (in its cache folder, at most a day old). A cold
  start shows them at once, dimmed, with "Showing sessions from 9:31" until
  the live list is in. With nothing cached, Home shows placeholder cards.
  The last eight transcripts stay in memory, so an agent you opened before
  shows at once.
- **Offline replies**: a reply that you send with no link to the server goes
  into a queue on the phone. It shows in Chat as "queued". The app sends it
  when the link comes back, in order, per agent. You can delete a queued
  reply before it goes.
- **Look**: Material 3 Expressive, dark by default. Settings has Dark, Light
  or System, and an option for wallpaper colours.
- **Brand**: a border collie (the orchestrator) and sheep (the agents). The
  launcher icon is an adaptive icon with a monochrome layer (themed icons).
  The splash shows the collie on wheat, or on pine in dark mode. The
  notification and tile icons are the white collie head. Sessions and Spaces
  have sheep and paddock glyphs, and empty and offline Home show a scene.
- **Notifications**: one ongoing **Agent summary**, and a notification of
  its own only for an agent that needs you. Three channels:
  - **Agent summary** (low, silent): the one routine notification. It is
    also the notification Android needs while the app keeps its link. A
    title such as "1 needs you · 4 working · 2 done", then up to five
    agents (Needs you, Failed, Working, Done) and "+N more". Done shows
    here only, and only until you see that agent on the Mac or the phone.
    While the link is down, the title says why. A tap opens Home. If you
    swipe it away, it comes back only when what it shows changes.
  - **Needs you** (high): one per agent that asks a question or stopped
    with an error, as its own card. Up to two answer buttons,
    and Reply is always there. It goes away when the agent moves on, for
    example when you answer it on the Mac.
  - **Alert health** (default): only when alerts cannot work: the server is
    too old (it has no `/api/agents/stream`), the server refused the link
    (HTTP 401 or 403), the link was down for 5 minutes or more, or Android
    did not start the alert service. **Open alert setup** and **Retry**. It
    goes away when the link works again.
  Working and Done agents have no notification of their own, and there is
  no per-session progress notification (a pin only orders the list in the
  app). Each Needs you notification shows its space, its harness and the
  server under the title, when the server sends them. An answer from a
  notification shows as "Sent" on the Home card.
- **Alerts screen** (**Alerts** in the drawer): the link's state
  (Connected, Connecting, old server, refused, or offline), the server, the
  last event, the agent counts, background alerts and Quiet agents,
  whether notifications are allowed, the battery mode, and each channel's
  setting (a tap opens that channel in Android's settings). **Send test
  alert** posts a local Needs you notification; it contacts no agent.
  **Retry connection** opens the link again; it does not restart the
  server.
- **Quick settings tiles**: **Herdr** shows "2 need you" (or "4 working");
  a tap opens Sessions filtered to Needs you. **Quiet agents**: when on,
  the summary says only "Quiet agents · N need you", with no Working or
  Done lines. Needs you and Alert health still notify.
- **Widget** (4x2): the server name, three counts (Needs you, Working,
  PRs) and the top Needs you session with its first answer button. It
  changes when the server sends a change, not on a timer.
- **Space map**: in a session, the button left of the menu shows where you
  are: the panes of your tab in miniature, with your pane filled, and one
  dot per tab, with yours filled. A tap opens the map of the space and its
  worktrees. Each tab shows its panes where Herdr draws them, with the
  harness mark, the name and the state of each pane. A tap on a pane opens
  that session. **New tab** starts an agent in that space, in a new tab at
  the end of the space in the Herdr app. Git worktrees of the repo that
  have no space in Herdr are listed under the spaces.
- **Harness marks**: Claude, Codex, omp and pi show their own marks on the
  cards, in the rows and in the chat. Sources: Claude from Simple Icons
  (CC0), Codex from lobe-icons (MIT), pi from pi.dev, omp from omp.sh.
  Each mark belongs to its owner; the app uses it only to show which
  harness runs in a session.
- **Replies**: a reply leaves the field at the tap and shows in the chat as
  "Sending…" until the transcript has it. Replies go in order, one at a
  time (the server takes about 1 s for each). A reply typed while the agent
  works goes at once, and the harness keeps it in its own queue. A reply for
  the prompt waits while the agent is at a dialog. A reply that failed stays
  as a bubble with **Send again** and delete.
- **Dots under the title**: one for each session a swipe reaches, the one on
  screen filled. No dots: no swipe there.
- **Machines** (at the end of the Spaces tab, and in the server list): every
  machine of your tailnet, online or not. A machine that the server reaches
  through `hc` (`hc machine ls`) shows its terminals. A tap on the machine
  opens its agent sessions, a swipe apart; a tap on a terminal opens that one,
  through the server in use. That machine needs no herdr-monitor. A machine
  with its own herdr-monitor has **Use**. The others show as online or
  offline only.
- **Settings**: the server URL, **Find on tailnet** (lists the herdr-monitor
  servers on your tailnet, with each one's Herdr app, Herdr TUI and hc
  versions), the background-alerts switch, Quiet agents and the look.

The phone must be on Tailscale, on the same tailnet as the Mac. No Google
account, Firebase or push service is used: while alerts are on, the app keeps
one link to the server (`GET /api/agents/stream`) and shows the silent agent
summary. Each agent on the stream has its address,
name, state, status kind and message, its change times, and its context:
`harness`, `space_id`, `space_name` and `branch`. A change to any of these,
except the title, sends an event.

### Build and install

The app needs Android 12 or later. To build it, you need the Android SDK
(`ANDROID_HOME`) and JDK 21 or later (the screenshot tests need 21; the app's
bytecode is Java 17). Android Studio is not needed.

    cd android
    ./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew test lint             # unit tests, screenshot tests and lint
    ./gradlew verifyPaparazziDebug  # compare the screenshots with the records
    ./gradlew recordPaparazziDebug  # after a deliberate UI change: new records
    ./gradlew connectedDebugAndroidTest   # UI tests (an emulator or a phone)
    ./gradlew installPerf           # release code (R8), debug key: to measure speed

Install it with a USB cable (USB debugging on):

    adb install -r app/build/outputs/apk/debug/app-debug.apk

Or send the APK to the phone (for example with Google Drive or a chat app),
open it, and allow the install. The debug build is signed with the debug
key. The release build is shrunk with R8 (about 4 MB instead of 60 MB for
debug). A debug build draws several times slower, so measure start-up and
frames on the `perf` build.

### Release signing

The release build is for sideloading only (not Google Play). Its key stays on
your Mac, outside the repo: `~/.config/herdr-monitor/android-release.jks`.
Never commit a keystore or its password (`*.jks`, `*.keystore` and
`keystore.properties` are in `android/.gitignore`).

1. Make the keystore once. `keytool` asks for a password; keep it in your
   password manager. If you lose the key, phones that have the app must
   uninstall it before they can install a build signed with a new key.

       mkdir -p ~/.config/herdr-monitor
       keytool -genkeypair -keystore ~/.config/herdr-monitor/android-release.jks \
         -alias herdr -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Herdr Monitor"
       chmod 600 ~/.config/herdr-monitor/android-release.jks

2. Give Gradle the password, in `~/.gradle/gradle.properties` (not the repo's
   file) or as an environment variable of the same name:

       HERDR_RELEASE_STORE_PASSWORD=...

3. Build and install:

       ./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
       adb install -r app/build/outputs/apk/release/app-release.apk

| Setting | Default |
| --- | --- |
| `HERDR_RELEASE_STORE_FILE` | `~/.config/herdr-monitor/android-release.jks` |
| `HERDR_RELEASE_STORE_PASSWORD` | none: without it the release APK is unsigned (`app-release-unsigned.apk`) |
| `HERDR_RELEASE_KEY_ALIAS` | `herdr` |
| `HERDR_RELEASE_KEY_PASSWORD` | the store password |

A phone that has the debug build must uninstall it before it installs the
release build, because the two builds are signed with different keys.

### Set it up

1. Open the app and allow notifications.
2. In Settings (the gear), type the server URL, for example
   `https://<machine>.<tailnet>.ts.net:7400`, or tap **Find on tailnet** and
   pick a server. The app finds servers through a server it can reach,
   because an Android app cannot read Tailscale's list of machines.
3. For alerts on time when the phone sleeps, tap **Run unrestricted**.
4. Open **Alerts** in the drawer. It must say **Connected**. Tap **Send test
   alert** to see a Needs you notification on this phone.

### Notification actions

Android shows at most three buttons on a notification.

| Agent | Buttons |
| --- | --- |
| Waits at one single-choice question | Its first two options, then **Reply**. A tap answers the question, like a tap in Chat (`POST /api/terminals/:t/answer`). |
| Waits at another dialog | **Reply** (types into the dialog with `force`), **Open**, **Stop** (`POST …/interrupt`) |
| Error | **Open**, **Reply** (`POST …/send`) |

Reply is an inline reply (Android's reply action). When a reply cannot go,
the notification says **Reply queued** (it goes when the link is back) or
**Reply kept in app** (open the app to send it again or delete it). The text
is never lost.

A tap on the notification opens that agent in the app. Every button that
types or presses keys asks you to unlock the phone first. The app sends an
`Origin` header equal to the server URL, so the server's same-origin check
applies to it unchanged.

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
- The page can answer dialogs. In the Chat view, a tap on an option of an
  omp or pi question form calls `POST /api/terminals/:t/answer`. The server
  reads the screen to find the question the form shows, then presses only
  navigation keys (arrows, Space, Enter; no Ctrl or Alt). If the form is not
  on screen, it presses nothing. While an agent is blocked, Send types into
  the dialog with `force`. Otherwise `terminal.send` is called
  without `force`, so hc refuses to type into an agent that just became
  blocked.
- `POST /api/sessions` (New session) starts only `claude` or `omp`, in the
  folder of a space that already exists. The client sends a space id, a
  harness name and the task text. It cannot send a path or a command. The
  task text goes to the agent as one argument, with no shell.

## How it works

    browser ── SSE /api/events ──┐
            ── fetch /api/... ───┤  server.js (routes, lib/http.js)
                                 ├─ lib/hub.js: the SSE clients
                                 ├─ lib/monitor.js ── 2 sockets ── hc (herdr-core)
                                 │   events.subscribe  (status, title, doc)
                                 │   doc.get herdr-app (spaces and tabs)
                                 │   terminal.get/read/send/interrupt
                                 ├─ lib/overview.js ── open PRs (gh), cached on disk
                                 ├─ lib/sidebar.js ── git (repo, worktree, branch)
                                 ├─ lib/git.js, lib/pr.js ── git, gh
                                 └─ lib/push.js ── Web Push (VAPID, aes128gcm)

    web/ (React) ── vite build ──> dist/ (served by server.js)

    Android app ── SSE /api/events (screens), /api/agents/stream (alerts)
                ── /api/info, /api/discover, /api/machines ── lib/herdr.js, lib/agents.js

`hc schema` prints the full `hc` API.

What keeps it up and quick:

- **hc goes away.** The monitor closes both sockets, sends `connection:
  false`, and connects again every 2 s. A missing socket at start does the
  same; it does not stop the server. Each hc call fails after 20 s with no
  reply, so a stuck hc cannot hold a request open.
- **Spinner titles.** Agents put a braille spinner in their title, about 12
  changes a second. The monitor drops the spinner, so a title change that
  only moves it sends nothing. It also sends at most one update per terminal
  every 250 ms (the first at once, the newest one at the end).
- **A slow client.** A stream with more than 1 MB waiting to go out is cut
  off; the client reconnects and gets a fresh snapshot.
- **The PR overview.** It answers at once with the last result, refreshes it
  in the background when it is older than 60 s, and keeps a copy in
  `$HERDR_MONITOR_DIR/cache/overview.json`, so a restart does not wait for
  `gh` (about 6 s for a dozen repos).
- **Big answers** are gzipped on the thread pool, not on the event loop.

## Test

    npm test           # server and library tests (node:test)
    npm run test:bun   # the same tests on Bun
    npm run typecheck  # the UI
    cd android && ./gradlew test lint   # the Android app

## License

MIT. See [LICENSE](LICENSE).
