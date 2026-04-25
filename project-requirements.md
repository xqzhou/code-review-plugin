# Code Review Comments 插件需求文档

## 1. 项目概述

`Code Review Comments` 是一个面向 GoLand 的本地代码评审插件，目标是在 IDE 内提供完整的评审工作台能力，包括：

- 在普通源码编辑器中对代码行或代码块添加评审评论
- 将评论以 Git 分支维度存储到本地 JSON 文件
- 在右侧工具窗口中以 thread 形式查看、编辑、回复、删除和管理评论
- 在 IDE 内打开 MR 风格的 diff 评审界面
- 在 diff 与评论之间双向联动
- 为外部同步工具提供稳定的数据契约，使本地评论可以推送到远端 Merge Request 平台

该插件本质上不是远端代码评审平台的替代品，而是一个 **本地评审工作台 + 本地状态层 + 远端同步前置层**。

## 2. 背景与问题

团队在日常开发中存在以下问题：

- 开发者希望在 IDE 内直接记录代码评审意见，而不是频繁切换到浏览器。
- 浏览器中的 MR 评论与本地源码上下文割裂，阅读和补充上下文成本高。
- 远端评审系统的接口和权限可能不稳定，需要一个本地缓存层保存评审结果。
- 评论需要按 Git 分支隔离，避免不同功能分支之间的内容相互污染。
- 在 diff 评审时，希望评论、代码跳转、文件切换、返回等行为都保持在同一个评审上下文中。

因此需要构建一个插件，使开发者可以在 IDE 内完成：

1. 本地记录评论
2. 查看和组织评论线程
3. 基于 MR diff 审阅改动
4. 将本地状态安全、可恢复地交给外部同步系统处理

## 3. 产品目标

## 3.1 核心目标

- 在 GoLand 中提供本地化、可持久化的代码评审能力。
- 以 Git 分支为作用域隔离评论数据。
- 支持线程化评论模型，而不是简单的扁平评论列表。
- 支持普通文件评审和 MR diff 评审两种工作方式。
- 提供清晰的本地与远端同步边界。

## 3.2 用户体验目标

- 用户在 IDE 内选中代码即可发起评论。
- 评论编辑、回复、删除、解决/重开都在同一侧边栏中完成。
- 评论与源码、gutter、diff、MR Review 视图之间可相互跳转。
- 在 diff 内进行代码导航时尽量不离开评审视图。

## 3.3 工程目标

- 本地存储结构稳定、可演进、兼容旧格式。
- 与外部同步工具之间使用明确的 schema 契约。
- 插件升级后不破坏已有 `.code-review/*.json` 数据。

## 4. 用户与场景

## 4.1 用户角色

- 开发者：在提交前对自己或他人的代码进行预审。
- Reviewer：针对当前分支或 MR 进行结构化评审。
- 同步工具开发者：读取本地 JSON，将评论同步到远端平台。

## 4.2 典型场景

### 场景 A：普通文件中添加评论

用户在普通源码编辑器里选中若干行代码，执行 `Add Review Comment` 动作，插件在右侧工具窗口打开 draft 卡片，用户填写并保存。

### 场景 B：查看和管理本地评论线程

用户打开 `Code Review` 工具窗口，查看当前分支下所有评论线程，对已有评论执行：

- 回复
- 编辑
- 删除
- Resolve
- Reopen

### 场景 C：在 MR Review 中评审变更

用户点击 `Open MR Diff`，插件根据当前分支对应的本地 JSON 中记录的 MR 上下文，构建一个 MR 风格的 diff review 页面，并展示：

- 变更文件列表
- 双栏 diff
- 右侧评论线程列表

### 场景 D：评论与代码联动

用户点击评论卡片、gutter 图标或 diff 中的跳转入口，插件在合适的位置打开或切换：

- 普通源码文件
- MR diff 中的目标文件
- 评论对应的代码范围

### 场景 E：本地评论同步到远端

外部 skill 或脚本读取 `.code-review/<branch>.json`，利用线程锚点、评论内容和 MR 元数据，将本地评论同步到远端 MR 平台，并回写 remote id。

## 5. 功能需求

## 5.1 评论创建

- 支持在普通编辑器中对当前光标所在行或选择的代码范围创建评论。
- 支持在 MR diff 的右侧（当前分支版本）创建评论。
- 创建动作统一通过 `Add Review Comment` 触发。
- 新建评论先进入工具窗口中的 draft 状态，再由用户确认保存。

## 5.2 评论存储

- 所有评论按当前 Git 分支保存到：
  - `<projectRoot>/.code-review/<sanitized-branch>.json`
- 分支名需要进行安全转换。
- 数据模型以 `thread` 为中心，`comments` 为子项。
- 需要兼容旧版扁平评论结构，并自动迁移到新结构。
- 文件损坏时，需要自动备份并回退到空状态，而不是导致插件不可用。

## 5.3 评论线程模型

- 一个 thread 表示一个锚定在代码位置上的讨论。
- 一个 thread 下允许有多个 comment，支持 reply。
- 线程级别维护：
  - 状态：`open` / `resolved`
  - outdated 标记
  - sync 状态
  - diff 位置
  - 远端 thread id
- 评论级别维护：
  - content
  - author
  - sync 状态
  - 删除墓碑
  - 远端 comment id

## 5.4 评论工具窗口

- 插件需要提供一个固定在右侧的 `Code Review` 工具窗口。
- 工具窗口顶部提供基础操作：
  - 打开 MR diff
  - 刷新
  - 复制本地 JSON 路径
- 评论列表以线程卡片形式展示，而不是纯 comment 平铺。
- 卡片需要展示：
  - 文件名
  - 路径 / 行号
  - 作者与时间
  - sync 状态
  - resolve / outdated 等标签

## 5.5 评论编辑与权限

- 仅允许当前用户编辑或删除自己创建的评论。
- 远端同步回来的、由他人创建的评论应以只读方式展示。
- 回复应追加到原 thread 中，而不是创建新的顶层卡片。

## 5.6 评论可视化装饰

- 在普通源码编辑器中，对已评论代码范围进行背景高亮。
- 当前选中评论需要更强高亮。
- 在评论起始行的 gutter 显示气泡图标。
- 点击 gutter 图标能定位到相关评论卡片。

## 5.7 评论与代码联动

- 点击评论卡片能跳转到对应代码位置。
- 在普通文件场景下，打开对应源码文件并定位。
- 在 MR diff 场景下，优先在 MR Review 内定位到对应 diff 文件与代码块。
- 当 thread 已失去精确锚点时，插件应提供合理提示，而不是静默失败。

## 5.8 MR Review 视图

- 插件需要支持在 IDE 内打开一个专门的 MR Review 编辑器页签。
- MR Review 依赖本地 JSON 中的 MR 上下文：
  - `mrUrl`
  - `mrId`
  - `sourceBranch`
  - `targetBranch`
  - `baseCommit`
  - `headCommit`
  - `title`
- MR Review 页面至少包含：
  - 顶部标题和分支/commit 元信息
  - 左侧文件列表
  - 中间双栏 diff
  - 右侧评论列表

## 5.9 MR Diff 内导航

- 在 MR diff 中执行声明跳转、实现跳转、类型跳转时，优先留在当前 MR Review 视图内。
- 目标文件若存在 old/new 两侧内容，则展示完整双栏 diff。
- 只存在某一侧时，另一侧允许为空。
- 对于多实现或多候选场景，应允许用户选择目标。
- `Navigate Back` 应尽量回到此前的 diff 上下文。

## 5.10 文件列表与布局管理

- 文件列表支持 `Hide Files / Show Files`。
- 隐藏后应把空间还给 diff viewer。
- 隐藏状态需要在关闭并重新打开 MR Review 后保留。
- 顶部需显示当前文件路径，避免文件列表隐藏后失去上下文。

## 5.11 远端同步契约

- 插件不直接负责与远端 MR 平台的完整双向同步实现，但必须输出稳定的本地结构供外部工具读取。
- 本地结构必须包含足够的字段，使外部工具可以：
  - 创建新 thread
  - 追加 reply
  - Resolve / Reopen
  - 删除 thread / reply
  - 回写 remote thread/comment id
- 插件需要保留远端同步相关字段，但不绑定具体远端平台实现。

## 6. 数据需求

## 6.1 本地文件结构

- 顶层包含：
  - branch
  - reviewDoc
  - mrUrl
  - mrId
  - sourceBranch
  - targetBranch
  - baseCommit
  - headCommit
  - title
  - threads

## 6.2 Thread 字段

- 本地唯一 id
- 文件路径
- 起止行列
- side
- createdAt / updatedAt
- status
- outdated
- reviewTargetRef
- diffBaseCommit / diffHeadCommit
- diffOldPath / diffNewPath
- syncStatus
- deletedAt
- remoteThreadId
- findingId

## 6.3 Comment 字段

- 本地唯一 id
- threadId
- content
- createdAt / updatedAt
- createdByName / createdByUsername
- syncStatus
- deletedAt
- remoteCommentId

## 7. 非功能需求

## 7.1 稳定性

- 插件升级不能破坏历史数据文件。
- 本地 JSON 损坏时要可恢复。
- MR diff 导航行为需要稳定，避免修复一个入口打坏另一个入口。

## 7.2 可维护性

- 存储层、服务层、UI 层、MR diff 层职责清晰。
- 本地 schema 与远端 schema 的映射关系应独立成文档。

## 7.3 可扩展性

- 后续可以扩展更多远端平台或同步工具。
- 可扩展更多导航能力，如 usages、related items。
- 可扩展更多 comment 状态或 badge。

## 8. 非目标

- 不在插件内内建完整远端平台 API 客户端。
- 不在本阶段实现服务端权限管理。
- 不在本阶段实现完整的浏览器 MR 界面替代。

## 9. 验收标准

- 用户可以在普通源码编辑器中创建本地评论。
- 评论能够持久化到分支级本地 JSON。
- 用户可以在 `Code Review` 工具窗口中查看和管理线程。
- 打开源码文件时能看到评论高亮和 gutter 图标。
- 用户可以打开 MR Review，并看到文件列表、双栏 diff 与评论区。
- 在 MR diff 内创建评论后，评论与 diff 锚点能够正确保存。
- 评论卡片可以把用户带回对应代码位置。
- 本地数据可被外部同步工具读取并回写 remote id。
