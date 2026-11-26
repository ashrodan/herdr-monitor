# Summary: a Herdr mod

What needs you across every space, inside Herdr. It is the native version of
herdr-monitor's Overview.

- **Bar** (status row): how many agents are waiting, done (not seen) and
  working, how many web apps run, and how many PRs are open. Click a count to open the panel on that
  list; click it again to close the panel.
- **Panel** (right panel): the tiles (Decide, Working, Apps, PRs), Ask, then the lists.
  - **Decide**: what waits on your call, as cards with one-click options,
    most urgent first:

    | Card | When | Options |
    | --- | --- | --- |
    | Asks | an agent is blocked on a question or permission prompt | **Yes** / **No** (permission prompts of Claude Code and Codex only), Review, Later |
    | Merge | your PR: not a draft, checks green (or none), not waiting on a review | **Merge** (confirm names the method and base), Review, Later |
    | Fix | your PR with failing checks or changes requested | **Fix with agent**, Review, Later |
    | Review | someone's PR waits on your review | **Approve** (confirm), Review, Later |
    | Done | an agent finished while you weren't looking | **Approve: open PR**, Review, Later |
    | Bots | Dependabot, Renovate and other apps' PRs, all on one card | Show (the PR list), Later |

    Review shows the agent, or the PR in ship-it's review pane when a space
    has its branch checked out (GitHub otherwise). Later hides a card until
    its subject changes (the agent moves on, the PR is updated).
  - Agents: space · tab, and for Claude Code agents the latest recap (Claude's
    away summary, else its last reply). Click a row to show the agent.
  - Running apps: each web app that runs in a space, on this Mac or on a
    remote machine, with its port, program (vite, next, …) and tailnet URL.
    Click a row to show its space (and the app's terminal, when a pane
    shows it); Open opens the URL; Copy copies it. An app that listens on
    `localhost` only shows "This machine only" and a **Share** button (see
    below). When a shared Vite app refuses the tailnet host name, the row says
    so: add `server.allowedHosts: ['.ts.net']` to its Vite config.
  - Open PRs: every open PR in the repos of the open spaces (worktrees of one
    repo count once), with repo, branch, author, draft or review state, and a
    dot when checks fail (red) or run (amber). Click a row to open it on GitHub.
  - **Ask** (under the tiles): a chat with a Gemini model that knows your
    spaces, agents, machines and open PRs. Ask about state ("what's waiting?"),
    or say what to start. Proposals come back as cards with a **Run** button:
    - **Start** an agent (claude or codex): a new worktree
      `<repo>/.worktrees/<slug>` on a new branch from the remote's default
      branch, in a new space with the brief as its first message; or a tab in
      the space's folder for work that changes nothing.
    - **Tell** a running agent something (`hc send`).
    - **Hand off** a space to another machine: opens the
      [herdr-handoff](https://github.com/ashrodan/herdr-handoff) dialog on that
      space with the machine picked; you confirm there.

## Install

To have an agent install it, tell it:

> Read ~/.config/herdr-app/AGENTS.md, then add this mod: https://github.com/ashrodan/herdr-monitor/tree/main/herdr-mod/summary

Or run the script from a clone of this repo:

```sh
scripts/install-mod.sh --layout main-browser   # copy, check, add the blocks
scripts/install-mod.sh                         # copy and check only
```

The layout step backs the layout up once to `<name>.json.before-summary`. It
refuses Herdr's own layouts (`main`, `terminals`, `win98`), which Herdr
replaces on every start: clone one first. By hand, the blocks are:

```json
{ "type": "block", "id": "summary-bar", "content": { "mod": "summary", "props": { "bar": true } } }
{ "type": "block", "id": "summary", "content": { "mod": "summary" } }
```

The first goes in the status row, the second in the `panel-body` stack.

## Commands

| Command | Does |
| --- | --- |
| `summary.open` | open the panel |
| `summary.toggle` | open or close the panel |
| `summary.all` / `.waiting` / `.done` / `.working` / `.apps` / `.prs` | open the panel on that list |
| `summary.refresh` | reload PRs, apps and recaps now |
| `summary.decide` | open the panel on Decide |
| `summary.decide:<n>:<option>` | pick an option on Decide card `n` (from 1): `yes`, `no`, `merge`, `fix`, `approve`, `approve-pr`, `review`, `later`, `show-prs`; `confirm` / `cancel` answer a Merge or Approve confirm |
| `summary.ask` | focus the Ask input (opens the panel from the bar) |
| `summary.ask:<text>` | send a message to Ask |
| `summary.run:<n>` | run card `n` (from 1) of the last answer |
| `summary.clear` | clear the chat |

## Ask: setup and how it works

Each turn runs `herdr-ask` (`bin/herdr-ask.js`, linked into `~/.local/bin` by
`scripts/install-mod.sh`; Node 20+, no dependencies). The mod writes the
message, the last 12 turns and a small snapshot (space ids, names and folders;
agent addresses, status and titles; connected machines; the PR count and the
latest 20 PRs, no transcripts) to `~/.cache/herdr-ask/turn-*.json`.
`herdr-ask --consume` reads and deletes it, calls Gemini with three tools
(`spawn_agent`, `tell_agent`, `hand_off`), and prints `{reply, actions}`.
When Gemini answers a tool call with no words, it sends one more round with a
`functionResponse` per call so the reply text comes back (about 7 s instead of 4 s).

Every action is checked against the snapshot before it becomes a card: an
unknown space, agent or machine is dropped and named under the reply, the
agent is `claude` or `codex`, and a branch that isn't `feat|fix|chore|docs|spike/<slug>`
is replaced by one made from the title.

The key: `GEMINI_API_KEY`, else the macOS Keychain item `herdr-ask`/`gemini`:

```sh
security add-generic-password -U -s herdr-ask -a gemini -w <key>
```

The model: `HERDR_ASK_MODEL`, default `gemini-3.8-flash`.

## How apps are found

1. One scan per machine that has a space (`sh`, via `exec … {on}`): the TCP
   listeners (`lsof` on macOS, `ss` on Linux), and for each process its
   `HC_TERMINAL_ID`, folder and command line (one `ps -E` / `lsof` call on
   macOS, `/proc` on Linux). Also `tailscale status` (the machine's tailnet
   name) and `tailscale serve status`.
2. A listener belongs to a space when its terminal is in a pane of that
   space, else when its folder is inside the space's folder or a pane's
   folder (home folders never count). Container port forwarders (OrbStack,
   Docker) are left out.
3. Only those listeners are probed: `curl` GET `/` with a 2 s limit. An app
   is a port that answers with `text/html`. A second request with the tailnet
   name as `Host` finds dev servers that refuse other host names.
4. The URL: the Tailscale Serve URL when one proxies to the port, else
   `http://<machine>.<tailnet>.ts.net:<port>` when the app listens on all
   addresses or the tailnet address, else none ("This machine only").

## What writes

| Action | When | Command |
| --- | --- | --- |
| Yes / No | a click, after a fresh `hc get` shows the agent still blocked | `hc keys <t> enter` / `hc keys <t> esc` |
| Approve: open PR | a click | `hc send <t> "Looks good. Commit anything left, push the branch and open a pull request (or update the open one)."` |
| Merge | Merge, then Confirm | `gh api graphql` (allowed methods, merge state, head, base; blocked or conflicting stops here), then `gh pr merge <n> --repo <r> --<squash\|merge\|rebase> --match-head-commit <head>` |
| Approve (PR) | Approve, then Confirm | `gh pr review <n> --repo <r> --approve` |
| Fix with agent | a click | `hc send` the failing checks to an idle agent in the space on that branch, else `git worktree add --track -B <branch> <repo>/.worktrees/<slug> origin/<branch>` and a new space with claude |
| Start (worktree) | a click on Run | `git fetch origin`, `git worktree add <repo>/.worktrees/<slug> -b <branch> <origin default>` (refused when the repo doesn't ignore `.worktrees/`), then `space.new {folder, argv: [claude\|codex], task: brief}` |
| Start (no worktree) | a click on Run | `tab.new {space, folder, argv, task}` |
| Tell | a click on Run | `hc send <terminal> <text>` |
| Hand off | a click on Run | opens the handoff dialog; that dialog's own button runs `herdr-handoff send` |
| Share | a click on Share | `tailscale serve --bg --https=<port> http://127.0.0.1:<port>` (`[::1]` for an IPv6-only app), on the app's machine; on Linux it tries `sudo -n` when the plain command fails |

To stop sharing: `tailscale serve --https=<port> off`. Tailscale Serve
reaches your tailnet only; the mod never uses Funnel.

## Cost

- Agent counts and lists come from Herdr (`agents()`): no polling.
- PRs: one `gh pr list --limit 30` per repo, 4 at a time, every 2 minutes
  (the bar does it, so the panel opens with fresh data). Both copies share
  the result (`summary.prs`).
- Recaps: read only for the Claude agents the panel shows, and again only
  when an agent changes. Each read is `hc get`, the session file and the last
  600 kB of the transcript, filtered by `grep` before JavaScript sees it.
  Local agents only.
- A PR load that finds nothing new does not write the shared value, so it
  does not redraw the window.
- Hidden copies do no work: the panel loads recaps and catches up on PRs only
  when it is on screen (in the panel, or as a pane in the tab on screen).
- Apps: one scan per machine every minute (the bar does it; about 1 s of
  CPU on a Mac with 50 listeners, 0.15 s on Linux), shared as
  `summary.apps`. Only the listeners of the spaces are probed.
- The only write is Share (above). No actions on PRs or agents.
