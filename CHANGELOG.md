# Changelog

## [2026.2]

- **Tasks tool window** listing every configured task server's issues in one table, grouped by server or flat
- **Every Task Management server type** supported; GitHub, GitLab, Jira and YouTrack get much more:
  - in-place editing of titles, descriptions and comments, including your own past comments
  - the tracker's own status names, assignees, priorities, labels and linked pull requests
  - epics, parents, sub-tasks and other linked issues (Jira, YouTrack)
- **Details pane** with the description, fields, pull requests, linked issues and threaded comments with avatars and
  reactions
- **Sorting** by several rules from column headers or a sort popup: workflow-ordered status, priority, dates and an
  assignee ranking; save a sort with *Done* and bring it back with *Restore*
- **Active task** marked with a yellow arrow, earlier started tasks with a grey one
- **Right-click menu** to switch to a task, edit the active one, or set a status the server allows
- **Dates** in the IDE's format, including "5 minutes ago" when pretty formatting is on
- **Caching** that follows Task Management's issue cache interval, with few requests per list and per task
- View settings and the sort are remembered per project
- Requires [Task Management](https://plugins.jetbrains.com/plugin/11545-task-management), which must be installed
  manually from 2026.2
