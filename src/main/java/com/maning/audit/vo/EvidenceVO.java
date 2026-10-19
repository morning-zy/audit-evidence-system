package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计证据 VO
 */
@Data
@Schema(description = "审计证据")
public class EvidenceVO {

    private Long id;
    private String evidenceNo;
    private Long projectId;
    private String projectName;
    private Long issueId;
    private String issueNo;
    private String issueTitle;
    private String evidenceName;
    private String evidenceType;
    private String evidenceTypeName;
    private String source;

    /** 可靠性等级码 1低 2中 3高 4很高（系统按准则计算，非人工填写） */
    private Integer reliability;
    private String reliabilityName;
    /** 等级字母 A/B/C/D，与纸质审计底稿的标注习惯一致 */
    private String reliabilityGrade;

    /* ============ 可靠性五维度及判定依据（对应审计准则 1301 号 第三条）============ */

    private String sourceType;
    private String sourceTypeName;
    private String acquireMethod;
    private String acquireMethodName;
    private String formType;
    private String formTypeName;
    private String originality;
    private String originalityName;
    private Boolean controlEffective;

    @Schema(description = "可靠性判定依据（一句话，可直接抄进审计底稿）")
    private String reliabilityBasis;

    @Schema(description = "是否需要补充佐证材料（C级及以下为 true，提交复核时强制要求）")
    private Boolean requiresSupplementary;

    private String filePath;
    private Long fileSize;
    private String remark;
    private Integer status;
    private String statusName;
    private Long submitBy;
    private String submitByName;
    private LocalDateTime submitTime;
    private Long reviewBy;
    private String reviewByName;
    private LocalDateTime reviewTime;
    private String reviewComment;
    private LocalDateTime createTime;

    @Schema(description = "当前登录用户是否为该证据的提交人（前端据此控制按钮显隐）")
    private Boolean canEdit;
    @Schema(description = "当前登录用户是否可以复核该证据")
    private Boolean canReview;

    /* ---------- 三级复核进度 ---------- */

    @Schema(description = "当前复核级别 0未提交 1待详细复核 2待一般复核 3待重点复核")
    private Integer currentLevel;

    @Schema(description = "当前复核级别中文名")
    private String currentLevelName;

    @Schema(description = "是否正在复核流程中")
    private Boolean reviewing;

    @Schema(description = "当前环节应由哪个岗位处理，如「部门经理」")
    private String pendingRoleName;

    @Schema(description = "是否已走完三级复核")
    private Boolean reviewFinished;

    @Schema(description = "当前登录用户的角色名，用于提示语")
    private String myRoleName;
}
