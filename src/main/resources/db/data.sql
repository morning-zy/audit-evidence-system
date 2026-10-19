-- =====================================================================
-- 审计证据管理系统 - 初始化数据
-- 说明: 密码统一为 123456，BCrypt 加密后的哈希见 sys_user
-- =====================================================================
-- 显式声明连接字符集，否则容器内初始化时按 latin1 解析，中文会双重编码。
-- 详见 schema.sql 开头的说明。
SET NAMES utf8mb4;

USE audit_evidence;

-- 部门
INSERT INTO sys_department (id, dept_code, dept_name, parent_id, leader_id, status) VALUES
(1, 'D001', '审计一部', 0, 2, 1),
(2, 'D002', '信息技术审计部', 0, 3, 1),
(3, 'D003', '风险管理部', 0, 4, 1);

-- 用户 (密码均为 123456)
-- BCrypt hash of "123456": $2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6
-- 注意: 此哈希已用 BCryptPasswordEncoder.matches() 实测校验通过
INSERT INTO sys_user (id, username, password, real_name, employee_no, role, dept_id, phone, status) VALUES
(1, 'admin',   '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '马宁',   'E1001', 'ADMIN',   1, '13800000001', 1),
(2, 'zhangsan','$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '张三',   'E1002', 'ADMIN',   1, '13800000002', 1),
(3, 'lisi',    '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '李四',   'E1003', 'AUDITOR', 2, '13800000003', 1),
(4, 'wangwu',  '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '王五',   'E1004', 'AUDITOR', 2, '13800000004', 1),
(5, 'zhaoliu', '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '赵六',   'E1005', 'AUDITOR', 1, '13800000005', 1),
(6, 'sunqi',   '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '孙七',   'E1006', 'VIEWER',  3, '13800000006', 1);

-- 三级复核制度对应的岗位账号（密码同为 123456）
-- 三级复核：详细复核(项目负责人) -> 一般复核(部门经理) -> 重点复核(主任会计师)
INSERT INTO sys_user (id, username, password, real_name, employee_no, role, dept_id, phone, status) VALUES
(7, 'leader',  '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '陈项目', 'E2001', 'LEADER',  1, '13800000007', 1),
(8, 'manager', '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '周部门', 'E2002', 'MANAGER', 1, '13800000008', 1),
(9, 'partner', '$2a$10$.QFuy4ZMXRauoJkpA1.On./T2hn/6yPDMmxp064D302vUEdY8iyM6', '吴主任', 'E3001', 'PARTNER', 1, '13800000009', 1);

UPDATE sys_department SET leader_id = 2 WHERE id = 1;

-- 审计项目
INSERT INTO audit_project (id, project_code, project_name, audit_type, audited_unit, leader_id, start_date, end_date, status, risk_level, description, create_by) VALUES
(1, 'AP2026001', '2026年度财务收支合规性审计',     'FINANCIAL',   '江苏华信实业有限公司',   2, '2026-09-01', '2026-11-30', 1, 3, '针对上一年度审计发现的存货盘点差异问题开展专项复核', 1),
(2, 'AP2026002', '核心业务系统信息安全审计',       'IT_SYSTEM',   '江苏华信实业有限公司',   3, '2026-09-15', '2026-12-15', 1, 3, '依据等保2.0三级要求，对核心业务系统的访问控制、日志审计、数据备份进行合规性检查', 1),
(3, 'AP2026003', '采购与付款循环内部控制审计',     'COMPLIANCE',  '华信供应链管理有限公司', 5, '2026-08-01', '2026-10-31', 2, 2, '重点检查供应商准入、比价采购、验收付款三分离控制的有效性', 1),
(4, 'AP2026004', '固定资产管理绩效审计',           'PERFORMANCE', '江苏华信实业有限公司',   5, '2026-10-01', '2026-12-31', 0, 1, '评估固定资产使用效率，识别闲置资产', 1);

-- 审计事项
INSERT INTO audit_issue (id, project_id, issue_no, issue_title, risk_point, auditor_id, status, evidence_count) VALUES
(1, 1, 'IS001-01', '存货账实差异核查',       '账面存货金额与实地盘点结果存在约12万元差异，可能存在存货计价或保管环节的舞弊风险', 2, 1, 1),
(2, 1, 'IS001-02', '营业收入截止性测试',     '存在跨期确认收入的风险，特别是12月末与大额合同的收入确认时点', 3, 1, 1),
(3, 1, 'IS001-03', '应收账款坏账准备计提',   '坏账准备计提比例是否符合企业会计政策，账龄划分是否准确', 4, 0, 0),
(4, 2, 'IS002-01', '用户权限分配合规性',     '存在离职员工账号未及时回收的风险，超级管理员账号共享使用', 3, 1, 1),
(5, 2, 'IS002-02', '数据库审计日志完整性',   '数据库操作日志保留期限不足6个月，不满足等保2.0三级要求', 4, 1, 1),
(6, 2, 'IS002-03', '数据备份与恢复演练',     '未见近一年内的数据恢复演练记录', 3, 0, 0),
(7, 3, 'IS003-01', '供应商准入资质审查',     '3家新增供应商缺少必要的资质文件归档', 4, 2, 1),
(8, 3, 'IS003-02', '采购比价流程执行情况',   '单笔金额超5万元的采购中有2笔未执行三家比价', 5, 1, 1);

-- 审计证据
INSERT INTO audit_evidence (id, evidence_no, project_id, issue_id, evidence_name, evidence_type, source, reliability, file_path, file_size, remark, status, submit_by, submit_time, review_by, review_time, review_comment) VALUES
(1, 'EV20260001', 1, 1, '2026年8月末存货盘点表(被审计单位盖章版)', 'DOCUMENT',     '仓储部提供',       3, '/upload/2026/10/ev_001.pdf', 158720, '盘点表已由仓储主管与财务人员双人签字确认', 2, 2, '2026-10-08 10:20:00', 1, '2026-10-09 09:15:00', '证据形式完整，可直接作为审计证据使用'),
(2, 'EV20260002', 1, 1, 'ERP存货明细账导出数据(2026年1-8月)',        'ELECTRONIC',   '财务系统导出',     3, '/upload/2026/10/ev_002.xlsx', 524288, '已核对导出日志，数据未被篡改', 2, 2, '2026-10-08 11:00:00', 1, '2026-10-09 09:20:00', '电子证据已做哈希校验，符合电子证据要求'),
(3, 'EV20260003', 1, 2, '销售合同台账及大额合同原件扫描件',          'DOCUMENT',     '销售管理部提供',   2, '/upload/2026/10/ev_003.zip', 3145728, '共涉及32份合同，其中超50万元的11份', 1, 3, '2026-10-12 14:30:00', NULL, NULL, NULL),
(4, 'EV20260004', 2, 4, '系统用户账号清单(2026年9月30日快照)',       'ELECTRONIC',   '运维中心提供',     3, '/upload/2026/10/ev_004.xlsx', 86432, '含全量账号共247个', 2, 3, '2026-10-10 16:00:00', 1, '2026-10-11 10:00:00', '数据完整'),
(5, 'EV20260005', 2, 4, '离职人员账号回收记录抽查访谈纪要',          'INTERVIEW',    '与运维主管访谈',   2, '/upload/2026/10/ev_005.docx', 45210, '抽查近6个月离职的15名员工', 3, 3, '2026-10-11 09:00:00', 1, '2026-10-12 15:30:00', '访谈纪要缺少被访谈人签字，需补充签字后重新提交'),
(6, 'EV20260006', 2, 5, '数据库操作审计日志配置截图与日志样本',       'OBSERVATION',  '现场观察取得',      3, '/upload/2026/10/ev_006.pdf', 892450, '确认日志保留策略为90天', 2, 4, '2026-10-13 10:00:00', 1, '2026-10-14 11:00:00', '现场观察记录清晰'),
(7, 'EV20260007', 3, 7, '新增供应商资质文件归档清单(Excel)',         'ANALYTICS',    '基于采购系统数据整理', 2, '/upload/2026/10/ev_007.xlsx', 128760, '共核查新增供应商42家', 2, 4, '2026-09-20 09:30:00', 2, '2026-09-22 14:00:00', '分析性复核过程清晰，结论有支撑'),
(8, 'EV20260008', 3, 8, '采购比价单抽样检查表',                     'DOCUMENT',     '采购部提供',       3, '/upload/2026/10/ev_008.pdf', 235100, '抽样检查近半年采购单共58笔', 0, 5, NULL, NULL, NULL, NULL);

-- 证据流转记录
INSERT INTO evidence_flow (evidence_id, action, from_status, to_status, operator_id, operator_name, comment, create_time) VALUES
(1, 'CREATE',  NULL, 0, 2, '张三', '创建证据',                          '2026-10-08 10:00:00'),
(1, 'SUBMIT',  0,    1, 2, '张三', '提交复核',                          '2026-10-08 10:20:00'),
(1, 'APPROVE', 1,    2, 1, '马宁', '证据形式完整，可直接作为审计证据使用', '2026-10-09 09:15:00'),
(2, 'CREATE',  NULL, 0, 2, '张三', '创建证据',                          '2026-10-08 10:50:00'),
(2, 'SUBMIT',  0,    1, 2, '张三', '提交复核',                          '2026-10-08 11:00:00'),
(2, 'APPROVE', 1,    2, 1, '马宁', '电子证据已做哈希校验，符合电子证据要求', '2026-10-09 09:20:00'),
(3, 'CREATE',  NULL, 0, 3, '李四', '创建证据',                          '2026-10-12 14:20:00'),
(3, 'SUBMIT',  0,    1, 3, '李四', '资料已齐备，提交主管复核',            '2026-10-12 14:30:00'),
(5, 'CREATE',  NULL, 0, 3, '李四', '创建证据',                          '2026-10-11 08:50:00'),
(5, 'SUBMIT',  0,    1, 3, '李四', '访谈已完成，提交复核',                '2026-10-11 09:00:00'),
(5, 'REJECT',  1,    3, 1, '马宁', '访谈纪要缺少被访谈人签字，需补充签字后重新提交', '2026-10-12 15:30:00');

SELECT '数据初始化完成' AS result,
       (SELECT COUNT(*) FROM sys_user) AS user_count,
       (SELECT COUNT(*) FROM audit_project) AS project_count,
       (SELECT COUNT(*) FROM audit_issue) AS issue_count,
       (SELECT COUNT(*) FROM audit_evidence) AS evidence_count;
