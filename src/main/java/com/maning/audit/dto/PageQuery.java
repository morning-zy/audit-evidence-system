package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用分页查询基类
 *
 * <p>页码和条数做上限保护：不限制的话前端一个 pageSize=100000 就能把数据库拖死。
 * 这是最容易被忽略、但线上最容易出事的点之一。
 */
@Data
@Schema(description = "分页查询基础参数")
public class PageQuery {

    @Schema(description = "页码，从1开始", example = "1")
    private Long pageNum = 1L;

    @Schema(description = "每页条数，最大100", example = "10")
    private Long pageSize = 10L;

    @Schema(description = "关键字（模糊匹配名称/编号）")
    private String keyword;

    public Long getPageNum() {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    public Long getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10L;
        }
        // 强制上限，避免恶意大分页
        return Math.min(pageSize, 100L);
    }
}
