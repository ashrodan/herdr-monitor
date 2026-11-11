# Summary: a Herdr mod

What needs you across every space, inside Herdr. It is the native version of
herdr-monitor's Overview.

- **Bar** (status row): how many agents are waiting, done (not seen) and
  working, how many web apps run, and how many PRs are open. Click a count to open the panel on that
  list; click it again to close the panel.
- **Panel** (right panel): the four tiles, then the lists.
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
