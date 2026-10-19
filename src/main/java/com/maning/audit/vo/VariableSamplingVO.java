package com.maning.audit.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 变量抽样视图。
 *
 * <p>派生字段都在后端算好再给前端：
 * 估计方法的中文与适用前提、结论措辞、还剩几项没检查、已发现错报笔数。
 * 前端只负责展示，不参与业务判断 —— 一旦前端自己拼结论，
 * 就会出现"页面写可接受、底稿写不可接受"的分裂，而这种分裂在复核时是致命伤。
 */
@Data
public class VariableSamplingVO {

    private Long id;

    private String samplingNo;

    private Long projectId;

    private Long issueId;

    private String accountName;

    private BigDecimal populationBookValue;

    private Integer populationSize;

    private BigDecimal tolerableMisstatement;

    private BigDecimal expectedMisstatement;

    private BigDecimal assumedStdDev;

    private String confidenceLevel;

    /** 95% / 90% */
    private String confidenceDesc;

    private String method;

    private String methodDesc;

    /** 方法适用前提，前端在方法选择旁显示，避免用户选错 */
    private String methodNote;

    private Integer sampleSize;

    private Boolean isPilot;

    private Integer checkedCount;

    private BigDecimal projectedMisstatement;

    private BigDecimal precisionAmount;

    private BigDecimal upperMisstatement;

    private BigDecimal sampleStdDev;

    private Integer conclusion;

    private String conclusionDesc;

    private String basis;

    private String conclusionText;

    /** 还需检查几项 */
    private Integer remaining;

    /** 已发现错报的样本笔数（错报金额不为 0） */
    private Integer misstatementCount;

    /** 是否已具备评价条件：全部检查完成 */
    private Boolean readyToConclude;

    private List<ItemVO> items;

    @Data
    public static class ItemVO {
        private Long id;
        private Integer seqNo;
        private String voucherNo;
        private LocalDate voucherDate;
        private BigDecimal bookValue;
        private BigDecimal auditedValue;
        private BigDecimal misstatement;
        private String remark;
        private LocalDateTime checkedTime;
    }
}
