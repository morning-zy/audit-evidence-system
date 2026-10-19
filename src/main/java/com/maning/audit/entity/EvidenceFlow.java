package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 证据流转记录
 *
 * <p>这张表是"审计留痕"的落地：每一条证据的每一次状态变更都记一行。
 * 三个设计细节值得注意：
 * ① 没有 @TableLogic —— 审计日志不允许删除，也不需要逻辑删除
 * ② operator_name 冗余存了姓名 —— 用户改名后，历史记录仍应显示当时的姓名，
 *    否则追溯结果就失真了。这是审计场景对"快照"的特殊要求
 * ③ from_status 可为 null —— 创建动作没有前置状态
 */
@Data
@TableName("evidence_flow")
public class EvidenceFlow implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long evidenceId;

    /** CREATE / SUBMIT / APPROVE_L1_L2_L3 / REJECT_L1_L2_L3 */
    private String action;

    private Integer fromStatus;

    private Integer toStatus;

    /**
     * 本次操作所属复核级别：1详细 2一般 3重点；NULL 表示非复核动作（创建、提交）。
     *
     * <p>为什么动作里已经带了 L1/L2/L3 还要单独存级别：
     * 流转记录要能独立回答"第几级、谁、何时、什么意见"，不依赖对 action 字符串的解析。
     * 冗余一个 TINYINT 换来查询直白 —— 统计"各部门在二级驳回了多少"时
     * 直接 {@code WHERE review_level = 2} 即可，不用写一堆 LIKE。
     */
    private Integer reviewLevel;

    private Long operatorId;

    private String operatorName;

    private String comment;

    private LocalDateTime createTime;
}
