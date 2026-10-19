package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * 审计证据 - 新增/修改 DTO
 */
@Data
@Schema(description = "审计证据保存请求")
public class EvidenceSaveDTO {

    @Schema(description = "证据ID（修改时必传）")
    private Long id;

    @NotNull(message = "所属事项不能为空")
    @Schema(description = "所属审计事项ID", example = "1")
    private Long issueId;

    @NotBlank(message = "证据名称不能为空")
    @Size(max = 128, message = "证据名称最长128字符")
    @Schema(description = "证据名称", example = "2026年8月末存货盘点表")
    private String evidenceName;

    @NotBlank(message = "证据类型不能为空")
    @Schema(description = "DOCUMENT书证/ELECTRONIC电子数据/INTERVIEW访谈记录/OBSERVATION现场观察/ANALYTICS分析性复核",
            example = "DOCUMENT")
    private String evidenceType;

    @Size(max = 128)
    @Schema(description = "证据来源的具体描述，如「仓储部提供」「XX银行回函」", example = "仓储部提供")
    private String source;

    /*
     * ============ 可靠性五维度（对应审计准则 1301 号 第三条）============
     *
     * 注意：这里已经<b>没有 reliability 字段</b>了。
     * 早期版本让人直接选「可靠性高/中/低」，这在审计上是站不住脚的 ——
     * 可靠性是判断结论，不是原始信息。让编制人自己填等级，
     * 等于让人自己给自己的证据打分，复核时无从追溯依据。
     *
     * 现在改为：编制人只填五个<b>客观事实</b>（证据从哪来、怎么来的、
     * 什么形式、是不是原件、控制有没有效），系统按准则算出等级。
     * 这才是审计底稿的正确做法 —— 事实在前，判断在后，判断可复核。
     */

    @NotBlank(message = "证据来源不能为空，审计底稿必须记录证据出处")
    @Schema(description = "来源：EXTERNAL外部独立来源 / INTERNAL被审计单位内部生成 / MANAGEMENT管理层声明",
            example = "EXTERNAL", allowableValues = {"EXTERNAL", "INTERNAL", "MANAGEMENT"})
    private String sourceType;

    @NotBlank(message = "证据获取方式不能为空")
    @Schema(description = "获取方式：DIRECT审计人员直接获取 / INDIRECT被审计单位提供 / INFERRED推算得出",
            example = "DIRECT", allowableValues = {"DIRECT", "INDIRECT", "INFERRED"})
    private String acquireMethod;

    @NotBlank(message = "证据存在形式不能为空")
    @Schema(description = "存在形式：WRITTEN书面文件 / ELECTRONIC电子记录 / ORAL口头说明",
            example = "WRITTEN", allowableValues = {"WRITTEN", "ELECTRONIC", "ORAL"})
    private String formType;

    @NotBlank(message = "证据原件性不能为空")
    @Schema(description = "原件性：ORIGINAL原件 / SCANNED扫描件 / COPY复印件",
            example = "ORIGINAL", allowableValues = {"ORIGINAL", "SCANNED", "COPY"})
    private String originality;

    @Schema(description = "内部控制是否有效。仅当来源为 INTERNAL 时参与评级（准则 1301 号 第三条第二项）",
            example = "true")
    private Boolean controlEffective;

    @Schema(description = "文件访问路径（先调用上传接口拿到 path 再传这里）")
    private String filePath;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Size(max = 512)
    @Schema(description = "备注")
    private String remark;
}
