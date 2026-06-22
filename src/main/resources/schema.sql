CREATE TABLE IF NOT EXISTS repo (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    git_url TEXT NOT NULL,
    gitlab_host TEXT,
    project_path TEXT,
    auth_type TEXT NOT NULL DEFAULT 'TOKEN',
    access_token TEXT,
    username TEXT,
    password TEXT,
    branch_prefix TEXT,
    swagger_paths TEXT,
    actuator_path TEXT,
    jvm_args TEXT,
    spring_profile TEXT,
    modules TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE IF NOT EXISTS task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id INTEGER NOT NULL,
    repo_name TEXT,
    branches TEXT NOT NULL,
    modules TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at TEXT,
    started_at TEXT,
    finished_at TEXT
);

CREATE TABLE IF NOT EXISTS task_module (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL,
    branch TEXT NOT NULL,
    module_name TEXT NOT NULL,
    module_path TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    port INTEGER,
    pid INTEGER,
    pgid INTEGER,
    log_file TEXT,
    build_log_file TEXT,
    swagger_url TEXT,
    error_message TEXT,
    keep_alive_until TEXT,
    created_at TEXT,
    started_at TEXT,
    finished_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_task_module_task_id ON task_module(task_id);
CREATE INDEX IF NOT EXISTS idx_task_status ON task(status);
CREATE INDEX IF NOT EXISTS idx_task_module_status ON task_module(status);

-- ============================================================
-- 合并检测（merge verification）相关表
-- ============================================================

-- 一次对比任务（一个基准 vs 多个目标分支）
CREATE TABLE IF NOT EXISTS compare_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id INTEGER NOT NULL,
    repo_name TEXT,
    baseline_branch TEXT NOT NULL,
    target_branches TEXT NOT NULL,       -- CSV
    mr_selections TEXT NOT NULL,         -- JSON: [{iid, targetBranch}, ...]
    mode TEXT NOT NULL,                  -- RULE | LLM | HYBRID
    context_ids TEXT,                    -- CSV of compare_context ids
    webhook_id INTEGER,
    status TEXT NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    progress_total INTEGER DEFAULT 0,
    progress_done INTEGER DEFAULT 0,
    progress_phase TEXT,
    created_at TEXT,
    started_at TEXT,
    finished_at TEXT
);

-- 任务下每个目标分支的子结果
CREATE TABLE IF NOT EXISTS compare_target (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL,
    target_branch TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    error_count INTEGER NOT NULL DEFAULT 0,
    warn_count INTEGER NOT NULL DEFAULT 0,
    info_count INTEGER NOT NULL DEFAULT 0,
    files_scanned INTEGER NOT NULL DEFAULT 0,
    started_at TEXT,
    finished_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_compare_target_task ON compare_target(task_id);

-- 单条差异
CREATE TABLE IF NOT EXISTS compare_finding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_id INTEGER NOT NULL,
    mr_iid INTEGER,
    file_path TEXT NOT NULL,
    detector TEXT NOT NULL,              -- RULE | LLM
    type TEXT NOT NULL,
    severity TEXT NOT NULL,              -- ERROR | WARN | INFO
    summary TEXT,
    baseline_snippet TEXT,
    target_snippet TEXT,
    llm_comment TEXT,
    created_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_compare_finding_target ON compare_finding(target_id);

-- LLM 知识库
CREATE TABLE IF NOT EXISTS compare_context (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id INTEGER,                     -- NULL = 全局
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT,
    updated_at TEXT
);

-- 通知（钉钉等）webhook
CREATE TABLE IF NOT EXISTS notification_webhook (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT,
    updated_at TEXT
);
