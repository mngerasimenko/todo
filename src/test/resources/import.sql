-- System user for reassigning tasks of deleted accounts
INSERT INTO todo_users (id, auth_id, email, password, name, created_task_color, completed_task_color, email_verified, created_at, version)
VALUES (0, 'system-deleted', 'deleted@system.local', '$2a$10$000000000000000000000uGhISe1rVFSqGGSm0pLdCNOJGOi6jjG', 'Удалённый пользователь', '#9E9E9E', '#9E9E9E', true, CURRENT_TIMESTAMP, 0);
