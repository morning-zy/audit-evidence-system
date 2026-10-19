package com.maning.audit.common.constant;

import lombok.Getter;

/**
 * 业务枚举集中定义
 *
 * <p>为什么不直接在代码里写 0/1/2：
 * ① 可读性 —— status == 2 和 status == EvidenceStatus.CONFIRMED.getCode() 差一个量级
 * ② 可维护 —— 状态机规则全部集中在这里，改一处全局生效
 * ③ 可防御 —— 前端传非法值时有统一入口校验
 */
public class Enums {

    /* ==================== 用户角色 ==================== */
    /**
     * 用户角色。
     *
     * <p>三级复核制度要求复核权按职级分层，所以这里不是"权限大小"的线性关系，
     * 而是"岗位对应层级"：
     * <pre>
     * AUDITOR  审计员     —— 编制底稿，无复核权
     * LEADER   项目负责人 —— 一级复核（详细复核），逐张看全部底稿
     * MANAGER  部门经理   —— 二级复核（一般复核），看重要账项与调整
     * PARTNER  主任会计师 —— 三级复核（重点复核），看重大审计问题
     * ADMIN    审计主管   —— 系统管理角色，可代行任一复核级别（用于演示与兜底）
     * VIEWER   查阅人     —— 只读
     * </pre>
     *
     * <p>为什么保留 ADMIN 可代行：真实事务所里主任会计师不会天天点系统，
     * 但演示和故障处置需要有人能推进流程。这个"特权"必须在代码里写清楚，
     * 而不是让它变成一个含糊的"管理员啥都能干"。
     */
    @Getter
    public enum Role {
        ADMIN("ADMIN", "审计主管"),
        AUDITOR("AUDITOR", "审计员"),
        LEADER("LEADER", "项目负责人"),
        MANAGER("MANAGER", "部门经理"),
        PARTNER("PARTNER", "主任会计师"),
        VIEWER("VIEWER", "查阅人");

        private final String code;
        private final String desc;

        Role(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static boolean isValid(String code) {
            for (Role r : values()) {
                if (r.code.equals(code)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 该角色对应哪一级复核权。返回 null 表示无复核权。
         *
         * <p>放进枚举而不是散落在 Service 里，是为了让"谁能复核第几级"
         * 只有一处定义 —— 以后要加"质量复核合伙人"这类角色，改这里就行。
         */
        public Integer reviewLevelOf() {
            return switch (this) {
                case LEADER -> ReviewLevel.DETAIL.getCode();
                case MANAGER -> ReviewLevel.GENERAL.getCode();
                case PARTNER -> ReviewLevel.KEY.getCode();
                default -> null;  // AUDITOR 编制、VIEWER 只读，都没有复核权
            };
        }
    }

    /* ==================== 项目状态 ==================== */
    @Getter
    public enum ProjectStatus {
        DRAFT(0, "草稿"),
        IN_PROGRESS(1, "进行中"),
        REVIEWING(2, "待复核"),
        ARCHIVED(3, "已归档");

        private final Integer code;
        private final String desc;

        ProjectStatus(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(Integer code) {
            for (ProjectStatus s : values()) {
                if (s.code.equals(code)) {
                    return s.desc;
                }
            }
            return "未知";
        }
    }

    /* ==================== 事项状态 ==================== */
    @Getter
    public enum IssueStatus {
        PENDING(0, "待执行"),
        COLLECTING(1, "证据收集中"),
        COMPLETED(2, "已完成");

        private final Integer code;
        private final String desc;

        IssueStatus(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(Integer code) {
            for (IssueStatus s : values()) {
                if (s.code.equals(code)) {
                    return s.desc;
                }
            }
            return "未知";
        }
    }

    /**
     * 证据状态 —— 本项目的状态机核心
     *
     * <pre>
     *   草稿(0) --提交--> 待复核(1) --通过--> 已确认(2)
     *                        |
     *                        +--驳回--> 已驳回(3) --重新提交--> 待复核(1)
     * </pre>
     */
    @Getter
    /**
     * 证据状态（含三级复核流转）。
     *
     * <p>完整生命周期：
     * <pre>
     *   草稿(0) ──提交──► 待详细复核(1) ──通过──► 待一般复核(4) ──通过──► 待重点复核(5) ──通过──► 已确认(2)
     *                        │                      │                      │
     *                        └──── 驳回 ────────────┴──────────────────────┴──► 已驳回(3)
     *                                                                             │
     *   草稿(0) ◄──────────────────────────── 编制人修改后重新提交 ───────────────┘
     * </pre>
     *
     * <p>为什么用 4、5 而不是重新编号成 1/2/3/4：
     * 现有数据库里 2=已确认、3=已驳回 已经被 8 条真实数据使用，
     * 重新编号要迁移数据、改所有前端字典，风险大于收益。
     * 新增两个"待复核"中间态用 4、5，语义清晰且零迁移成本 ——
     * 这是兼容性优先的取舍，代价是状态码不连续，需要在枚举里写清楚。
     *
     * <p>另一个设计点：<b>状态里不体现"是谁在审"</b>。
     * 谁审由 currentLevel + 流转记录决定，状态只说"流程走到哪"。
     * 如果把"张三在审"写进状态，张三休假就得改状态，流程和人员耦合，必然后患无穷。
     */
    public enum EvidenceStatus {
        DRAFT(0, "草稿", 0),
        PENDING_REVIEW(1, "待详细复核", ReviewLevel.DETAIL.getCode()),
        CONFIRMED(2, "已确认", 0),
        REJECTED(3, "已驳回", 0),
        PENDING_GENERAL(4, "待一般复核", ReviewLevel.GENERAL.getCode()),
        PENDING_KEY(5, "待重点复核", ReviewLevel.KEY.getCode());

        private final Integer code;
        private final String desc;
        /** 该状态对应的复核级别；0 表示不在复核中 */
        private final Integer reviewLevel;

        EvidenceStatus(Integer code, String desc, Integer reviewLevel) {
            this.code = code;
            this.desc = desc;
            this.reviewLevel = reviewLevel;
        }

        public static String descOf(Integer code) {
            for (EvidenceStatus s : values()) {
                if (s.code.equals(code)) {
                    return s.desc;
                }
            }
            return "未知";
        }

        /** 是否处于复核流程中（需要某级复核人处理） */
        public static boolean isReviewing(Integer code) {
            return levelOf(code) > 0;
        }

        /** 该状态对应第几级复核；不在复核中返回 0 */
        public static int levelOf(Integer code) {
            for (EvidenceStatus s : values()) {
                if (s.code.equals(code)) {
                    return s.reviewLevel == null ? 0 : s.reviewLevel;
                }
            }
            return 0;
        }

        /** 按复核级别取对应的"待复核"状态 —— 通过后往下一级推进时用 */
        public static EvidenceStatus pendingOf(int level) {
            return switch (level) {
                case 1 -> PENDING_REVIEW;
                case 2 -> PENDING_GENERAL;
                case 3 -> PENDING_KEY;
                default -> throw new IllegalArgumentException("非法复核级别: " + level);
            };
        }
    }

    /* ==================== 证据类型（审计准则分类） ==================== */
    @Getter
    public enum EvidenceType {
        DOCUMENT("DOCUMENT", "书证"),
        ELECTRONIC("ELECTRONIC", "电子数据"),
        INTERVIEW("INTERVIEW", "访谈记录"),
        OBSERVATION("OBSERVATION", "现场观察"),
        ANALYTICS("ANALYTICS", "分析性复核");

        private final String code;
        private final String desc;

        EvidenceType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(String code) {
            for (EvidenceType t : values()) {
                if (t.code.equals(code)) {
                    return t.desc;
                }
            }
            return "未知";
        }

        public static boolean isValid(String code) {
            for (EvidenceType t : values()) {
                if (t.code.equals(code)) {
                    return true;
                }
            }
            return false;
        }
    }

    /* ==================== 可靠性等级 ==================== */
    @Getter
    public enum Reliability {
        LOW(1, "低", "D"),
        MEDIUM(2, "中", "C"),
        HIGH(3, "高", "B"),
        VERY_HIGH(4, "很高", "A");

        private final Integer code;
        private final String desc;
        /** 审计底稿上的字母等级，方便和纸质底稿对照 */
        private final String grade;

        Reliability(Integer code, String desc, String grade) {
            this.code = code;
            this.desc = desc;
            this.grade = grade;
        }

        public static String descOf(Integer code) {
            for (Reliability r : values()) {
                if (r.code.equals(code)) {
                    return r.desc;
                }
            }
            return "未知";
        }

        /** 等级越高越可靠，用于比较 */
        public boolean atLeast(Reliability other) {
            return this.code >= other.code;
        }
    }

    /*
     * ==================== 审计证据可靠性维度 ====================
     *
     * 《中国注册会计师审计准则第 1301 号——审计证据》第三条列出了
     * 判断审计证据可靠性的五条一般原则。下面五个枚举就是把这五条原则
     * 翻译成可存储、可计算的字段 —— 而不是把准则条文抄进注释然后什么都不做。
     *
     * 之所以要拆成五个维度而不是让人填一个"可靠性高中低"：
     * 1) 审计底稿要求可追溯。复核人必须知道"为什么这条证据评 B 级"，
     *    五个维度就是判定依据，一条都不能少。
     * 2) 同一条证据在不同维度上可能优劣相反（比如内部生成但为原件的报表），
     *    单一评分无法表达这种差异，会让复核人失去判断依据。
     * 3) 拆分后可以做成规则引擎，未来接入不同事务所的底稿标准只需换权重，
     *    不用改代码结构。
     */

    /**
     * 来源维度 —— 准则 1301 号 第三条（一）、（二）
     *
     * <p>"从被审计单位外部独立来源获取的审计证据比从其他来源获取的更可靠"
     * <br>"相关控制有效时内部生成的证据比控制薄弱时内部生成的更可靠"
     *
     * <p>注意第三条（二）讲的是"控制是否有效"，这是个独立条件，
     * 所以内部证据还要看 controlEffective 字段，不能只看 sourceType。
     */
    @Getter
    public enum EvidenceSource {
        /** 外部独立来源：银行函证回函、供应商对账单、第三方检测报告 */
        EXTERNAL(3, "外部独立来源"),
        /** 被审计单位内部生成：财务系统导出的明细账、内部审批单 */
        INTERNAL(2, "被审计单位内部生成"),
        /** 仅管理层声明或询问答复，未经其他证据印证 */
        MANAGEMENT(1, "管理层声明/口头答复");

        private final int score;
        private final String desc;

        EvidenceSource(int score, String desc) {
            this.score = score;
            this.desc = desc;
        }

        public static String descOf(String name) {
            for (EvidenceSource s : values()) {
                if (s.name().equals(name)) {
                    return s.desc;
                }
            }
            return "未填写";
        }
    }

    /**
     * 获取方式维度 —— 准则 1301 号 第三条（三）
     *
     * <p>"直接获取的审计证据比间接获取或推论得出的审计证据更可靠"
     */
    @Getter
    public enum AcquireMethod {
        /** 审计人员亲自检查、观察、盘点取得的 */
        DIRECT(3, "审计人员直接获取"),
        /** 被审计单位提供，审计人员未见证取得过程 */
        INDIRECT(2, "被审计单位提供"),
        /** 由其他证据推算得出，非直接观察 */
        INFERRED(1, "推算/推论得出");

        private final int score;
        private final String desc;

        AcquireMethod(int score, String desc) {
            this.score = score;
            this.desc = desc;
        }

        public static String descOf(String name) {
            for (AcquireMethod m : values()) {
                if (m.name().equals(name)) {
                    return m.desc;
                }
            }
            return "未填写";
        }
    }

    /**
     * 存在形式维度 —— 准则 1301 号 第三条（四）
     *
     * <p>"以文件、记录形式（无论纸质、电子或其他介质）存在的审计证据
     * 比口头形式的审计证据更可靠"
     */
    @Getter
    public enum EvidenceForm {
        /** 书面文件：合同、发票、审批单 */
        WRITTEN(3, "书面文件"),
        /** 电子记录：系统截图、电子台账、邮件 */
        ELECTRONIC(2, "电子记录"),
        /** 口头说明：访谈记录、电话确认 */
        ORAL(1, "口头说明");

        private final int score;
        private final String desc;

        EvidenceForm(int score, String desc) {
            this.score = score;
            this.desc = desc;
        }

        public static String descOf(String name) {
            for (EvidenceForm f : values()) {
                if (f.name().equals(name)) {
                    return f.desc;
                }
            }
            return "未填写";
        }
    }

    /**
     * 原件性维度 —— 准则 1301 号 第三条（五）
     *
     * <p>"从原件获取的审计证据比从复印、传真或通过拍摄、数字化
     * 或其他方式转化成电子形式的文件获取的审计证据更可靠"
     */
    @Getter
    public enum Originality {
        /** 原件：纸质原件或系统原始记录 */
        ORIGINAL(3, "原件"),
        /** 扫描件/数字化件：原件扫描后存档 */
        SCANNED(2, "扫描件"),
        /** 复印件/传真件/照片 */
        COPY(1, "复印件");

        private final int score;
        private final String desc;

        Originality(int score, String desc) {
            this.score = score;
            this.desc = desc;
        }

        public static String descOf(String name) {
            for (Originality o : values()) {
                if (o.name().equals(name)) {
                    return o.desc;
                }
            }
            return "未填写";
        }
    }

    /**
     * 三级复核层级 —— 依据《审计工作底稿》三级复核制度
     *
     * <p>制度原文（财政部《关于加快发展我国注册会计师行业的若干意见》配套解读）：
     * "扎实抓好项目负责人的全面复核、部门经理的一般复核、主任会计师的重点复核
     * 等三级复核制度，确保每一级复核分工明确、要求具体、监督到位，
     * 避免图形式、走过场。"
     *
     * <p>三级复核的复核范围是收敛的（越往后复核的东西越少但越重要），
     * 这也是系统里"一级复核必须全量、三级复核只抽重点"的根据。
     */
    @Getter
    public enum ReviewLevel {
        /** 一级：项目负责人详细复核，逐张复核全部底稿 */
        DETAIL(1, "详细复核", "项目负责人逐张复核全部底稿"),
        /** 二级：部门经理一般复核，复核重要会计账项与调整事项 */
        GENERAL(2, "一般复核", "部门经理复核重要账项与调整事项"),
        /** 三级：主任会计师重点复核，复核重大审计问题 */
        KEY(3, "重点复核", "主任会计师复核重大审计问题");

        private final Integer code;
        private final String desc;
        private final String scope;

        ReviewLevel(Integer code, String desc, String scope) {
            this.code = code;
            this.desc = desc;
            this.scope = scope;
        }

        public static String descOf(Integer code) {
            for (ReviewLevel l : values()) {
                if (l.code.equals(code)) {
                    return l.desc;
                }
            }
            return "未知";
        }
    }

    /* ==================== 流转动作 ==================== */
    /**
     * 流转动作。
     *
     * <p>三级复核下，"通过"和"驳回"各需要三个动作值，否则流转记录里
     * 只能看到"APPROVE 通过"却不知道是第几级通过的 —— 追溯时这就废了。
     *
     * <p>但只存动作不够：动作是"做了什么"，级别是"在哪一级做的"。
     * 两者都存（action + reviewLevel）虽然冗余，换来的是
     * 单条记录自解释 —— 不用联表推断就能回答"谁在第一级驳回了什么"。
     */
    @Getter
    public enum FlowAction {
        CREATE("CREATE", "创建"),
        SUBMIT("SUBMIT", "提交复核"),

        APPROVE_L1("APPROVE_L1", "详细复核通过"),
        APPROVE_L2("APPROVE_L2", "一般复核通过"),
        APPROVE_L3("APPROVE_L3", "重点复核通过"),

        REJECT_L1("REJECT_L1", "详细复核驳回"),
        REJECT_L2("REJECT_L2", "一般复核驳回"),
        REJECT_L3("REJECT_L3", "重点复核驳回");

        private final String code;
        private final String desc;

        FlowAction(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(String code) {
            for (FlowAction a : values()) {
                if (a.code.equals(code)) {
                    return a.desc;
                }
            }
            // 兼容三级复核改造前的历史数据。
            //
            // 改造前存的是 APPROVE / REJECT（单级复核），那时没有级别概念。
            // 这里有两条路：① 把历史数据处理成 APPROVE_L3 ② 保留原值并如实标注。
            // 选②，因为审计系统的历史记录不允许被改写 —— 当时就是单级复核，
            // 硬改成"三级复核通过"等于伪造底稿。宁可显示得别扭一点，也不能失真。
            if ("APPROVE".equals(code)) {
                return "复核通过（旧版单级）";
            }
            if ("REJECT".equals(code)) {
                return "复核驳回（旧版单级）";
            }
            return code;
        }

        /** 某级复核通过对应的动作 */
        public static FlowAction approveOf(int level) {
            return switch (level) {
                case 1 -> APPROVE_L1;
                case 2 -> APPROVE_L2;
                case 3 -> APPROVE_L3;
                default -> throw new IllegalArgumentException("非法复核级别: " + level);
            };
        }

        /** 某级复核驳回对应的动作 */
        public static FlowAction rejectOf(int level) {
            return switch (level) {
                case 1 -> REJECT_L1;
                case 2 -> REJECT_L2;
                case 3 -> REJECT_L3;
                default -> throw new IllegalArgumentException("非法复核级别: " + level);
            };
        }
    }

    /* ==================== 审计类型 ==================== */
    @Getter
    public enum AuditType {
        FINANCIAL("FINANCIAL", "财务审计"),
        IT_SYSTEM("IT_SYSTEM", "信息系统审计"),
        COMPLIANCE("COMPLIANCE", "合规审计"),
        PERFORMANCE("PERFORMANCE", "绩效审计");

        private final String code;
        private final String desc;

        AuditType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static boolean isValid(String code) {
            for (AuditType t : values()) {
                if (t.code.equals(code)) {
                    return true;
                }
            }
            return false;
        }
    }

    /* ==================== 审计抽样 ==================== */

    /**
     * 可接受的信赖过度风险，用置信水平表达。
     *
     * <p>准则 1314 号里的"信赖过度风险"指的是：内部控制实际上无效，
     * 但抽样结论却认为它有效 —— 这是审计上最危险的一类错误，
     * 因为它会让审计人员少做实质性程序，直接导致审计失败。
     *
     * <p>为什么用置信水平而不是直接写风险：
     * 底稿上写"95% 置信水平"是行业通用表述，写"5% 信赖过度风险"
     * 复核人还要在脑子里换算一次。代码里两者都留着，各有各的用处。
     *
     * <p>取值依据：控制对财务报表认定越重要、越依赖该控制，
     * 越应该选 95%；控制只作为辅助证据时可选 90%。
     */
    @Getter
    public enum ConfidenceLevel {
        NINETY_FIVE(95.0, 0.05, "95%"),
        NINETY(90.0, 0.10, "90%");

        /** 置信水平百分比，用于展示 */
        private final double percent;
        /** 对应的风险值，用于计算 */
        private final double risk;
        private final String desc;

        ConfidenceLevel(double percent, double risk, String desc) {
            this.percent = percent;
            this.risk = risk;
            this.desc = desc;
        }

        public static ConfidenceLevel of(String name) {
            for (ConfidenceLevel c : values()) {
                if (c.name().equals(name)) {
                    return c;
                }
            }
            return NINETY_FIVE;
        }
    }

    /**
     * 抽样方法。
     *
     * <p>准则 1314 号把抽样分为统计抽样与非统计抽样。
     * 关键差别不在"抽几个"，而在<b>结论能不能推断总体</b>：
     * 统计抽样用概率论算样本量，偏差率上限可以量化，结论可外推；
     * 判断抽样靠经验，抽到了就抽到了，不能声称"总体偏差率不超过 5%"。
     *
     * <p>系统里两种都支持，但只有统计抽样会展示置信区间 ——
     * 给判断抽样也套一个置信区间，等于伪造统计基础。
     */
    @Getter
    public enum SamplingMethod {
        STATISTICAL("STATISTICAL", "统计抽样", "按二项分布计算样本量，结论可推断总体"),
        JUDGMENTAL("JUDGMENTAL", "判断抽样", "依职业判断选取样本，结论不可外推");

        private final String code;
        private final String desc;
        private final String note;

        SamplingMethod(String code, String desc, String note) {
            this.code = code;
            this.desc = desc;
            this.note = note;
        }

        public static boolean isValid(String code) {
            for (SamplingMethod m : values()) {
                if (m.code.equals(code)) {
                    return true;
                }
            }
            return false;
        }

        public static String descOf(String code) {
            for (SamplingMethod m : values()) {
                if (m.code.equals(code)) {
                    return m.desc;
                }
            }
            return "未知";
        }
    }

    /**
     * 抽样结论。
     *
     * <p>只有三种，刻意不设"部分有效"这种中间态：
     * 审计结论必须能落到"能不能信赖"上，含糊的结论在复核环节会被打回。
     * 样本不足以支持结论时，正确的做法是扩大样本（仍然是 PENDING），
     * 而不是给一个"大概有效"。
     */
    @Getter
    public enum SamplingConclusion {
        PENDING(0, "未执行"),
        EFFECTIVE(1, "控制可信赖"),
        INEFFECTIVE(2, "控制不可信赖");

        private final Integer code;
        private final String desc;

        SamplingConclusion(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(Integer code) {
            for (SamplingConclusion c : values()) {
                if (c.code.equals(code)) {
                    return c.desc;
                }
            }
            return "未知";
        }
    }

    /* ==================== 变量抽样（细节测试） ==================== */

    /**
     * 变量抽样估计方法。
     *
     * <p>三种方法的差别不在"精度高低"，而在<b>前提假设</b>：
     * 错报和金额有没有关系，决定了该用哪一种。选错方法算出来的数看着像模像样，
     * 但推断基础是错的 —— 这比算错更难被发现。
     */
    @Getter
    public enum VariableSamplingMethod {
        MEAN_PER_UNIT("MEAN_PER_UNIT", "均值估计",
                "以样本审定金额均值推断总体，不依赖错报分布，最稳健但样本量最大"),
        DIFFERENCE("DIFFERENCE", "差额估计",
                "以样本平均单笔错报推断，要求错报与项目金额无关"),
        RATIO("RATIO", "比率估计",
                "以样本错报率推断，要求错报与账面金额成比例，通常最精确");

        private final String code;
        private final String desc;
        private final String note;

        VariableSamplingMethod(String code, String desc, String note) {
            this.code = code;
            this.desc = desc;
            this.note = note;
        }

        public static boolean isValid(String code) {
            for (VariableSamplingMethod m : values()) {
                if (m.code.equals(code)) {
                    return true;
                }
            }
            return false;
        }

        public static String descOf(String code) {
            for (VariableSamplingMethod m : values()) {
                if (m.code.equals(code)) {
                    return m.desc;
                }
            }
            return "未知";
        }

        public static String noteOf(String code) {
            for (VariableSamplingMethod m : values()) {
                if (m.code.equals(code)) {
                    return m.note;
                }
            }
            return "";
        }
    }

    /**
     * 变量抽样结论。
     *
     * <p>措辞刻意用"可接受/不可接受"而不是"错报有无"：
     * 细节测试的结论是"这个金额能不能接受"，不是"有没有错报"——
     * 任何总体都会有小额错报，关键看是否超过可容忍水平。
     */
    @Getter
    public enum VariableSamplingConclusion {
        PENDING(0, "未评价"),
        ACCEPTABLE(1, "可接受"),
        NOT_ACCEPTABLE(2, "不可接受");

        private final Integer code;
        private final String desc;

        VariableSamplingConclusion(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public static String descOf(Integer code) {
            for (VariableSamplingConclusion c : values()) {
                if (c.code.equals(code)) {
                    return c.desc;
                }
            }
            return "未知";
        }
    }
}
