package com.maning.audit.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果对象
 *
 * <p>为什么不直接返回 MP 的 Page：Page 里有 orders、optimizeCountSql、
 * countId 等纯内部字段，返回给前端既是信息泄漏也是无用数据。
 */
@Data
public class PageResult<T> implements Serializable {

    /** 总记录数 */
    private Long total;
    /** 当前页码 */
    private Long current;
    /** 每页条数 */
    private Long size;
    /** 总页数 */
    private Long pages;
    /** 当前页数据 */
    private List<T> records;

    public static <T> PageResult<T> of(Long total, Long current, Long size, List<T> records) {
        PageResult<T> pr = new PageResult<>();
        pr.setTotal(total);
        pr.setCurrent(current);
        pr.setSize(size);
        pr.setPages(size == null || size == 0 ? 0 : (total + size - 1) / size);
        pr.setRecords(records);
        return pr;
    }
}
