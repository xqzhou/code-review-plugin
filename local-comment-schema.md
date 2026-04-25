# Local MR Comment Schema

This document defines the local `.code-review/<branch>.json` schema used by the
plugin. It is intentionally smaller than the observed code.byted.org /
GitLab-style MR comment schema in [mr-comment-schema.md](./mr-comment-schema.md).

The local model is **thread-first**:

- one `ReviewThread` represents one anchored MR discussion
- one or more `ReviewComment` entries live inside that thread
- line / diff position data exists only on the thread
- replies do not duplicate anchor metadata

## Top-level JSON

```json
{
  "branch": "feat/multi-version",
  "reviewDoc": "docs/reviews/mr-153.md",
  "mrUrl": "https://code.byted.org/people/makalu/-/merge_requests/153",
  "mrId": "153",
  "sourceBranch": "feat/multi-version",
  "targetBranch": "origin/master",
  "baseCommit": "f013adad...",
  "headCommit": "ebd04424...",
  "title": "add multi-version design baseline",
  "threads": []
}
```

## ReviewThread

```json
{
  "id": "local-thread-uuid",
  "file": "internal/platform/engine/step_form_resolver.go",
  "side": "new",
  "startLine": 148,
  "endLine": 150,
  "startColumn": null,
  "endColumn": null,
  "createdAt": "2026-04-24T04:07:12Z",
  "updatedAt": "2026-04-24T04:07:12Z",
  "status": "open",
  "outdated": false,
  "reviewTargetRef": "origin/master",
  "diffBaseCommit": "f013adad...",
  "diffHeadCommit": "ebd04424...",
  "diffOldPath": "internal/platform/engine/step_form_resolver.go",
  "diffNewPath": "internal/platform/engine/step_form_resolver.go",
  "syncStatus": "local_only",
  "deletedAt": null,
  "remoteThreadId": null,
  "findingId": null,
  "comments": []
}
```

### Thread field notes

- `id`: local stable UUID for the thread
- `file`: repo-relative display path for the anchor
- `side`: currently `new` in plugin-created comments; `old` is reserved for
  future compatibility
- `status`: `open` or `resolved`
- `outdated`: thread-level outdated state
- `reviewTargetRef`: always store as `origin/<target-branch>`
- `syncStatus`: `local_only`, `modified`, `synced`, `delete_pending`
- `deletedAt`: thread tombstone timestamp when remote deletion must be synced
- `remoteThreadId`: remote discussion / thread id
- `findingId`: optional source finding badge

## ReviewComment

```json
{
  "id": "local-comment-uuid",
  "threadId": "local-thread-uuid",
  "content": "这里是不是要用对应版本的 definition",
  "createdAt": "2026-04-24T04:07:12Z",
  "updatedAt": "2026-04-24T04:07:12Z",
  "createdByName": "Xiaoqiang Zhou",
  "createdByUsername": "xiaoqiang.zhou",
  "syncStatus": "local_only",
  "deletedAt": null,
  "remoteCommentId": null
}
```

### Comment field notes

- `threadId`: always points to the parent thread
- `content`: plain comment body
- `createdByName`: used by the plugin for local ownership checks
- `createdByUsername`: optional; useful for stricter sync ownership later
- `remoteCommentId`: remote note / comment id
- replies are just additional `ReviewComment` entries appended to the thread

## Minimal mapping: remote -> local

### Thread

| Remote field | Local field |
|---|---|
| `Thread.Id` | `ReviewThread.remoteThreadId` |
| `Thread.Status` | `ReviewThread.status` |
| `Thread.Outdated` | `ReviewThread.outdated` |
| `Thread.CreatedAt` | `ReviewThread.createdAt` |
| `Thread.Position.Path` | `ReviewThread.file` |
| `Thread.Position.Side` | `ReviewThread.side` |
| `Thread.Position.StartLine` | `ReviewThread.startLine` |
| `Thread.Position.EndLine` | `ReviewThread.endLine` |
| `Thread.Position.StartColumn` | `ReviewThread.startColumn` |
| `Thread.Position.EndColumn` | `ReviewThread.endColumn` |
| `Thread.Position.OldCommitId` | `ReviewThread.diffBaseCommit` |
| `Thread.Position.NewCommitId` | `ReviewThread.diffHeadCommit` |
| `Thread.Position.Path` | `ReviewThread.diffNewPath` by default |

If the skill already knows a rename pair from the MR diff, it should populate:

- `diffOldPath` with the old path
- `diffNewPath` with the new path

Otherwise both may safely equal `Thread.Position.Path`.

### Comment

| Remote field | Local field |
|---|---|
| `Comment.Id` | `ReviewComment.remoteCommentId` |
| `Comment.Content` | `ReviewComment.content` |
| `Comment.CreatedAt` | `ReviewComment.createdAt` |
| `Comment.UpdatedAt` | `ReviewComment.updatedAt` |
| `Comment.CreatedBy.DisplayName` | `ReviewComment.createdByName` |
| `Comment.CreatedBy.Username` | `ReviewComment.createdByUsername` |

## Minimal mapping: local -> remote

### Create new thread

Use the **first non-deleted comment** in the thread as the root comment.

Send:

- no `ThreadId`
- `Comment.Content` = root comment `content`
- `Comment.FilePath` = `ReviewThread.file`
- `Comment.Side` = `ReviewThread.side`
- `Comment.StartLine` = `ReviewThread.startLine`
- `Comment.EndLine` = `ReviewThread.endLine`
- `Comment.StartColumn` = `ReviewThread.startColumn ?: 1`
- `Comment.EndColumn` = `ReviewThread.endColumn ?: 1`

On success, write back:

- `ReviewThread.remoteThreadId`
- root `ReviewComment.remoteCommentId`
- `syncStatus = "synced"` for both thread and root comment

### Reply to existing thread

For a local comment with:

- `remoteCommentId == null`
- parent `ReviewThread.remoteThreadId != null`

Send:

- `Comment.ThreadId` = `ReviewThread.remoteThreadId`
- `Comment.Content` = reply `content`

Do **not** resend file / line position for replies.

On success, write back:

- `ReviewComment.remoteCommentId`
- `ReviewComment.syncStatus = "synced"`

### Resolve / reopen

Resolve state is **thread-level** only.

| Local field | Remote field |
|---|---|
| `ReviewThread.status = "resolved"` | remote `Thread.Status = "resolved"` |
| `ReviewThread.status = "open"` | remote `Thread.Status = "open"` |

Do not model resolve state per reply.

### Delete

#### Delete thread

- if `remoteThreadId == null`: local-only thread can be removed directly
- if `remoteThreadId != null`: mark thread `deletedAt` and
  `syncStatus = "delete_pending"`, then let the skill delete the remote thread
  or its root note and clear the tombstone afterward

#### Delete reply

- if `remoteCommentId == null`: local-only reply can be removed directly
- if `remoteCommentId != null`: mark reply `deletedAt` and
  `syncStatus = "delete_pending"`, then let the skill delete the remote note

## Deliberately omitted local fields

The plugin does **not** persist these remote-only fields in local JSON:

- `RepoId`
- `CommentableType`
- `CommentableId`
- `TruncatedDiff`
- `RawPatch`
- full `CreatedBy` object
- `VersionNumber`

The skill should derive those from MR context and remote API responses instead
of storing them in plugin-owned local state.
