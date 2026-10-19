package com.maning.audit.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maning.audit.common.context.UserContext;
import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.PageResult;
import com.maning.audit.common.result.Result;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.dto.PageQuery;
import com.maning.audit.entity.OperationLog;
import com.maning.audit.mapper.OperationLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询接口
 *
 * <p>说明：这个 Controller 里直接用了 Mapper，没有走 Service 层。
 * 为什么可以接受：查询逻辑只有一条 Wrapper 拼装，没有业务规则、
 * 没有事务、没有多表聚合。为这种"纯透传查询"硬造一个 Service
 * 只是多一层空壳。
 *
 * <p><b>但要诚实</b>：严格的分层规范里，Controller 不该碰 Mapper。
 * 如果日志查询以后要加复杂条件、要做聚合统计，就应该抽出 LogService。
 * 这里保留直连是为了让你看到"分层的边界在哪里可以放松"，
 * 而不是让你学"Controller 可以直接调 Mapper"。
 */
@Tag(name = "06-操作日志", description = "系统操作留痕查询（仅主管可见）")
@RestController
@RequestMapping("/log")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogMapper logMapper;

    @Operation(summary = "分页查询操作日志")
    @GetMapping("/page")
    public Result<PageResult<OperationLog>> page(PageQuery query,
                                                 @RequestParam(required = false) String module) {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有审计主管可以查看操作日志");
        }

        Page<OperationLog> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<OperationLog> result = logMapper.selectPage(page, Wrappers.<OperationLog>lambdaQuery()
                .eq(StringUtils.hasText(module), OperationLog::getModule, module)
                .like(StringUtils.hasText(query.getKeyword()), OperationLog::getOperation, query.getKeyword())
                .orderByDesc(OperationLog::getCreateTime));

        return Result.page(result);
    }
}
