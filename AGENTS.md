# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build & Run

Requires JDK 21. Kotlin 2.0.21, IntelliJ Platform Gradle Plugin 2.1.0, Gradle 8.10.2.

```bash
JAVA_HOME=<JDK 21 path> ./gradlew buildPlugin   # produces build/distributions/code-review-plugin-<version>.zip
JAVA_HOME=<JDK 21 path> ./gradlew runIde        # launches a sandbox GoLand with the plugin loaded
```

Plugin targets GoLand 2024.2.5 (`sinceBuild=242`, `untilBuild=253.*`). Version and name come from `gradle.properties` (`pluginVersion`, `pluginName`).

## Architecture

GoLand/IntelliJ plugin that lets users add code-review comments on selected lines and persists them as JSON per git branch. The JSON is the integration contract with an external "skill" that pushes comments to a Merge Request — the plugin itself does no network I/O.

Key layers under `src/main/kotlin/com/codereview/`:

- **`model/`** — `ReviewComment` is the on-disk schema (mirrors the JSON in `README.md`, including the remote-sync fields `remoteProvider`, `remoteDiscussionId`, `remoteNoteId`, `remoteAuthorName`, `remoteUrl`, `lastSyncedAt`, plus `syncStatus` and `deletedAt` tombstone). `CommentDraft` is the in-flight UI state for an unsaved new comment.
- **`storage/CommentStore`** — reads/writes `<projectRoot>/.code-review/<sanitized-branch>.json` using Gson. Branch names sanitized via `[^A-Za-z0-9._-] → _`. Current branch comes from `git/GitBranchProvider` (Git4Idea).
- **`service/CommentService`** — project-level `@Service`, the single entry point for add/update/delete. Publishes `CommentService.TOPIC` on the project message bus after every mutation; UI listens and refreshes. Delete semantics are important: if the comment has any remote IDs, it's soft-deleted (`syncStatus = "delete_pending"`, `deletedAt` set) so the external skill can propagate the delete; otherwise it's hard-deleted. `update` flips `synced`/`delete_pending` back to `modified`.
- **`service/CommentSelectionService`** — tracks which comment card is currently "selected" so editor decorations and the tool window stay in sync.
- **`actions/AddCommentAction`** — the `Shift+Cmd+M` / editor-popup entry point. Captures selection → hands a `CommentDraft` to the tool window, which inserts an inline draft card.
- **`ui/CommentsPanel` + `CommentsToolWindowFactory` + `CommentToolWindowService`** — the right-side "Code Review" tool window. Card-based list; cards flip between read and inline-edit mode. Only comments authored by the current git user (compared against `createdByName` for local, `remoteAuthorName` after sync) are editable/deletable.
- **`editor/CommentEditorDecorations`** — range highlighters for commented lines plus the gutter speech-bubble icon that opens the corresponding card.
- **`editor/CommentDraftFactory`** — turns an editor selection into a `CommentDraft` (file path relative to project, line range, code snippet).
- **`startup/CommentProjectActivity`** — `postStartupActivity` that warms up services and wires editor decorations for already-open files.
- **`git/GitUserProvider`** — resolves the display name stamped into `createdByName`; falls back through Git4Idea config.

Plugin wiring lives in `src/main/resources/META-INF/plugin.xml`: services, the tool window, the startup activity, the notification group (`CodeReview.Notifications`), and the `CodeReview.AddComment` action. Depends on `Git4Idea` and bundled module `intellij.platform.vcs.dvcs.impl`.

## Storage contract (do not break without updating the skill)

Comments live at `<projectRoot>/.code-review/<sanitized-branch>.json` with the shape documented in `README.md`. The plugin writes local fields; the external skill writes back the `remote*` and `lastSyncedAt` fields after pushing to the MR. Tombstones (`deletedAt != null` with remote IDs) are the signal for the skill to delete the remote note.
