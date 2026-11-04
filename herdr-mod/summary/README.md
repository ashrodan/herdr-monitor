# Summary: a Herdr mod

What needs you across every space, inside Herdr. It is the native version of
herdr-monitor's Overview.

- **Bar** (status row): how many agents are waiting, done (not seen) and
  working, and how many PRs are open. Click a count to open the panel on that
  list; click it again to close the panel.
- **Panel** (right panel): the four tiles, then the lists.
  - Agents: space · tab, and for Claude Code agents the latest recap (Claude's
    away summary, else its last reply). Click a row to show the agent.
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
| `summary.all` / `.waiting` / `.done` / `.working` / `.prs` | open the panel on that list |
| `summary.refresh` | reload PRs and recaps now |

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
- Nothing in it writes: no actions on PRs or agents.
