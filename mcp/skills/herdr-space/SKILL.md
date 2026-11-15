---
name: herdr-space
description: Work inside one Herdr space (one repo or worktree) - read its agents, give them tasks, answer their questions and follow each task to a result. Use on a space endpoint (/mcp/spaces/<name>) or with a space named on the board endpoint.
---

# Work in one Herdr space

A space is one repo or one git worktree. On a space endpoint
(`/mcp/spaces/<name>`), every tool acts only on that space: you cannot
touch agents in other spaces by mistake.

## Look

1. `herdr_space`: the folder, the branch, the tabs, and each agent and
   program with its state.
2. `herdr_read <agent>`: the last messages of its conversation, and any
   question it waits on. Use `last` for more messages. Use
   `source: "screen"` for a program, a dialog, or an agent with no
   readable conversation.

## Act

| You want to | Call |
| --- | --- |
| Give an idle agent a task, or reply to it | `herdr_send` |
| Answer its question form | `herdr_answer` with `option` (1-based) |
| Answer a multi-part form | `herdr_answer` for each part, then `submit: true` |
| Get past a permission dialog | `herdr_answer` with `keys`, e.g. `["1"]` |
| Add an agent for a new task | `herdr_start` with `prompt` |
| Stop its current turn | `herdr_interrupt` |

## Follow a task to its result

1. `herdr_send` (or `herdr_start`).
   Or use `herdr_handoff` for steps 1 to 3 in one call: it sends, waits and
   reads, and on a timeout gives the output so far and a cursor.
2. `herdr_wait` with about 20 s (the maximum is 25 s). On a timeout, call it
   again. Do not resend. To see progress, `herdr_read` with `since` set to
   the `Cursor:` of your last read.
3. When it stops:
   - done or idle: `herdr_read` and report the result.
   - blocked: `herdr_read`, then `herdr_answer`, then go back to step 2.
   - error: `herdr_read`, then decide: send a fix, or report it.

## Write a good task

- Say the outcome, not the steps: "Make the login test pass and open a PR."
- Name the files, issue or PR if you know them.
- Say what needs the operator: "Ask before you push."
- One task per message. A long task is fine; a vague one is not.

## Rules

- One agent writes in a space at a time. If one is working, wait, or give
  the new task to that same agent after it is done.
- Do not approve deploys, migrations, pushes to main or secret changes in
  a dialog. Report them to the operator.
