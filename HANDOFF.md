# 交接文档：cicd-assistant

> 这份文档专门给接手开发的 AI agent 看。新会话开始时把这份贴进去，比让它重读全部历史聊天省 95% 上下文。

## 项目背景

代码合并自助验证系统。两大功能：

1. **代码启动**：拉分支 → mvn 编译 → java -jar 启动 → 探测 actuator/swagger，确认服务能起
2. **合并检测**（开发中）：MR 上线前，把基准分支（develop/uat）和被对比分支（env-*）做语义级 diff，输出报告推钉钉

仓库：`hytionking/cicd-assistant`。当前开发分支由 session 分配，看系统提示"Git Development Branch Requirements"那节。历史两个已并入 main 的分支：`claude/charming-dijkstra-8d8IU`、`claude/zen-allen-j1s6zj`。

## 硬性技术约束（不要换）

- SpringBoot **2.7.18** / **JDK 1.8**（最后兼容版）
- SQLite + MyBatis XML mapper（不用 mybatis-plus）
- gitlab4j-api **4.19.0**（4.20+ 在 Central 没有，5.x 要 JDK11）
- Thymeleaf + 原生 JS，**无前端构建**
- UI 框架 Tabler 1.4.0 + 自带 Bootstrap，本地 vendored（`static/vendor/tabler/`、`static/vendor/tabler-icons/`），不用 CDN
- 前端用 **`tabler.Modal/Dropdown/Alert/Collapse`**，不是 `bootstrap.*` —— Tabler 内嵌了 BS，引外置 bundle 会触发双重事件监听 bug（之前踩过）
- Git 操作**严格只读**，`GitWorkspaceManager` 有白名单子命令，非允许命令会抛 `SecurityException`

## 已交付功能

### 老功能（代码启动）

- 仓库管理 CRUD + AES 加密 token + GitLab 连通性测试 + 模块列表配置
- 异步多分支构建/启动，端口池 18000-18999，按 `Started XxxApplication in` + 服务器探测判定成功
- Windows 上通过 `wmic` 查询父子进程 PID（JDK8 的 ProcessImpl.handle 不是 PID）
- 任务列表分页（10/20/50/100，智能省略 `1 2 … 14`）
- 日志窗：增量 `appendChild` 避免大段文本闪烁，自动刷新可关；终态停轮询

### 新功能（合并检测，P1+P2 已完成）

**数据表**：`compare_task / compare_target / compare_finding / compare_context / notification_webhook`

**核心服务（`com.cicdassistant.service.compare`）**：
- `GitWorkspaceManager` — `clone --no-checkout --filter=blob:none`，fetch baseline+target，`git show ref:path` 读文件
- `MrFileListFetcher` — 调 `getMergeRequestChanges` 拿 MR 改动文件列表（只取路径，不读 patch）
- `JavaSemanticDiffer` — JavaParser 3.25.10，对比 imports/types/methods/fields
- `MybatisXmlDiffer` — DOM 解析，按 `namespace+tag+id` 比 `<select|insert|update|delete|sql|resultMap>`
- `SqlScriptDiffer` — JSqlParser 4.6，DDL 按表名 key，其它按规范化哈希 key
- `GenericTextDiffer` — 兜底
- `FileDifferRouter` — 按扩展名分派
- `CompareEngine.runOneTarget(task, repo, target, mrIids)` — 串起全部流程

**Finding 字段**：
- `detector ∈ {RULE, LLM}`
- `severity ∈ {ERROR, WARN, INFO}`
- `type` 如 `METHOD_MISSING / SQL_TAG_DIFF / FIELD_EXTRA / FILE_MISSING_IN_TARGET ...`

**前端页面**：`/compare/new`、`/compare/tasks`、`/compare/tasks/:id`、`/compare/contexts`、`/compare/webhooks`

**侧栏**：2 级 Bootstrap collapse（不是 dropdown），用 `<ul class="nav nav-pills nav-vertical">`，CSS 在 `static/css/app.css` 里定制了缩进 + chevron + active 蓝色左条

**配置**：
```yaml
app.compare:
  workspace-root: ./workspace-compare
  mr-fetch-default-limit: 20
  llm:
    enabled: false
    base-url: ""        # OpenAI 兼容
    api-key: ""
    model: "qwen2.5-coder-32b-instruct"
    timeout-seconds: 120
    max-tokens: 4096
  notify:
    dingtalk-enabled: true
    message-max-chars: 4500
```

## P3 任务（✅ 已完成）

**目标**：把 `LLM` / `HYBRID` 两种比对模式实现起来，让 finding 带 `LLM` 检测来源徽章。

落地情况：
- `service/compare/LlmClient.java` — JDK `HttpURLConnection` 走 OpenAI 兼容 `/v1/chat/completions`，强制 `response_format=json_object`，调用/解析失败都降级成一条 `detector=LLM type=LLM_CALL_FAILED|LLM_PARSE_FAILED WARN` finding，把原始响应塞 `llmComment`。
- `service/compare/LlmPromptBuilder.java` — system prompt 注入"全局上下文 + 仓库相关上下文"两段；user prompt 含 baseline/target 双版本源码（各 6000 字符截断）。HYBRID 复用同一份 system，user 末尾追加规则引擎的 ERROR/WARN 列表让模型复核。
- `CompareEngine.runOneTarget` 按 mode 分支：RULE 不变；LLM 跳过 differ；HYBRID 先跑 RULE，对每条 ERROR/WARN 调一次 LLM 复核，结论 append 成新 finding（detector=LLM），原 RULE finding 保留。
- `CompareService.create` 增加守卫：mode∈{LLM,HYBRID} 且 `app.compare.llm.enabled=false` 或 `base-url` 为空 → 抛 IllegalArgumentException。
- `controller/ApiExceptionHandler.java` — `@RestControllerAdvice` 把 `IllegalArgumentException` 映射成 400 + `{error,message}`，前端能拿到具体原因。
- `static/js/app.js` 的 `api.*` 出错时尝试读 JSON body 的 message 字段，否则退回 "HTTP <code>"。

### P3.1 新增 `LlmClient`（`service/compare/`）

- 走 OpenAI 兼容协议：`POST {base-url}/v1/chat/completions`
- 请求体 `{model, messages, temperature: 0.1, response_format: {type:"json_object"}}`
- 用 JDK `HttpURLConnection`（项目里其它地方也是），**不要引新 HTTP 库**
- 输入：system prompt（场景+历史问题，来自 `CompareEngine.resolveContexts`） + user prompt（diff 片段 + 评估指令）
- 输出 JSON：`[{severity:"ERROR|WARN|INFO", summary:"...", comment:"..."}]`
- JSON 解析失败 → 上报一条 `detector=LLM type=LLM_PARSE_FAILED WARN`，把原始响应放 `llmComment`

### P3.2 改 `CompareEngine.runOneTarget`，按模式分支

```
mode == RULE   → 现有逻辑不变
mode == LLM    → 跳过 differ，每个改动文件直接把 baseline+target 喂给 LLM
mode == HYBRID → 先跑 RULE，对每条 ERROR / WARN 调用 LLM 复核，
                 把 LLM 结论 append 成新 finding（detector=LLM），原 RULE finding 保留
```

### P3.3 Prompt 模板（建议放 `LlmPromptBuilder.java`）

```
System: 你是代码合并审计助手。下面是业务上下文，判断时务必参考：
        [全局上下文条目 1..n]
        [仓库相关上下文条目 1..n]

User:   文件: src/main/java/...
        基准分支 (develop) 版本：
        ```java
        <baseline>
        ```
        目标分支 (env-1) 版本：
        ```java
        <target>
        ```
        请评估目标分支相对基准分支是否有"上线风险"（代码遗漏 / SQL 丢失 / 逻辑被改回等）。
        以 JSON 数组返回，每项 {severity, summary, comment}。无风险返回 []。
```

### P3.4 Snippet 大小控制

单文件 baseline+target 各截断到 6000 字符（粗略对应 ~2000 tokens），整个 prompt 控制在模型上下文窗口内。

### P3.5 任务级别 LLM 启用守卫

如果 `app.compare.llm.enabled=false` 或 `base-url` 为空：
- 提交 LLM/HYBRID 模式任务直接拒绝 400 + "LLM 未配置"
- 或者降级到 RULE 并写一条 WARNING

### P3.6 P3 不做的

- 钉钉真实推送（留 P4）
- LLM 流式输出（一次性返回 JSON 简单可靠）
- LLM 历史对话（每个文件独立请求）

## 验收方法

```bash
mvn -B -DskipTests clean package
java -jar target/cicd-assistant.jar
# 浏览器开 localhost:8080，admin/admin123
```

## 之前踩过的坑（必看）

1. **Spring Security 默认禁缓存**，给 vendor 资源单独配 `Cache-Control: max-age=31536000`（见 `WebConfig`），其它路径走默认 no-cache
2. **任务详情页 setInterval 重渲染会闪烁** — pager 用 `lastPagerState` 缓存，状态不变不重渲染
3. **大段日志 textContent 替换会重置 scrollTop** — 用 `appendChild` 增量更新
4. **日志窗用 `modal fade`（不要 `modal-blur`）**，因为背景 4s 轮询会让 backdrop-filter 重算闪
5. **`process.destroyForcibly()` 在 Linux 上是 SIGTERM**，进程组要靠 `setsid + kill -9 -- -<pgid>`
6. **SQLite 列已存在的 ALTER 会失败** —— `SchemaMigrator` 用 `PRAGMA table_info` 判断后再加列
7. **`.dropdown-menu` 默认 `display:none`**，包在 `.collapse` 里也不会显示 — 子菜单要用 `<ul class="nav nav-pills nav-vertical">`
8. **侧栏 dropdown 双重监听 bug**：之前同时引了 `bootstrap.bundle.min.js` 和 `tabler.min.js`，两个都注册 `[data-bs-toggle="dropdown"]` 监听，点击瞬间打开又关。Tabler 内嵌了 Bootstrap，**只引 Tabler**

## 测试用真实仓库不方便时

```bash
mkdir /tmp/fake-repo && cd $_ && git init -q
# 写文件、提交到 develop
git branch develop && git checkout -b env-1
# 删一些内容、提交
cd .. && git clone --bare fake-repo fake-repo.git
# 在 cicd-assistant 里配 gitUrl=file:///tmp/fake-repo.git
```

直接用 classloader 调 `FileDifferRouter.route()` 验证 differ：
```bash
javac -cp target/classes /tmp/DifferSmokeTest.java
java -cp /tmp:target/classes:$(find ~/.m2 -name 'javaparser-core*.jar' -o -name 'jsqlparser*.jar' -o -name 'slf4j-api*.jar' | tr '\n' :) DifferSmokeTest
```

## 用户使用的环境

- Qwen3.5，OpenAI 兼容协议
- 钉钉用 webhook，不需要加签
- 部署在 Linux 服务器，systemd 启动
- 公司私服 Nexus URL：`http://10.0.80.56:8081/nexus/content/groups/public/`
- 仓库示例：`gitlab.example.com/group/sub-group/.../service.git`

## P3.x 增量：MR-Patch 驱动校验（✅ 已完成）

为了规避"基准分支（fat）含未上线代码导致全是误报"的问题，引入了 patch verification 作为 MR 模式下的主路径：

- `MrFileListFetcher.changesOf(repo, iid)` 返回 `List<MrFileChange>`（path + unified patch）
- `PatchHunkVerifier` 把 patch 解析成 + 行 / − 行，按文件类型过滤噪音（import/单符号/注释/纯关闭标签），到 target 当前文件做规范化子串匹配
  - 未命中的 + 行 → `MR_LINE_MISSING` ERROR/WARN（>=50% 未命中算 ERROR）
  - 仍存在的 − 行 → `MR_LINE_LINGERING` INFO
  - target 不存在该文件而 patch 全是新增 → `MR_FILE_MISSING` ERROR
  - finding `detector=RULE_PATCH`，UI 徽章 "MR 验证"（青色）
- `CompareEngine.runOneTarget` 改成三分支：
  - RULE/HYBRID + 有 MR → patch verifier，fat 不读
  - LLM → 不变（每文件丢给 LLM）
  - RULE/HYBRID + 没选 MR → 退回老 file-differ，fat 作为 baseline 全量对比
- HYBRID + MR 路径下，patch verifier 的 ERROR/WARN 会通过 `LlmPromptBuilder.buildUserForPatchReview` 送给 LLM 复核，让模型判断"未命中"是真上线丢失还是等价改写/重构改名
- `GET /api/compare/config` 暴露 `mrFetchDefaultLimit/mrFetchMaxLimit/llmEnabled`，前端创建任务页：
  - 新增 "每分支拉取 N 条" 输入（默认值来自配置，可在 1~100 间调）
  - 勾了任意 MR 时，基准分支 label 会出现灰字 "(MR 模式下仅作上下文参照)"

## P3.x 后续迭代（✅ 已完成，本节按时间倒序记录关键决策，方便新 agent 回溯）

### PatchHunkVerifier 三次算法演进（读这段能省很多返工）

**问题现象一路诊断出来的算法演进史**：

1. **初版：整文件折叠 substring 匹配** —— 把 target 全文 `replaceAll("\\s+"," ")` 折成一条字符串，patch 每一 +/- 行也归一化后 substring 找。**噪音爆炸**：`return resultMap;` 之类常用短语在 target 的任何其它方法里都会命中，"漏删"变假阳性
2. **改进：block-with-context 严格连续** —— 要求 patch 里连续的 +/- 块作为一个整体，在 target essential 行序列里连续出现。对 66 行大新增大量误报 —— env 常在两个新增方法之间夹了别人的方法，block 不再 contiguous
3. **最终：per-line + 3 行小窗口** —— 每条 +/- 行 self + 一个 prev/next 邻居；target 上找到 self 的位置 k，只要 prev 在 target[k-3..k-1] 或 next 在 target[k+1..k+3] 出现就算命中。CONTEXT_WINDOW=3 容忍 2 行无关插入
4. **再加两层减法**（针对 lingering 假阳性）：
   - **M1 · Move 检测**：同 patch 内 `+` 集合里的字面就不查 lingering —— 成员变量重排 / import 调序 / 方法移位不算漏删
   - **M2 · Scope 锚定**：hunk 头 `@@ ... @@ <xfuncname>` 拿到 scope（`type:X` / `method:name` / `select:id`）；target 命中位置回走找同种 structural 行，scope 对不上就跳过。Java 方法用 modifier + `(...)` + 排除 `if/for/while/catch` 之类正则识别

### 三明治 LLM prompt（HYBRID+MR、LLM+MR 两条路都用）

- `MrInfo`：source 分支名 + 文件改动列表
- `MrFileListFetcher.infoOf` 一次拿全，`CompareEngine.runOneTarget` 入口把所有 MR 的 source 分支收集起来一起 `git fetch`
- source 分支 fetch 用 `fetchBranchesBestEffort`（合并 → 逐个 fallback → 全 WARN 不抛），因为 MR merge 后 GitLab 常自动删源分支
- `LlmPromptBuilder.buildUserForThreeWayReview`：
  - patch（意图，8000 字符上限）
  - source 全文（"应该长成的样子"，带行号）
  - target 全文（"实际样子"，带行号）
  - 二者合计 ≤ 17000 字符 → 都塞全文；超了 → 各自降级到 patch @@ 位置 ±30 行窗口
  - source 拿不到（分支已删/API 失败）→ 透明退回 `buildUserForPatchReview`（patch + target 窗口）
- HYBRID 走这条时会追加"规则怀疑点 + AI 否决协议"；纯 LLM 模式 `ruleFindings=null`，不出现规则段（也不会让 LLM 编造"AI 否决"）
- `LlmClient.populateCitedSnippet` 解析 AI 评语里"目标分支第 N 行"/"源分支第 N~M 行"回填到 `baselineSnippet`/`targetSnippet`，滑窗遇到下一个"分支"关键字截止避免侧别串味儿
- 前端 detail 弹窗对 LLM finding 单独渲染"AI 引用的源/目标分支代码（带行号）"两块

### LLM JSON 截断救援

- Response 被 `max_tokens` 切断（默认 4096 → 提到 8192）
- `LlmClient.extractMessageContent` 读 `choice.finish_reason`；`length` 时走 `salvageTruncatedJson`
- 找最后一个完整 `}`，扫前缀里未闭合的 `{`/`[` 数量，按缺补齐。已完成的对象都保留，只丢截断的末条
- 字符串感知（`"` + `\` 转义），不会把字面量里的 `{` 也算进去

### 布局 / 前端 / P4 P5 小改（合到一起说）

**P4 · 钉钉真发**：
- `DingTalkSender`：HmacSHA256 加签（可选 `secret` 字段，`SchemaMigrator` 加列），sendText/sendMarkdown 双入口
- `CompareNotifier`：任务终态时组装 markdown（emoji+仓库+基准+模式+整体状态+各目标分支 err/warn/info 计数+错误消息预览+详情链接），受 `notify.message-max-chars` 截断
- 详情链接的 host 用 `publicHost()`：注入 `server.port`，配置只写 host（如 `10.0.80.123`）自动补 `:8080`，配了完整 URL 就原样用
- `NotificationWebhookService.sendTest` 也走 `DingTalkSender`，测试按钮同时验证签名

**P5.1 侧栏底部美化**：
- `sidebar-footer` + `sidebar-footer-link` 样式，两行 flat 布局，顶上分隔线
- 意见反馈 URL = `app.feedback-url` 配置，**空就不渲染**（不做默认 GitHub Issues 兜底）
- 退出按钮 hover 变红
- CSS 二级菜单缩进从 2.5rem → 3.25rem，特异性提到 6 层 class 才能压过 Tabler 自带的 5 层
- 侧栏加 `overflow-x: hidden` + `::-webkit-scrollbar` 自定义（thin/transparent）解决空轨占位

**P5.2 · MR "只看今天合并的"**：
- 创建任务页复选框 → `&todayOnly=true`
- `GitLabService.listRecentMergedMrs(repo, branch, limit, todayOnly)` 用 `MergeRequestFilter.withUpdatedAfter` 做服务端粗筛（gitlab4j 4.19 有这方法），本地按 `mergedAt` 严格过滤
- **时区必须用配置的**：`app.compare.timezone`（默认 `Asia/Shanghai`），不能用 `ZoneId.systemDefault()` —— 服务器 UTC + 用户北京 = 日切错 8 小时，会漏"今天早上"的 MR 或塞进"昨天下午"的 MR
- 日志 `[GITLAB] mr today-only zone=... cutoff=... raw=N kept=M dropped=K`，排查用

**P5.3 · 自定义被对比分支前缀**：
- 前缀输入框 + 对勾按钮，值存 `localStorage` key `compare.targetPrefix`（默认 `env-`）
- `renderTargets` 按当前前缀勾选，可点对勾重新按新前缀勾一遍不用重拉分支

### 代码启动模块（launch）几个关键修复

**commit sha 展示**：
- `task_module` 加 `commit_sha` + `commit_info` 两列，`SchemaMigrator` 幂等 ALTER
- `BuildLaunchService.readHeadInfo`：`git log -1 --pretty=format:%H|%h|%s|%an|%cr`，软失败
- 任务详情页分支列下方多一行 sha7 + 短 subject，`title=` 显示全 info

**git fetch 顽疾（这次踩到的最坑）**：
- 症状：**reuse workspace 拉不到新 commit，fresh clone 就正常**
- 根因：`git fetch origin <branch>` 裸 branch 名，只在本地 `.git/config` 的 `remote.origin.fetch` 有匹配 refspec 时才更新 `refs/remotes/origin/<branch>`；config 被清空或改窄了，就只更新 `FETCH_HEAD`，接下来 `reset --hard origin/<branch>` 用的还是**上次的 ref**
- 修法：`ensureRepoClone` 用显式 refspec `+refs/heads/<b>:refs/remotes/origin/<b>`，绕开 config
- 加了 `[GIT] origin/<b> before-fetch=... after-fetch=... remote-actual=...` 三态日志，出问题一 grep 就定位

**其它 launch 小改**：
- 启动超时默认 `startup-timeout-seconds: 600 → 300`（Nacos/重服务再手动加长）
- source 分支 `fetchBranchesBestEffort`（合并→逐个 fallback）应对 GitLab 自动删源分支

## 关于业务侧循环依赖

用户遇到过：launch 起业务服务时 `Bean with name 'asyncService' has been injected into other beans [xxxServiceImpl] in its raw version as part of a circular reference, but has eventually been wrapped`。**cicd-assistant 侧无解**，`-Dspring.main.allow-circular-references=true` 只放过第一步，@Async CGLIB 代理创建时 raw vs proxy 不一致仍会拒绝。**必须让业务在注 `AsyncService` 的字段上加 `@Lazy`**。文档里我给用户贴了 grep 命令批量定位。

## 当前状态

- P1 + P2 + P3 (含 MR patch verifier 多轮迭代 + 三明治 LLM prompt + JSON 截断救援) + P4 + P5 都已完成
- launch 模块加了 commit sha 显示 + fetch refspec 修复 + 超时默认改 300s
- 侧栏 2 级 collapse 早已修好
- 下一步没规划，等用户提

## 给新 agent 的工作守则

- **不要写测试代码**（MVP 阶段）
- **不要做用户权限分级 / 国际化 / Docker**
- **不要主动建文档**（除非用户明确要）—— 这份 HANDOFF.md 是例外
- 每个阶段一个独立 commit + push，方便用户回滚验收
- 编译 / 启动验证必须做一遍，至少打个 `/login` 200
- 大改动先和用户确认方案再动手，用户会说 "OK 干"
- 写代码用中文注释解释 WHY，不解释 WHAT
