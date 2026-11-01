# Code Review Comments — GoLand Plugin

Local code-review comments for GoLand. Add comments on selected lines from the
editor, store them as JSON per-branch under `.code-review/<branch>.json`, and
let an external skill pick them up to push to a Merge Request.

## Build & Install

```bash
JAVA_HOME=<JDK 21 path> ./gradlew buildPlugin
# Output: build/distributions/code-review-plugin-<version>.zip
```

In GoLand: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the zip.
This release supports GoLand IDE builds 253 through 262, including GoLand 2026.2.

Run sandbox IDE with the plugin loaded:

```bash
JAVA_HOME=<JDK 21 path> ./gradlew runIde
```

## Usage

- Select code (or just put the caret on a line) → right-click → **Add Code
  Review Comment**, or press <kbd>Shift</kbd>+<kbd>Cmd</kbd>+<kbd>M</kbd>.
- Direct code comments still work in normal editors exactly as before; MR diff
  review is an additional path, not a replacement.
- Use **Open MR Diff** from the tool window toolbar to review the MR bound to
  the current branch. The plugin reads MR metadata from
  `.code-review/<branch>.json` and opens a dedicated **MR Review** tab with a
  persistent changed-files list on the left and the selected file's diff on the
  right, without asking for a target branch.
- In the MR Review tab, select a changed file from the left list, then focus
  the right-hand side (the current branch revision), select changed lines, and
  use the same
  <kbd>Shift</kbd>+<kbd>Cmd</kbd>+<kbd>M</kbd> shortcut to send a comment draft
  into the sidebar. Comments continue to be written in the right sidebar rather
  than inline inside the diff.
- Clicking an MR-linked comment card returns to the matching file/block inside
  the MR Review tab instead of jumping out to a normal editor tab.
- The **Code Review** tool window on the right is thread-based. New comments are
  inserted as inline draft thread cards, and replies stay grouped under the same
  anchor instead of being flattened into separate top-level cards.
- Clicking a thread card jumps to the commented file/line. Individual replies
  can be edited inline, and threads can be replied to, resolved, reopened, or
  deleted from the same sidebar.
- Only comments created by the current user are editable or deletable. Remote
  comments authored by someone else are rendered read-only.
- Comment text follows the IDE editor font family, while card colors continue to
  follow the IDE theme.
- Thread metadata includes a sync-status badge such as `Local`, `Modified`,
  `Synced`, or `Delete pending` on the same line as the timestamp.
- Newly created comments briefly flash after save so the created card is easier
  to spot in the list.
- Commented code ranges are highlighted in the editor; the currently selected
  comment gets a stronger highlight.
- A speech-bubble icon appears in the gutter for commented lines in open
  editors. Clicking it opens the related comment in the tool window.

## Team Distribution

- Build a versioned zip with `./gradlew buildPlugin`.
- Install or upgrade from the zip in `build/distributions/` via
  `Settings -> Plugins -> gear icon -> Install Plugin from Disk...`.
- Existing branch-scoped comment files under `.code-review/` are preserved
  across plugin upgrades.

## Storage layout (skill contract)

Comments live in `<projectRoot>/.code-review/<sanitized-branch>.json`.
The local model is thread-first; replies live inside `threads[].comments`:

```json
{
  "branch": "feature/foo",
  "mrUrl": "https://gitlab.example.com/group/project/-/merge_requests/123",
  "mrId": "123",
  "sourceBranch": "feature/foo",
  "targetBranch": "origin/main",
  "baseCommit": "abc123...",
  "headCommit": "def456...",
  "title": "feat: add code review pipeline",
  "reviewDoc": "docs/reviews/mr-123.md",
  "threads": [
    {
      "id": "thread-uuid",
      "file": "path/relative/to/project.go",
      "side": "new",
      "startLine": 12,
      "endLine": 18,
      "createdAt": "2026-04-23T10:00:00Z",
      "updatedAt": "2026-04-23T10:00:00Z",
      "status": "open",
      "outdated": false,
      "syncStatus": "local_only",
      "deletedAt": null,
      "remoteThreadId": null,
      "reviewTargetRef": "origin/main",
      "diffBaseCommit": "abc123...",
      "diffHeadCommit": "def456...",
      "diffOldPath": "old/path/file.go",
      "diffNewPath": "new/path/file.go",
      "comments": [
        {
          "id": "comment-uuid",
          "threadId": "thread-uuid",
          "content": "Free-form text",
          "createdAt": "2026-04-23T10:00:00Z",
          "updatedAt": "2026-04-23T10:00:00Z",
          "createdByName": "Alice Chen",
          "createdByUsername": "alice.chen",
          "syncStatus": "local_only",
          "deletedAt": null,
          "remoteCommentId": null
        }
      ]
    }
  ]
}
```

Branch names are sanitized via `[^A-Za-z0-9._-] → _`.

The downstream skill should:
1. Read the file for the branch you're submitting against.
2. Populate the branch JSON top-level MR context before the plugin opens MR diff:
   `mrUrl`, `mrId`, `sourceBranch`, `targetBranch`, `baseCommit`, `headCommit`, `title`.
   Store `targetBranch` as `origin/<targetBranch>`.
3. Create a remote thread from each local thread whose `remoteThreadId == null`
   using the thread anchor plus the first comment body.
4. Append replies by syncing local `threads[].comments[]` entries whose
   `remoteCommentId == null` into the parent `remoteThreadId`.
5. Write back remote IDs using `threads[].remoteThreadId` and
   `threads[].comments[].remoteCommentId`.
6. Preserve `createdByName` / `createdByUsername` for local ownership checks.
7. Resolve or reopen at the thread level using `threads[].status`.
8. When `deletedAt != null` and remote IDs are present, delete the matching
   remote thread or note and then clear or finalize the local tombstone.
9. When a thread comes from an MR diff review, use `reviewTargetRef`,
   `diffBaseCommit`, `diffHeadCommit`, `diffOldPath`, and `diffNewPath` to
   reconstruct the remote diff position.

See [local-mr-comment-schema.md](local-mr-comment-schema.md) for the full local
schema and the exact remote field mapping contract.
