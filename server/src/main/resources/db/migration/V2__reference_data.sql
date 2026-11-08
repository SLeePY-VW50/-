INSERT INTO sys_role (role_code, role_name) VALUES
    ('STUDENT', '学生'),
    ('TEACHER', '任课教师'),
    ('LAB_ADMIN', '实验室管理员'),
    ('SYS_ADMIN', '系统管理员');

INSERT INTO sys_permission (perm_code, perm_name) VALUES
    ('user:read', '查询用户'),
    ('user:status:update', '修改用户状态'),
    ('resource:read', '查询公开资源'),
    ('reservation:read', '查询预约'),
    ('schedule:read', '查询排期结果'),
    ('stat:read', '查询统计');

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.role_id, p.perm_id
FROM sys_role r
JOIN sys_permission p ON p.perm_code IN ('user:read', 'user:status:update')
WHERE r.role_code = 'SYS_ADMIN';

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.role_id, p.perm_id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE p.perm_code IN ('resource:read', 'reservation:read');

INSERT INTO sys_role_permission (role_id, perm_id)
SELECT r.role_id, p.perm_id
FROM sys_role r
JOIN sys_permission p ON p.perm_code IN ('schedule:read', 'stat:read')
WHERE r.role_code IN ('LAB_ADMIN', 'SYS_ADMIN');

INSERT INTO t_sys_config
    (config_key, config_value, value_type, config_group, description)
VALUES
    ('credit.threshold', '60', 'INTEGER', 'credit', '预约准入信用分'),
    ('checkin.before.minutes', '15', 'INTEGER', 'checkin', '开始前可提前签到的分钟数'),
    ('checkin.grace.minutes', '15', 'INTEGER', 'checkin', '开始后的签到宽限分钟数'),
    ('checkin.choice.expire.seconds', '120', 'INTEGER', 'checkin', '多预约选择凭证有效秒数'),
    ('checkin.max.failed.attempts', '5', 'INTEGER', 'checkin', '连续认证失败上限'),
    ('checkin.lockout.minutes', '10', 'INTEGER', 'checkin', '认证失败锁定时长'),
    ('cancel.free.before.hours', '2', 'INTEGER', 'cancel', '免费取消的最小提前小时数'),
    ('reservation.duration.min.minutes', '30', 'INTEGER', 'reservation', '全局最短预约时长'),
    ('reservation.duration.max.minutes', '240', 'INTEGER', 'reservation', '全局最长预约时长'),
    ('reservation.advance.min.days', '1', 'INTEGER', 'reservation', '最少提前天数'),
    ('reservation.advance.max.days', '14', 'INTEGER', 'reservation', '最多提前天数'),
    ('schedule.primary.algorithm', 'GREEDY', 'STRING', 'schedule', '生产发布主算法'),
    ('schedule.batch.interval.minutes', '10', 'INTEGER', 'schedule', '自动排期批次执行间隔'),
    ('ai.provider', 'deepseek', 'STRING', 'ai', '大模型供应商标识'),
    ('ai.model', 'deepseek-chat', 'STRING', 'ai', '默认对话模型'),
    ('ai.temperature', '0.2', 'DECIMAL', 'ai', '模型采样温度'),
    ('ai.timeout.seconds', '30', 'INTEGER', 'ai', '单次模型调用超时秒数'),
    ('ai.max.tokens', '1024', 'INTEGER', 'ai', '单次回答最大输出token数'),
    ('ai.history.ttl.hours', '24', 'INTEGER', 'ai', 'Redis对话保留小时数'),
    ('ai.history.max.messages', '20', 'INTEGER', 'ai', '单线程最大消息数'),
    ('ai.cors.origins', '["http://localhost:5173"]', 'JSON', 'ai', 'AI接口允许的跨域来源');
