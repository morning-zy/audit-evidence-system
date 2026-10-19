-- =====================================================================
-- 审计证据管理系统 (Audit Evidence Management System) - 建表脚本
-- 数据库: audit_evidence
-- 字符集: utf8mb4 / 排序: utf8mb4_general_ci
-- =====================================================================

-- 必须在任何语句之前声明连接字符集。
-- 原因：MySQL 官方镜像首次启动时执行 /docker-entrypoint-initdb.d/ 下的脚本，
-- 客户端连接的默认字符集是 latin1（character_set_client=latin1）。
-- 若不声明，脚本里的 UTF-8 中文字节会被逐字按 latin1 解析后再以 utf8mb4 存储，
-- 造成双重编码 —— 「马宁」存成 C3A9C2A9C2AC... 而不是 E9A9ACE5AE81。
-- 本地用 Navicat/命令行导入时客户端字符集正确，所以这个坑只在容器里暴露。
SET NAMES utf8mb4;

DROP DATABASE IF EXISTS audit_evidence;
CREATE DATABASE audit_evidence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE audit_evidence;

-- ---------------------------------------------------------------------
-- 1. 部门表
-- ---------------------------------------------------------------------
CREATE TABLE sys_department (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dept_code     VARCHAR(32)  NOT NULL COMMENT '部门编码',
    dept_name     VARCHAR(64)  NOT NULL COMMENT '部门名称',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '上级部门ID, 0=顶级',
    leader_id     BIGINT       NULL COMMENT '负责人ID',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dept_code (dept_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '部门表';

-- ---------------------------------------------------------------------
-- 2. 用户表 (审计人员 / 被审计单位对接人)
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password      VARCHAR(128) NOT NULL COMMENT '密码(BCrypt)',
    real_name     VARCHAR(64)  NOT NULL COMMENT '真实姓名',
    employee_no   VARCHAR(32)  NULL COMMENT '工号',
    role          VARCHAR(32)  NOT NULL COMMENT '角色: ADMIN审计主管 AUDITOR审计员 VIEWER查阅人',
    dept_id       BIGINT       NULL COMMENT '所属部门ID',
    phone         VARCHAR(32)  NULL COMMENT '手机号',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0禁用',
    last_login    DATETIME     NULL COMMENT '最后登录时间',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_dept (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

-- ---------------------------------------------------------------------
-- 3. 审计项目表 (一个项目 = 一次独立的审计任务)
-- ---------------------------------------------------------------------
CREATE TABLE audit_project (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_code  VARCHAR(32)  NOT NULL COMMENT '项目编号',
    project_name  VARCHAR(128) NOT NULL COMMENT '项目名称',
    audit_type    VARCHAR(32)  NOT NULL COMMENT '审计类型: FINANCIAL财务 / IT_SYSTEM信息系统 / COMPLIANCE合规 / PERFORMANCE绩效',
    audited_unit  VARCHAR(128) NOT NULL COMMENT '被审计单位',
    leader_id     BIGINT       NOT NULL COMMENT '项目负责人ID',
    start_date    DATE         NULL COMMENT '计划开始日期',
    end_date      DATE         NULL COMMENT '计划结束日期',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0草稿 1进行中 2待复核 3已归档',
    risk_level    TINYINT      NOT NULL DEFAULT 1 COMMENT '风险等级 1低 2中 3高',
    description   VARCHAR(512) NULL COMMENT '项目描述',
    create_by     BIGINT       NULL COMMENT '创建人',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (project_code),
    KEY idx_status (status),
    KEY idx_leader (leader_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计项目表';

-- ---------------------------------------------------------------------
-- 4. 审计事项表 (项目下的具体审计点, 是证据的挂载容器)
-- ---------------------------------------------------------------------
CREATE TABLE audit_issue (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id      BIGINT       NOT NULL COMMENT '所属项目ID',
    issue_no        VARCHAR(32)  NOT NULL COMMENT '事项编号',
    issue_title     VARCHAR(128) NOT NULL COMMENT '事项标题',
    risk_point      VARCHAR(512) NULL COMMENT '风险点描述',
    auditor_id      BIGINT       NOT NULL COMMENT '承办审计员ID',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0待执行 1证据收集中 2已完成',
    evidence_count  INT          NOT NULL DEFAULT 0 COMMENT '证据数量(冗余字段)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_issue_no (issue_no),
    KEY idx_project (project_id),
    KEY idx_auditor (auditor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计事项表';

-- ---------------------------------------------------------------------
-- 5. 审计证据表 (核心业务表, 一条证据 = 一份审计证据材料)
-- ---------------------------------------------------------------------
CREATE TABLE audit_evidence (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    evidence_no     VARCHAR(32)  NOT NULL COMMENT '证据编号',
    project_id      BIGINT       NOT NULL COMMENT '所属项目ID',
    issue_id        BIGINT       NOT NULL COMMENT '所属事项ID',
    evidence_name   VARCHAR(128) NOT NULL COMMENT '证据名称',
    evidence_type   VARCHAR(32)  NOT NULL COMMENT '证据类型: DOCUMENT书证 / ELECTRONIC电子数据 / INTERVIEW访谈记录 / OBSERVATION现场观察 / ANALYTICS分析性复核',
    source          VARCHAR(128) NULL COMMENT '证据来源',
    reliability     TINYINT      NOT NULL DEFAULT 2 COMMENT '可靠性等级 1低(D) 2中(C) 3高(B) 4很高(A)，由系统按准则计算',

    -- 以下五列对应《审计准则第1301号——审计证据》第三条的可靠性五原则
    source_type       VARCHAR(16)  NULL COMMENT '来源 EXTERNAL外部独立 INTERNAL内部生成 MANAGEMENT管理层声明',
    acquire_method    VARCHAR(16)  NULL COMMENT '获取方式 DIRECT直接获取 INDIRECT被审计单位提供 INFERRED推算',
    form_type         VARCHAR(16)  NULL COMMENT '存在形式 WRITTEN书面 ELECTRONIC电子 ORAL口头',
    originality       VARCHAR(16)  NULL COMMENT '原件性 ORIGINAL原件 SCANNED扫描件 COPY复印件',
    control_effective TINYINT(1)   NULL COMMENT '内部控制是否有效，仅对内部来源生效',
    reliability_basis VARCHAR(512) NULL COMMENT '可靠性判定依据，供复核人追溯',
    file_path       VARCHAR(512) NULL COMMENT '文件存储路径',
    file_size       BIGINT       NULL COMMENT '文件大小(字节)',
    remark          VARCHAR(512) NULL COMMENT '备注',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0草稿 1待详细复核 2已确认 3已驳回 4待一般复核 5待重点复核',
    current_level   TINYINT      NOT NULL DEFAULT 0 COMMENT '当前复核级别 0未提交 1待详细复核 2待一般复核 3待重点复核 4已完成三级',
    submit_by       BIGINT       NULL COMMENT '提交人ID',
    submit_time     DATETIME     NULL COMMENT '提交时间',
    review_by       BIGINT       NULL COMMENT '复核人ID',
    review_time     DATETIME     NULL COMMENT '复核时间',
    review_comment  VARCHAR(512) NULL COMMENT '复核意见',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_no (evidence_no),
    KEY idx_project (project_id),
    KEY idx_issue (issue_id),
    KEY idx_status (status),
    KEY idx_submit_by (submit_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计证据表';

-- ---------------------------------------------------------------------
-- 6. 证据流转记录表 (审计留痕的核心, 支撑事后追溯)
-- ---------------------------------------------------------------------
CREATE TABLE evidence_flow (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    evidence_id   BIGINT       NOT NULL COMMENT '证据ID',
    action        VARCHAR(32)  NOT NULL COMMENT '动作: CREATE创建 SUBMIT提交 APPROVE_L1/L2/L3分级通过 REJECT_L1/L2/L3分级驳回',
    from_status   TINYINT      NULL COMMENT '变更前状态',
    to_status     TINYINT      NOT NULL COMMENT '变更后状态',
    review_level  TINYINT      NULL COMMENT '本次操作所属复核级别 1详细 2一般 3重点, NULL=非复核动作',
    operator_id   BIGINT       NOT NULL COMMENT '操作人ID',
    operator_name VARCHAR(64)  NOT NULL COMMENT '操作人姓名(冗余, 防止用户改名后追溯失真)',
    comment       VARCHAR(512) NULL COMMENT '意见/说明',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_evidence (evidence_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '证据流转记录表';

-- ---------------------------------------------------------------------
-- 7. 操作日志表 (AOP 切面写入, 系统级审计)
-- ---------------------------------------------------------------------
CREATE TABLE operation_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    module        VARCHAR(64)  NULL COMMENT '模块名',
    operation     VARCHAR(64)  NULL COMMENT '操作描述',
    method        VARCHAR(128) NULL COMMENT '方法签名',
    request_uri   VARCHAR(256) NULL COMMENT '请求路径',
    request_method VARCHAR(16) NULL COMMENT '请求方式',
    params        TEXT         NULL COMMENT '请求参数',
    result        TEXT         NULL COMMENT '返回结果(截断)',
    operator_id   BIGINT       NULL COMMENT '操作人ID',
    operator_name VARCHAR(64)  NULL COMMENT '操作人姓名',
    ip            VARCHAR(64)  NULL COMMENT '客户端IP',
    cost_ms       BIGINT       NULL COMMENT '耗时(毫秒)',
    success       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功',
    error_msg     VARCHAR(1024) NULL COMMENT '异常信息',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_operator (operator_id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';

-- ---------------------------------------------------------------------
-- 8. 审计抽样计划表 (依据《审计准则第1314号——审计抽样》)
--
-- 设计要点：这张表不只存"抽了几个"，还存"为什么抽这么几个"和"结论怎么来的"。
-- 审计底稿的核心要求是可追溯 —— 复核人必须能看懂样本量的计算依据，
-- 所以 basis 和 conclusion_text 两列是必填的业务字段，不是日志。
-- ---------------------------------------------------------------------
CREATE TABLE audit_sampling (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    sampling_no        VARCHAR(32)  NOT NULL COMMENT '抽样计划编号',
    project_id         BIGINT       NOT NULL COMMENT '所属项目ID',
    issue_id           BIGINT       NULL COMMENT '关联审计事项ID',
    control_name       VARCHAR(128) NOT NULL COMMENT '被测试的内部控制名称',

    population_size    INT          NOT NULL COMMENT '总体规模(本期该控制涉及的全部凭证/交易笔数)',
    tolerable_rate     DECIMAL(6,4) NOT NULL COMMENT '可容忍偏差率, 如 0.0500=5%',
    expected_rate      DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '预期偏差率, 依据历史数据或小规模测试',
    confidence_level   VARCHAR(16)  NOT NULL DEFAULT 'NINETY_FIVE' COMMENT '置信水平 NINETY_FIVE=95% / NINETY=90%',
    method             VARCHAR(16)  NOT NULL DEFAULT 'STATISTICAL' COMMENT '抽样方法 STATISTICAL统计 / JUDGMENTAL判断',

    sample_size        INT          NOT NULL COMMENT '应抽取样本量(由二项分布反解)',
    allowed_deviations INT          NOT NULL DEFAULT 0 COMMENT '允许偏差数, 超出即判定控制不可信赖',
    is_census          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否退化为全查(总体小于所需样本量)',

    checked_count      INT          NOT NULL DEFAULT 0 COMMENT '已实际检查的样本数',
    deviation_count    INT          NOT NULL DEFAULT 0 COMMENT '已发现的偏差数',
    deviation_rate     DECIMAL(6,4) NULL COMMENT '样本偏差率(点估计)',
    upper_limit        DECIMAL(6,4) NULL COMMENT '总体偏差率上限(单侧置信上限)',

    conclusion         TINYINT      NOT NULL DEFAULT 0 COMMENT '结论 0未执行 1控制可信赖 2控制不可信赖',
    basis              VARCHAR(512) NULL COMMENT '计算依据, 直接打印进底稿',
    conclusion_text    VARCHAR(512) NULL COMMENT '结论说明, 含上限与可容忍率的比较',

    create_by          BIGINT       NULL COMMENT '创建人ID',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sampling_no (sampling_no),
    KEY idx_project (project_id),
    KEY idx_issue (issue_id),
    KEY idx_conclusion (conclusion)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计抽样计划表';

-- ---------------------------------------------------------------------
-- 9. 抽样样本明细表
--
-- 每个样本一行, 记录抽到了哪张凭证、检查和结论。
-- deviation 存的是"这个样本有没有偏差", 不是"这个样本有没有问题" ——
-- 偏差指偏离内部控制要求(比如缺少审批签字), 而不是金额错误本身。
-- 两者的区别在审计上很重要: 控制测试测的是"控制有没有被执行",
-- 实质性程序测的才是"金额对不对"。混在一起会让结论失去依据。
-- ---------------------------------------------------------------------
CREATE TABLE audit_sampling_item (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    sampling_id     BIGINT        NOT NULL COMMENT '所属抽样计划ID',
    seq_no          INT           NOT NULL COMMENT '样本序号(从1开始)',
    voucher_no      VARCHAR(64)   NOT NULL COMMENT '凭证号/单据号',
    voucher_date    DATE          NULL COMMENT '凭证日期',
    amount          DECIMAL(18,2) NULL COMMENT '金额',
    -- 三态而非布尔：1 有偏差 / 0 无偏差 / NULL 抽到了但还没检查。
    -- 所以这里必须允许 NULL —— 用 NOT NULL DEFAULT 0 会把"未检查"压成"无偏差"，
    -- 已检查数和偏差数会被虚增，抽样结论直接失真。
    deviation       TINYINT(1)    NULL DEFAULT NULL COMMENT '是否存在偏差: 1有偏差 0无偏差 NULL未检查',
    deviation_desc  VARCHAR(512)  NULL COMMENT '偏差描述, 有偏差时必填',
    checked_time    DATETIME      NULL COMMENT '检查时间, NULL=尚未检查',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_sampling (sampling_id),
    KEY idx_voucher (voucher_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽样样本明细表';

-- ---------------------------------------------------------------------
-- 10. 变量抽样计划表（细节测试）
--
-- 与 audit_sampling 的关系：那张表测"控制有没有被执行"，结论是偏差率；
-- 这张表测"金额对不对"，结论是错报金额。两者都叫抽样，但推断的对象不同，
-- 所以没有合并 —— 合并会让每个字段都允许为空，最后没人说得清某个字段归哪套逻辑。
--
-- 金额字段一律 DECIMAL(18,2)：报告上的数要能和企业账面逐分对平。
-- double 存 1234567.89 会变成 1234567.8899999999，累加几万条后差异肉眼可见。
-- ---------------------------------------------------------------------
CREATE TABLE audit_variable_sampling (
    id                       BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    sampling_no              VARCHAR(32)    NOT NULL COMMENT '计划编号 VS+年份+4位流水',
    project_id               BIGINT         NOT NULL COMMENT '所属审计项目ID',
    issue_id                 BIGINT         NULL COMMENT '关联审计事项ID',
    account_name             VARCHAR(128)   NOT NULL COMMENT '被测试科目/项目名称',

    -- 总体与抽样参数：决定样本量的全部输入，必须落库，否则两年后复现不出这个结论
    population_book_value    DECIMAL(18,2)  NOT NULL COMMENT '总体账面金额',
    population_size          INT            NOT NULL COMMENT '总体项数',
    tolerable_misstatement   DECIMAL(18,2)  NOT NULL COMMENT '可容忍错报',
    expected_misstatement    DECIMAL(18,2)  NOT NULL DEFAULT 0 COMMENT '预期错报(仅参考)',
    assumed_std_dev          DECIMAL(18,2)  NULL COMMENT '预估单笔标准差, NULL=先做试点',
    confidence_level         VARCHAR(32)    NOT NULL DEFAULT 'NINETY_FIVE' COMMENT '置信水平',
    method                   VARCHAR(32)    NOT NULL DEFAULT 'DIFFERENCE' COMMENT 'MEAN_PER_UNIT/DIFFERENCE/RATIO',

    -- 抽样结果
    sample_size              INT            NOT NULL COMMENT '应抽取样本量',
    is_pilot                 TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否试点阶段',
    checked_count            INT            NOT NULL DEFAULT 0 COMMENT '已检查样本数',
    projected_misstatement   DECIMAL(18,2)  NULL COMMENT '估计总体错报(点估计)',
    precision_amount         DECIMAL(18,2)  NULL COMMENT '抽样风险允许度(精度)',
    upper_misstatement       DECIMAL(18,2)  NULL COMMENT '错报上限=点估计+精度',
    sample_std_dev           DECIMAL(18,2)  NULL COMMENT '样本标准差(留痕)',

    conclusion               INT            NOT NULL DEFAULT 0 COMMENT '0未评价 1可接受 2不可接受',
    basis                    VARCHAR(1024)  NULL COMMENT '样本量计算依据',
    conclusion_text          VARCHAR(1024)  NULL COMMENT '结论说明',
    create_by                BIGINT         NULL COMMENT '创建人',
    create_time              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                  TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 编号唯一索引：即使逻辑删除，编号也永久占用，不允许复用（底稿留痕要求）
    UNIQUE KEY uk_vs_no (sampling_no),
    KEY idx_vs_project (project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '变量抽样计划表';

-- ---------------------------------------------------------------------
-- 11. 变量抽样样本明细表
--
-- audited_value 允许为 NULL，代表"抽到了但还没检查"。
-- 这是三态语义：NULL 未检查 / 有值已检查。不能压成 0 ——
-- 0 表示"审定金额为零"，在计算里就是一条全额错报，错报上限会直接爆表。
-- ---------------------------------------------------------------------
CREATE TABLE audit_variable_sampling_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    sampling_id    BIGINT        NOT NULL COMMENT '所属变量抽样计划ID',
    seq_no         INT           NOT NULL COMMENT '样本序号(从1开始)',
    voucher_no     VARCHAR(64)   NOT NULL COMMENT '凭证号/单据号',
    voucher_date   DATE          NULL COMMENT '凭证日期',
    book_value     DECIMAL(18,2) NOT NULL COMMENT '账面金额',
    audited_value  DECIMAL(18,2) NULL COMMENT '审定金额, NULL=尚未检查',
    -- 错报 = 账面 - 审定：正数高估、负数低估，方向必须保留
    misstatement   DECIMAL(18,2) NULL COMMENT '错报金额, NULL=尚未检查',
    remark         VARCHAR(512)  NULL COMMENT '检查说明/错报原因',
    checked_time   DATETIME      NULL COMMENT '检查时间, NULL=尚未检查',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_vs_item (sampling_id),
    KEY idx_vs_voucher (voucher_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '变量抽样样本明细表';
