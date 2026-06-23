# 交接文档：cicd-assistant

> 这份文档专门给接手开发的 AI agent 看。新会话开始时把这份贴进去，比让它重读全部历史聊天省 95% 上下文。

## 项目背景

代码合并自助验证系统。两大功能：

1. **代码启动**：拉分支 → mvn 编译 → java -jar 启动 → 探测 actuator/swagger，确认服务能起
2. **合并检测**（开发中）：MR 上线前，把基准分支（develop/uat）和被对比分支（env-*）做语义级 diff，输出报告推钉钉

仓库：`hytionking/cicd-assistant`，**所有开发都在分支 `claude/charming-dijkstra-8d8IU`**。

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

## 当前状态

- P1+P2+P3 + MR-patch 验证已完成
- 侧栏 2 级 collapse 已修好（之前用 dropdown-menu 嵌套 + 双 BS 监听都踩过）
- 下一步是 P4（钉钉真实推送）

## 给新 agent 的工作守则

- **不要写测试代码**（MVP 阶段）
- **不要做用户权限分级 / 国际化 / Docker**
- **不要主动建文档**（除非用户明确要）—— 这份 HANDOFF.md 是例外
- 每个阶段一个独立 commit + push，方便用户回滚验收
- 编译 / 启动验证必须做一遍，至少打个 `/login` 200
- 大改动先和用户确认方案再动手，用户会说 "OK 干"
- 写代码用中文注释解释 WHY，不解释 WHAT
