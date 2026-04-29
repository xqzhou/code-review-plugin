# code.byted.org MR Review Comments 数据结构（Thread/Comment）

本文整理 code.byted.org（Codebase）上 Merge Request 的 review comments（thread/comment）数据结构，用于做双向同步。

数据来源：通过 `mcp__Codebase__ListMergeRequestComments` 拉取真实 MR（`people/makalu` 的 `!153`）的返回值抽样总结。

> 注意：字段随平台演进可能增减；本文件描述的是「当前观测到的」结构与语义。

---

## 1) 拉取评论：`ListMergeRequestComments` 响应结构

### Top-level

```json
{
  "PageNumber": 1,
  "PageSize": 50,
  "TotalCount": 2,
  "Threads": ["Thread", "..."]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `PageNumber` | number | 分页页码（从 1 开始） |
| `PageSize` | number | 分页大小 |
| `TotalCount` | number | 满足筛选条件的 thread 总数 |
| `Threads` | `Thread[]` | thread 列表（每个 thread 里带 comments） |

### Thread

```json
{
  "Id": "770648899097410",
  "Status": "open",
  "Outdated": false,
  "CreatedAt": "2026-04-24T04:07:12Z",
  "RepoId": "986640",
  "CommentableType": "merge_request",
  "CommentableId": "770373710794161",
  "Position": {"...": "Position"},
  "TruncatedDiff": {"...": "TruncatedDiff"},
  "Comments": ["Comment", "..."]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `Id` | string | thread 的唯一标识（用于回复/同步时的主键） |
| `Status` | string | thread 状态：观测到 `open` / `resolved` |
| `Outdated` | bool | 是否 outdated（与 MR diff 版本演进相关） |
| `CreatedAt` | string | thread 创建时间（RFC3339） |
| `RepoId` | string | 代码库内部 repo id（与 `RepoId` 参数的 `people/makalu` 不同） |
| `CommentableType` | string | 观测到为 `merge_request` |
| `CommentableId` | string | MR 的内部 id（不是 MR number） |
| `Position` | `Position` | 线程定位信息（文件/行/side/commit） |
| `TruncatedDiff` | `TruncatedDiff` | 该线程锚定位置对应的 diff 片段（可能被截断） |
| `Comments` | `Comment[]` | 同一 thread 下的评论列表（按时间顺序观测） |

> 观测：`Thread.Id` 与 thread 的首条 comment `Comments[0].Id` 一致；但建议仍把 `Thread.Id` 作为 thread 主键使用。

### Comment

```json
{
  "Id": "770538507089930",
  "Content": "...",
  "CreatedAt": "2026-04-23T13:29:54Z",
  "UpdatedAt": "2026-04-23T13:29:54Z",
  "CreatedBy": {
    "Id": "6578865",
    "Username": "liuminji",
    "DisplayName": "刘民霁"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `Id` | string | comment 的唯一标识 |
| `Content` | string | 评论正文（原始文本，可能包含换行） |
| `CreatedAt` | string | 创建时间（RFC3339） |
| `UpdatedAt` | string | 更新时间（RFC3339） |
| `CreatedBy` | object | 作者简要信息（不是完整 user profile） |

`Content` 可承载 Markdown 图片语法。当前插件本地图片以
`![image](.code-review/assets/...)` 形式存入 local JSON；同步到远端前若要
在 GitLab / code.byted.org 正常显示，需要先把本地图片上传为远端可访问 URL，
再替换正文中的本地路径。

### CreatedBy（作者引用）

| 字段 | 类型 | 说明 |
|---|---|---|
| `Id` | string | 用户 id |
| `Username` | string | 用户名 |
| `DisplayName` | string | 展示名 |

### Position（线程定位）

```json
{
  "Type": "content",
  "Side": "new",
  "OldCommitId": "...",
  "NewCommitId": "...",
  "Path": "internal/platform/approval/models.go",
  "StartLine": 313,
  "StartColumn": 12,
  "EndLine": 313,
  "EndColumn": 28
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `Type` | string | 观测到为 `content` |
| `Side` | string | 定位在 diff 的哪一侧：观测到 `new`（也可能有 `old`） |
| `OldCommitId` | string | old side 对应的 commit id |
| `NewCommitId` | string | new side 对应的 commit id |
| `Path` | string | 文件路径（repo 相对路径） |
| `StartLine` | number | 起始行（1-based） |
| `StartColumn` | number\|null | 起始列（1-based；整行评论可能为 `null`） |
| `EndLine` | number | 结束行（1-based） |
| `EndColumn` | number\|null | 结束列（1-based；整行评论可能为 `null`；精确选区时按 end-exclusive 范围处理） |

插件本地 schema 现在会在用户选中文本创建 comment 时保存
`startColumn/endColumn`，用于和这里的 `StartColumn/EndColumn` 做双向映射。
只有光标所在行创建的整行 comment 才保持列号为 `null`。

### TruncatedDiff（上下文 diff 片段）

```json
{
  "Binary": false,
  "Truncated": false,
  "RawPatch": "@@ -315,7 +310,7 @@\n ..."
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `Binary` | bool | 是否二进制 diff |
| `Truncated` | bool | patch 是否被截断 |
| `RawPatch` | string | 原始 patch 文本（unified diff 片段） |

---

## 2) 写入评论：`CreateMergeRequestComment` 入参结构（用于双向同步）

用于把外部系统的 comment/thread 同步回 code.byted.org：

```json
{
  "RepoId": "people/makalu",
  "Number": 153,
  "Draft": false,
  "VersionNumber": 11,
  "Comment": {
    "Content": "...",

    "ThreadId": "770648899097410",

    "FilePath": "internal/platform/approval/models.go",
    "Side": "new",
    "StartLine": 313,
    "EndLine": 313,
    "StartColumn": 12,
    "EndColumn": 28
  }
}
```

关键点：

- **回复现有 thread**：设置 `Comment.ThreadId`。此时文件定位字段（`FilePath/Side/StartLine/...`）会被忽略。
- **创建新 thread（inline comment）**：不设置 `ThreadId`，改为提供文件定位字段（`FilePath` + `Side` + 行范围，若是精确选区则同时提供列范围）。

字段语义（来自 tool schema）：

| 字段 | 说明 |
|---|---|
| `RepoId` | 仓库路径（如 `people/makalu`） |
| `Number` | MR number（如 `153`） |
| `VersionNumber` | 可选：指定评论落在哪个 MR 版本（不传默认 latest） |
| `Draft` | 是否草稿评论 |
| `Comment.Content` | 评论正文 |
| `Comment.ThreadId` | 可选：回复某个 thread 的 id |
| `Comment.FilePath` | 可选：创建新 thread 时需要的文件路径 |
| `Comment.Side` | 可选：`old` 或 `new`，默认 `new` |
| `Comment.StartLine` / `EndLine` | 可选：行号（1-based） |
| `Comment.StartColumn` / `EndColumn` | 可选：列号（1-based）；精确选区评论使用，整行评论可省略或传 `null` |

---

## 3) 双向同步建议（字段级主键与幂等）

只针对「数据结构」相关的最低限建议：

- Thread 主键：优先用 `Thread.Id`；外部系统可维护 `external_thread_id <-> codebase_thread_id` 映射。
- Comment 主键：用 `Comment.Id`；同理维护 `external_comment_id <-> codebase_comment_id`。
- 位置锚点：用 `Thread.Position`（`Path + Side + Start/EndLine(+Column) + Old/NewCommitId`）作为「线程落点」的可比对信息；列号为空表示整行锚点，列号非空表示文本范围锚点。
- 状态同步：用 `Thread.Status`（`open/resolved`）做线程级状态；comment 本身未观测到独立的 open/resolve 字段。
