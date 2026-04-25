# Code Review Comments — GoLand Plugin

Local code-review comments for GoLand. Add comments on selected lines from the
editor, store them as JSON per-branch under `.code-review/<branch>.json`, and
let an external skill pick them up to push to a Merge Request.

## Build & Install

```bash
JAVA_HOME=<JDK 21 path> ./gradlew buildPlugin
# Output: build/distributions/code-review-plugin-0.1.0.zip
```

In GoLand: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the zip.

Run sandbox IDE with the plugin loaded:

```bash
JAVA_HOME=<JDK 21 path> ./gradlew runIde
```

## Usage

- Select code (or just put the caret on a line) → right-click → **Add Code
  Review Comment**, or press <kbd>Shift</kbd>+<kbd>Cmd</kbd>+<kbd>M</kbd>.
- The **Code Review** tool window on the right is card-based. New comments are
  inserted as inline draft cards, and existing comments are edited inline in the
  same list.
- Clicking a card jumps to the commented file/line. Double-clicking a card, or
  using **Edit**, switches that card into inline edit mode.
- Only comments created by the current user are editable or deletable. Remote
  comments authored by someone else are rendered read-only.
- Comment text follows the IDE editor font family, while card colors continue to
  follow the IDE theme.
- Card metadata now includes a sync-status badge such as `Local`, `Modified`,
  `Synced`, or `Delete pending` on the same line as the timestamp.
- Newly created comments briefly flash after save so the created card is easier
  to spot in the list.
- Commented code ranges are highlighted in the editor; the currently selected
  comment gets a stronger highlight.
- A speech-bubble icon appears in the gutter for commented lines in open
  editors. Clicking it opens the related comment in the tool window.
- Card actions are hover-only to keep the list visually quiet, but card height
  stays stable while hovering.

## Team Distribution

- Build a versioned zip with `./gradlew buildPlugin`.
- Install or upgrade from
  [build/distributions/code-review-plugin-0.1.0.zip](build/distributions/code-review-plugin-0.1.0.zip)
  via `Settings -> Plugins -> gear icon -> Install Plugin from Disk...`.
- Existing branch-scoped comment files under `.code-review/` are preserved
  across plugin upgrades.

## Storage layout (skill contract)

Comments live in `<projectRoot>/.code-review/<sanitized-branch>.json`:

```json
{
  "branch": "feature/foo",
  "comments": [
    {
      "id": "uuid",
      "file": "path/relative/to/project.go",
      "startLine": 12,
      "endLine": 18,
      "codeSnippet": "...",
      "comment": "Free-form text",
      "createdAt": "2026-04-23T10:00:00Z",
      "updatedAt": "2026-04-23T10:00:00Z",
      "status": "pending",
      "syncStatus": "local_only",
      "createdByName": "Alice Chen",
      "deletedAt": null,
      "remoteProvider": null,
      "remoteMergeRequestId": null,
      "remoteDiscussionId": null,
      "remoteNoteId": null,
      "remoteAuthorName": null,
      "remoteUrl": null,
      "lastSyncedAt": null
    }
  ]
}
```

Branch names are sanitized via `[^A-Za-z0-9._-] → _`.

The downstream skill should:
1. Read the file for the branch you're submitting against.
2. POST each active `pending` comment to the MR API with the file, line range and text.
3. Write back remote IDs after sync using `remoteProvider`, `remoteDiscussionId`,
   `remoteNoteId`, `remoteAuthorName`, `remoteUrl`, and `lastSyncedAt`.
4. Preserve `createdByName` for local ownership checks and use `remoteAuthorName`
   for remote ownership checks after sync.
5. When a local comment has `deletedAt != null` and remote IDs are present, delete
   the corresponding GitLab note/discussion and then remove or finalize the local tombstone.
