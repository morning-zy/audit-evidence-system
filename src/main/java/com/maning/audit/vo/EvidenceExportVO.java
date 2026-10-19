package com.maning.audit.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据导出 Excel 的行模型
 *
 * <p>为什么要单独的导出模型而不是直接导 EvidenceVO：
 * ① @ExcelProperty 注解会污染展示用的 VO
 * ② 导出列和接口返回字段经常不同（导出要去掉 id、加上中文表头）
 * ③ 表头顺序由注解顺序决定，独立类控制起来更清晰
 */
@Data
@HeadRowHeight(22)
@ContentRowHeight(18)
public class EvidenceExportVO {

    @ExcelProperty("证据编号")
    @ColumnWidth(16)
    private String evidenceNo;

    @ExcelProperty("所属项目")
    @ColumnWidth(30)
    private String projectName;

    @ExcelProperty("事项编号")
    @ColumnWidth(14)
    private String issueNo;

    @ExcelProperty("证据名称")
    @ColumnWidth(40)
    private String evidenceName;

    @ExcelProperty("证据类型")
    @ColumnWidth(14)
    private String evidenceTypeName;

    @ExcelProperty("证据来源")
    @ColumnWidth(20)
    private String source;

    @ExcelProperty("可靠性")
    @ColumnWidth(10)
    private String reliabilityName;

    @ExcelProperty("状态")
    @ColumnWidth(12)
    private String statusName;

    @ExcelProperty("提交人")
    @ColumnWidth(12)
    private String submitByName;

    @ExcelProperty("提交时间")
    @ColumnWidth(20)
    private LocalDateTime submitTime;

    @ExcelProperty("复核人")
    @ColumnWidth(12)
    private String reviewByName;

    @ExcelProperty("复核时间")
    @ColumnWidth(20)
    private LocalDateTime reviewTime;

    @ExcelProperty("复核意见")
    @ColumnWidth(40)
    private String reviewComment;
}
