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
