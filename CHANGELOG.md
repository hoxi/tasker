# Changelog

## [2026.2]

- A **Tasks** tool window listing the issues from every configured task server in one sortable table, grouped per
  server or as one flat list, with each tracker's own status names shown as coloured badges
- A details pane showing the issue's description, dates, priority, labels and other fields, its linked pull and merge
  requests, its linked issues — epic, parent, sub-tasks and other links, for Jira and YouTrack — and its comments
  with avatars, replies and emoji reactions
- Issues from every server type Task Management supports, shown with what its connector provides
- Much more for GitHub, GitLab, Jira and YouTrack: rename issues, edit their descriptions and post comments in place,
  edit your own comments (GitHub, Jira and YouTrack), and see priorities, labels, linked pull requests and each
  tracker's own status names. More server types may get this detailed handling in the future
- Sort by several fields at once from a sort popup, or Shift+click column headers: status in workflow order, priority,
  dates, and assignees ranked with your own issues first; save a sort with Done and restore it after a stray click
- Switch to a task or change its status from the right-click menu, offering only the transitions the server allows
- Lists and task details are kept as long as Task Management's own issue cache, and refreshed on the same interval while
  the tool window is showing, so changing the view doesn't ask every server again
- View settings are remembered per project, and the list reloads by itself after the servers are reconfigured
- Requires the [Task Management](https://plugins.jetbrains.com/plugin/11545-task-management) plugin, which is no
  longer bundled with the IDE from 2026.2: on 2026.2 and later, install it manually from the Marketplace first
