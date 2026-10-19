package com.maning.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工作台 / 看板统计 VO
 */
@Data
@Schema(description = "统计概览")
public class DashboardVO {

    @Schema(description = "进行中项目数")
    private Long activeProjectCount;

    @Schema(description = "事项总数")
    private Long issueTotal;

    @Schema(description = "已完成事项数")
    private Long issueCompleted;

    @Schema(description = "证据总数")
    private Long evidenceTotal;

    @Schema(description = "待我复核的证据数")
    private Long pendingReviewCount;

    @Schema(description = "我提交的证据总数")
    private Long myEvidenceCount;

    @Schema(description = "证据状态分布")
    private List<NameValueVO> evidenceStatusDist;

    @Schema(description = "证据类型分布")
    private List<NameValueVO> evidenceTypeDist;

    @Schema(description = "项目进度排行（按进度降序）")
    private List<ProjectVO> projectProgressList;

    @Data
    @Schema(description = "名称-数值对")
    public static class NameValueVO {
        private String name;
        private Long value;

        public NameValueVO() {
        }

        public NameValueVO(String name, Long value) {
            this.name = name;
            this.value = value;
        }
    }
}
