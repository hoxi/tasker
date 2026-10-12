# Tasker

![Tasker tool window](docs/images/tool-window.png)

## Overview

An IntelliJ IDEA tool window that puts every issue from every configured task server in one sortable, groupable table —
with a details pane you can edit in place.

JetBrains' **Task Management** plugin already talks to your trackers, but only through a search popup, one issue at a
time. Tasker reuses those same connections and shows everything as a table you can work in.

Tasker lists issues from **every** server type Task Management supports. For **GitHub**, **GitLab**, **Jira** and
**YouTrack** it goes further: in-place editing of titles, descriptions and comments, priorities, labels, assignees,
linked pull requests and issues, comment threads, reactions, avatars, and each tracker's own status names. More server
types may follow.

## Requirements

- IntelliJ IDEA **2026.1** or newer (build 261+)
- The **Task Management** plugin (`com.intellij.tasks`), installed and enabled — Tasker won't load without it
- At least one task server under **Settings | Tools | Tasks | Servers**

Tasker has no credentials or settings of its own; it uses the servers you configured for Task Management.

### Installing Task Management on 2026.2 and later

Task Management is no longer bundled from **2026.2**. Install
[Task Management](https://plugins.jetbrains.com/plugin/11545-task-management) first — **Settings | Plugins |
Marketplace**, search for *Task Management* — then Tasker. On 2026.1 and earlier it's already there.

## The task list

Opens as the **Tasks** tool window at the bottom of the IDE.

- **Columns** — ID, Status, Assignee, Summary, Updated, Created. Drag to reorder; ID stays pinned left.
- **Header sorting** — click a header to sort by it, Shift+click to add it as a further rule.
- **Sort popup** — sort by several fields at once:
  - drag rules into order and pick each direction in words ("Newest first", "Highest first")
  - rank assignees, e.g. *Me*, then a colleague, then everyone else
  - status sorts in workflow order; empty values always go last; ties keep the server's order
  - changes apply live; *Reset* clears the sort and can be undone
  - *Done* (or Enter) saves the sort; *Restore* brings it back after an accidental header click
- **Grouping** — tasks sit under their server; turn *Group by Server* off for a flat list.
- **Status** — a coloured badge with the tracker's own state name.
- **Active task** — a yellow arrow and bold id. Tasks you've started before get a grey arrow.
- **Closed issues** — hidden by default, greyed when shown.
- **Task list limit** — issues requested per server; 30 by default.
- **Dates** — in the IDE's date format. With *pretty formatting* on (**Settings | Appearance & Behavior | System
  Settings | Date Formats**), recent ones read "5 minutes ago" or "Today 14:05" and keep up as time passes.

View settings, the sort and the saved sort are remembered per project.

### Loading and caching

- Servers load in parallel; a slow or failing one never holds up the rest, and says so on its own row.
- Lists and task details are kept as long as Task Management's issue cache (*Update issue cache every N minutes* under
  **Settings | Tools | Tasks**, 20 by default). Toggling closed issues, changing the limit or revisiting a task is
  instant.
- While the tool window is showing, anything older is refreshed in the background. **Refresh** reloads everything.
- Assignees, priorities and linked issues are read with the list, a page of issues per request, so selecting a task
  doesn't ask for them again.

## The details pane

![Details pane](docs/images/details-pane.png)

The selected issue's id, title, status, labels, dates, priority and other fields, its description, pull requests, linked
issues and comments. Comments and the full description are fetched on selection and cached.

- **Comments** — rendered as the tracker renders them, with avatars, threaded replies and emoji reactions (yours
  highlighted).
- **Pull requests** — linked pull and merge requests with their state. *Show in IDE* opens GitLab merge requests in the
  GitLab plugin.
- **Linked issues** (Jira, YouTrack) — epic, parent and sub-tasks, then *blocks*, *relates to* and other links, each
  with its status. Click one to jump to its row, or open it in the browser if it isn't listed.
- **Dates** — hover Created or Updated for the exact time.

### Editing in place

The pen next to the **title** and the **description** makes them editable where they sit.

- Title: `Enter` saves, `Escape` cancels
- Description: Save and Cancel buttons; `Escape` cancels
- New comment: `Enter` sends, `Shift+Enter` adds a newline, `Escape` discards
- **Your own comments** get a pen too, and edit like the description

![Inline editing](docs/images/inline-edit.png)

Anything a tracker can't do is greyed out, with a tooltip saying whether the provider doesn't support it or this issue
can't be addressed.

## Right-click menu

![Context menu](docs/images/context-menu.png)

- **Switch to `<task>`** — activates the issue as Task Management's *Open Task* does: straight away if you've worked on
  it before, otherwise through the Open Task dialog (changelist, branch, context).
- **Edit `<task>`** — on the active task instead: Task Management's *Edit Task* dialog for its name, changelist and
  branch.
- **Set status** — only the transitions the server allows from the current state.

## Toolbar

|                        |                                                                                                     |
|------------------------|-----------------------------------------------------------------------------------------------------|
| **Refresh**            | Reload every server and drop cached details — done for you after the servers are reconfigured       |
| **Group by Server**    | Toggle the per-server tree                                                                          |
| **Show Closed Issues** | Show closed and resolved issues too                                                                 |
| **Sort**               | Sort by several fields and rank assignees — see *Sort popup* above                                  |
| **Tasks & Contexts**   | Task Management's own menu: Switch, Open and Close Task, contexts, Configure Servers                |
| **Task list limit**    | Issues requested per server                                                                         |

## What works per provider

Every server type Task Management supports is listed: Bugzilla, Forgejo, FogBugz, Generic, GitHub, GitLab, Jira,
Lighthouse, Mantis, PivotalTracker, Redmine, Trac, Trello and YouTrack. Status changes go through the platform and work
with any tracker that supports them. Everything else needs a Tasker adapter, which GitHub, GitLab, Jira and YouTrack
have.

| Provider                   | Rename | Description | Comment | Edit own comments | Reactions | Linked PRs | Linked issues | Extra fields shown                                          |
|----------------------------|:------:|:-----------:|:-------:|:-----------------:|:---------:|:----------:|:-------------:|-------------------------------------------------------------|
| GitHub                     |   ✅   |     ✅      |   ✅    |        ✅         |    ✅     |     ✅     |       —       | Assignee, labels (coloured)                                 |
| GitLab                     |   ✅   |     ✅      |   ✅    |         —         |     —     |     ✅     |       —       | Assignee, Time spent, Estimate, labels                      |
| YouTrack                   |   ✅   |     ✅      |   ✅    |        ✅         |    ✅     |     ✅     |      ✅       | Priority (coloured), Type, Assignee, tags (coloured)        |
| Jira                       |   ✅   |     ✅      |   ✅    |        ✅         |     —     |     ✅     |      ✅       | Type, Priority (with icon), Assignee, Reporter, labels      |
| Redmine, Trac, Bugzilla, … |   —    |      —      |    —    |         —         |     —     |     —      |       —       | —                                                           |

Notes:

- **YouTrack** fills the platform's custom properties itself, so its fields need no extra request. `Type` shows only if
  the project has a field named exactly `Type`.
- **GitLab's "Time spent"** is what's logged against the issue (`time_stats`). GitHub has no time tracking.
- **Jira** shows its own status names and issue-type icons, which the bundled connector drops; Tasker reads them back.
- **Jira's epic** comes from `parent` on Cloud, and from the *Epic Link* field on Server and Data Center.
- **Jira's pull requests** come from the undocumented `dev-status` API; if it stops answering, the section is hidden.
  Jira reactions aren't available to API tokens.
- **GitHub's pull requests** are those that mention the issue; one linked only from the sidebar isn't visible.
- **"Tracked in IDE"** is the IDE's own stopwatch, shown for any provider once you've switched to the task.

Providers without an adapter still list, sort, group, show details and comments, and change status; they just can't be
edited from the pane.

## Building

Needs JDK 25.

```bash
./gradlew buildPlugin      # -> build/distributions/Tasker-<version>.zip
./gradlew runIde           # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin     # run the JetBrains plugin verifier
```

Install the built zip through **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Licence

[MIT](LICENSE) © 2026 Magnus Wållberg
