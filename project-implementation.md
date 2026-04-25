# Code Review Comments 插件实现文档

## 1. 实现概览

项目当前实现了一个基于 GoLand 平台的本地代码评审插件。整体架构可分为五层：

- 动作入口层
- 本地模型与存储层
- 服务层
- UI 层
- MR Review / Diff 层

插件通过 IntelliJ 平台扩展点注册 project service、tool window、file editor provider 和 action，并围绕 `.code-review/<branch>.json` 建立本地状态体系。

## 2. 插件注册与入口

插件定义文件：

- [plugin.xml](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/resources/META-INF/plugin.xml)

当前注册项包括：

- `CommentService`
- `CommentSelectionService`
- `MrReviewService`
- `CommentToolWindowService`
- `CommentEditorDecorations`
- `MrReviewFileEditorProvider`
- `Code Review` tool window
- `CodeReview.AddComment` action

其中 action 绑定了默认快捷键：

- `Shift + Cmd + M`

用于在普通编辑器和 MR diff 中发起评论创建。

## 3. 模型与数据结构

模型定义位于：

- [ReviewComment.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/model/ReviewComment.kt)
- [MrReviewSession.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/model/MrReviewSession.kt)

## 3.1 评论模型

### `CommentFile`

表示某个 Git 分支对应的本地评论文件，包含：

- 分支信息
- MR 元信息
- 线程集合
- 兼容旧版本的 legacy comments 字段

### `ReviewThread`

表示一个评审线程，包含：

- 锚点文件路径与起止行列
- side 与 diff commit 信息
- thread 状态与 outdated 状态
- 本地/远端同步状态
- reply 列表

### `ReviewComment`

表示 thread 下的单条 comment 或 reply。  
它在持久化时只保留 comment 级字段，在运行时通过 `CommentService` 从父 thread 补齐：

- file
- line range
- diff 元数据
- thread 状态
- outdated 等 transient 信息

### `MrReviewSession`

表示一次 MR Review 会话，包含：

- MR 标题和 id
- source / target
- merge base / head commit
- 变更文件列表
- 跳过的二进制文件数

## 4. 本地存储层

存储实现位于：

- [CommentStore.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/storage/CommentStore.kt)

## 4.1 存储路径

存储目录：

- `<projectRoot>/.code-review/`

当前分支对应文件：

- `<sanitized-branch>.json`

分支名通过 `GitBranchProvider.safeBranchFileName(...)` 做安全化处理。

## 4.2 读写策略

### 读取

- 若文件不存在，则返回空 `CommentFile`
- 若文件存在，则使用 Gson 反序列化
- 若读取失败：
  - 将原文件重命名为 `.broken-<timestamp>`
  - 弹出 warning
  - 返回空数据

### 写入

- 写入前会执行 normalize
- 使用临时文件 + 原子替换
- 不再写回 legacy `comments` 字段

## 4.3 兼容与迁移

`CommentStore` 内实现了从旧版 `LegacyStoredComment` 到 `ReviewThread + ReviewComment` 的迁移逻辑：

- 只有在新结构 `threads` 为空时才自动迁移
- 迁移后清空 legacy `comments`

这样可以保证旧插件版本留下的数据仍能被新插件读取。

## 5. 服务层

## 5.1 `CommentService`

文件：

- [CommentService.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/service/CommentService.kt)

职责：

- 读取和保存本地评论数据
- 对外提供 thread / comment 查询
- 新建 thread
- 新增 reply
- 编辑 comment
- 修改 thread 状态
- 删除 thread 或 reply
- 在读取后为运行时 comment 注入 thread 级信息

### 关键行为

- `addThread(...)`：创建新 thread 与根 comment
- `addReply(...)`：在已有 thread 上追加回复
- `update(...)`：编辑单条 comment
- `updateThreadStatus(...)`：执行 resolve / reopen
- `delete(...)`：对根 comment 删除会退化为删除整个 thread
- `deleteThread(...)`：显式删除 thread

### 同步状态处理

本地状态主要包括：

- `local_only`
- `modified`
- `synced`
- `delete_pending`

服务层根据 comment / thread 是否已有 remote id、是否被编辑、是否被删除来更新这些状态。

## 5.2 `CommentSelectionService`

职责：

- 维护当前选中的 comment id
- 广播选择变化

该服务用于协调：

- 工具窗口卡片选中
- 编辑器高亮
- gutter 交互

## 5.3 `MrReviewService`

文件：

- [MrReviewService.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/service/MrReviewService.kt)

职责：

- 从本地 JSON 准备 MR Review 上下文
- 基于 Git 变更构建 `MrReviewSession`
- 打开和刷新 MR Review editor
- 创建 diff request
- 在 MR Review 中定位到评论、草稿和导航目标文件
- 管理 MR diff 内部的 back stack

### 关键行为

- `openReview()`：打开 MR Review
- `prepareSession(...)`：根据当前分支的本地 MR 元数据构建会话
- `navigateToComment(...)`：从评论定位到 MR diff
- `navigateToDraft(...)`：从 draft 定位到 MR diff
- `navigateToFileDiff(...)`：在 MR diff 内切到目标文件
- `navigateBackInDiff()`：处理 diff 内部返回

## 5.4 `MrReviewUiStateService`

职责：

- 持久化文件列表显示状态
- 持久化 splitter 比例

用于保证关闭 MR Review 后重新打开时，布局状态仍保持一致。

## 6. 动作入口层

## 6.1 `AddCommentAction`

文件：

- [AddCommentAction.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/actions/AddCommentAction.kt)

实现方式：

- 在普通编辑器中，通过 `CommonDataKeys.EDITOR` 判断是否可用
- 在 MR diff 中，通过 `ReviewDiffDataKeys.COMMENT_TARGET` 和 `DiffDataKeys.CURRENT_EDITOR` 判断是否可用
- 动作触发后调用 `CommentDraftFactory.fromEvent(...)`
- 生成 draft 后交给 `CommentToolWindowService.showDraft(...)`

因此：

- 普通编辑器与 MR diff 共享同一个“添加评论”动作
- 只是 draft 构造方式不同

## 7. UI 层

## 7.1 工具窗口与评论面板

主要文件：

- [CommentsToolWindowFactory.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/ui/CommentsToolWindowFactory.kt)
- [CommentToolWindowService.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/ui/CommentToolWindowService.kt)
- [CommentsPanel.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/ui/CommentsPanel.kt)

### `CommentToolWindowService`

职责：

- 维护工具窗口实例和 `CommentsPanel` 之间的绑定关系
- 提供统一入口：
  - 显示 draft
  - 显示某条评论

### `CommentsPanel`

职责：

- 渲染右侧评论卡片列表
- 响应服务层变化，实时刷新
- 处理用户交互：
  - 新建 draft
  - 编辑 comment
  - 回复
  - 删除
  - resolve / reopen
  - 跳转到代码

### 列表组织方式

- 顶部 toolbar
- 中间 thread 卡片列表
- 每张卡片按 thread 分组展示 root comment 与 replies

### 线程卡片主要能力

- 展示文件、行号、作者、时间、状态
- 对选中状态做视觉高亮
- 支持 inline reply / inline edit
- 区分本地评论和只读远端评论

## 7.2 编辑器装饰

文件：

- [CommentEditorDecorations.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/editor/CommentEditorDecorations.kt)

职责：

- 在普通源码编辑器中监听：
  - comment 改变
  - 选中 comment 改变
  - 文件打开 / 切换
  - Git 仓库变更
- 为当前打开的 editor 添加：
  - 代码范围高亮
  - 当前选中评论高亮
  - gutter 图标

### gutter 图标交互

点击 gutter 图标后，调用 `CommentToolWindowService.showComment(...)`，从而把用户带回评论卡片。

## 8. MR Review / Diff 层

相关文件：

- [MrReviewFileEditor.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/mr/MrReviewFileEditor.kt)
- [MrReviewFileEditorProvider.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/mr/MrReviewFileEditorProvider.kt)
- [MrReviewVirtualFile.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/mr/MrReviewVirtualFile.kt)
- [ReviewDiffContext.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/diff/ReviewDiffContext.kt)

## 8.1 MR Review 页签

MR Review 使用一个自定义 `VirtualFile + FileEditorProvider` 机制创建专用编辑器页签。

优点：

- 可以像普通文件一样被 IDE 打开和切换
- 但内部渲染的是插件自定义的 MR review 界面

## 8.2 页面结构

`MrReviewFileEditor` 当前主要由三部分组成：

- 顶部 header
- 左侧文件列表
- 中间 diff panel

评论区仍然复用右侧 `Code Review` tool window，而不是嵌入 diff 内。

## 8.3 文件列表

左侧文件树支持：

- 按路径分组
- 搜索
- 显示新增/删除行数
- Hide / Show
- 恢复上次可见状态和比例

## 8.4 Diff request 构造

`MrReviewService` 使用 `DiffContentFactory` / `SimpleDiffRequest` 构建双栏 diff。

当目标文件不在原始 change 列表中时，也支持运行时构造临时 diff：

- 两侧都有内容 -> 正常双栏
- 一侧不存在 -> 另一侧为空

## 8.5 MR diff 内评论锚点

在 MR diff 中创建评论时，会把以下信息写入 thread：

- `reviewTargetRef`
- `diffBaseCommit`
- `diffHeadCommit`
- `diffOldPath`
- `diffNewPath`

这样外部同步工具可以准确还原远端 diff 位置。

## 8.6 MR diff 内导航

当前已支持在 diff 内进行：

- `Go to Declaration`
- `Go to Implementation`
- `Go to Type Declaration`
- `Navigate Back`

实现原则：

1. 在真实项目文件上做 PSI 解析
2. 将目标文件和目标行映射回 MR diff
3. 尽量保持在当前 MR Review 上下文内

对于 `Go to Implementation` 的多实现场景，当前实现会弹出 chooser，用户选择后再切换到目标 diff。

## 9. Git 与环境依赖

相关文件：

- [GitBranchProvider.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/git/GitBranchProvider.kt)
- [GitUserProvider.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/git/GitUserProvider.kt)
- [ProjectFiles.kt](/Users/bytedance/Workspace/ide/code-review-plugin/src/main/kotlin/com/codereview/util/ProjectFiles.kt)

作用：

- 获取当前分支
- 生成稳定的分支文件名
- 获取当前 Git 用户名 / 显示名
- 在项目内做相对路径与 `VirtualFile` 查找

这些能力支撑了：

- 分支级存储
- 本地 ownership 判断
- diff 文件路径映射

## 10. 对外数据契约

项目内已有两份结构文档：

- [local-comment-schema.md](/Users/bytedance/Workspace/ide/code-review-plugin/local-comment-schema.md)
- [gitlab-comment-schema.md](/Users/bytedance/Workspace/ide/code-review-plugin/gitlab-comment-schema.md)

它们分别定义：

- 插件本地 JSON 结构
- 远端 MR 评论结构及映射关系

插件当前策略是：

- 插件维护本地 schema
- 外部 skill 负责远端 API 交互
- 同步完成后将 remote id 写回本地 JSON

这种设计让插件本身保持轻量，避免直接耦合到某一家远端平台 API。

## 11. 已实现能力清单

截至当前版本，项目已经具备以下能力：

- 分支级本地评论存储
- 旧版评论结构迁移
- 普通编辑器选区评论
- thread-first 评论模型
- 回复、编辑、删除、resolve、reopen
- 只允许编辑自己的评论
- 工具窗口评论卡片视图
- 编辑器高亮与 gutter 图标
- MR Review 视图
- MR diff 中评论锚点保存
- MR diff 内代码导航与回退
- 文件列表显隐和状态持久化
- 多实现 chooser

## 12. 当前限制

- 远端同步逻辑仍依赖外部工具，不在插件内部完成。
- `outdated` 目前仍主要依赖同步进来的 thread 标记，本地没有完整自动重算机制。
- 评论仍主要在右侧工具窗口中编辑，不支持在 diff 中直接嵌入式编辑。
- `Find Usages` 等更复杂导航仍未全部映射到 MR diff 内。

## 13. 后续演进方向

- 本地自动判定 `outdated`
- 支持更多 diff 内导航类型
- 更完整的远端同步状态可视化
- 更强的筛选、搜索和分组能力
- 在评论工具窗口中增加更多 MR 上下文信息
