---
name: herdr-coordinate
description: Coordinate coding agents across all Herdr spaces - triage who needs you, unblock them, hand out work, and follow it to a result. Use on the board endpoint (/mcp).
---

# Coordinate agents across Herdr

Herdr runs coding agents in **spaces**. A space is one repo or one git
worktree on this machine. Each space has tabs, and each tab has terminals:
agents (Claude Code, omp, Codex) or programs (dev servers, tests).

An agent has one state:

| State | Meaning | What you do |
| --- | --- | --- |
| blocked | It waits at a question or a permission dialog | Answer it |
| error | Its turn failed | Read it, then send a fix or tell the operator |
| done | Its turn ended; nobody has looked yet | Read the result |
| working | It is busy | Leave it, or wait on it |
| idle | It waits for a task | Give it work, or leave it |

## The loop

1. `herdr_overview`. Agents that need you come first.
2. For each agent that needs you: `herdr_read` it. Then:
   - a question form: `herdr_answer` with `option` (and `question`).
   - a permission or other dialog: `herdr_read` with `source: "screen"`,
     then `herdr_answer` with `keys`.
   - a decision that belongs to the operator: do not guess. Report it.
3. For each done agent: `herdr_read` it and report the result in one or
   two sentences.
4. To hand out work: pick the space that owns the repo (`herdr_space`).
   Give the task to an idle agent there with `herdr_send`, or start a new
   one with `herdr_start`.
5. Follow up: `herdr_wait` (about 20 s), then `herdr_read`.

## Rules

- **One writer per space.** Do not give two agents write work in the same
  space at the same time. Check `herdr_space` first.
- **Delivery is not completion.** After `herdr_send` or `herdr_start`, wait
  and read. Do not resend a task because a wait timed out.
- **Keep waits short.** Hosted clients often cut a tool call at about 25 s.
  On a timeout, call `herdr_wait` again.
- **Do not approve what you do not understand.** A permission dialog for a
  deploy, a migration, a push to main or a secret is the operator's call.
- **Report outcomes, not activity.** Say what changed, what is blocked,
  and what you need from the operator.

## Watching without polling

Clients that speak MCP 2026-07-28 can open `subscriptions/listen` with
`resourceSubscriptions: ["herdr://agents"]`. Each
`notifications/resources/updated` means an agent changed state: read
`herdr://agents` again, then act. Other clients poll `herdr_overview`.

## Pull requests

`herdr_prs` lists open PRs with checks and the reasons each cannot merge.
Use it to tell the operator what is ready for review.
