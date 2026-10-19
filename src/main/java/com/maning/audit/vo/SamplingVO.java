package com.maning.audit.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽样计划视图。
 *
 * <p>除了原始字段，还带一组"算出来给前端直接用"的派生字段：
 * 置信水平的中文、抽样方法的中文、结论的中文、还剩几个没检查。
 * 为什么不把这些放前端算：结论措辞必须和后端一致 ——
 * 前端自己拼"可以信赖"，后端改了口径而前端没改，就会出现
 * 页面显示"有效"、底稿写"不可信赖"的分裂。
 */
@Data
public class SamplingVO {

    private Long id;

    private String samplingNo;

    private Long projectId;

    private Long issueId;

    private String controlName;

    private Integer populationSize;

    private BigDecimal tolerableRate;

    private BigDecimal expectedRate;

    private String confidenceLevel;

    /** 95% / 90% */
    private String confidenceDesc;

    private String method;

    /** 统计抽样 / 判断抽样 */
    private String methodDesc;

    private Integer sampleSize;

    private Integer allowedDeviations;

    private Boolean isCensus;

    private Integer checkedCount;

    private Integer deviationCount;

    private BigDecimal deviationRate;

    private BigDecimal upperLimit;

    /** 0 未执行 1 可信赖 2 不可信赖 */
    private Integer conclusion;

    private String conclusionDesc;

    private String basis;

    private String conclusionText;

    /** 还需检查几个样本 */
    private Integer remaining;

    /** 是否已经可以出结论：检查数达到样本量，或偏差数已超过允许值 */
    private Boolean readyToConclude;

    private List<ItemVO> items;

    @Data
    public static class ItemVO {
        private Long id;
        private Integer seqNo;
        private String voucherNo;
        private LocalDate voucherDate;
        private BigDecimal amount;
        private Boolean deviation;
        private String deviationDesc;
        private LocalDateTime checkedTime;
    }
}
