package com.maning.audit.controller;

import com.maning.audit.common.result.Result;
import com.maning.audit.service.FileService;
import com.maning.audit.service.StatService;
import com.maning.audit.vo.DashboardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 统计与文件接口
 *
 * <p>把上传接口放在这里而不是 EvidenceController，
 * 理由是"文件"是通用能力，未来别的模块（比如项目附件）也会用到。
 * 按"能力"而不是"表"划分 Controller，能避免后期到处复制粘贴。
 */
@Tag(name = "05-统计与文件", description = "工作台看板、附件上传")
@RestController
@RequiredArgsConstructor
public class CommonController {

    private final StatService statService;
    private final FileService fileService;

    @Operation(summary = "工作台统计概览")
    @GetMapping("/stat/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.success(statService.dashboard());
    }

    @Operation(summary = "项目维度统计（带 Redis 缓存）")
    @GetMapping("/stat/project/{projectId}")
    public Result<DashboardVO> projectStat(@PathVariable Long projectId) {
        return Result.success(statService.projectStat(projectId));
    }

    @Operation(summary = "手动清除项目统计缓存")
    @DeleteMapping("/stat/project/{projectId}/cache")
    public Result<Void> evictCache(@PathVariable Long projectId) {
        statService.evictProjectStat(projectId);
        return Result.success("缓存已清除", null);
    }

    @Operation(summary = "上传证据附件",
            description = "返回相对路径，把 filePath 字段传给证据保存接口即可")
    @PostMapping("/file/upload")
    public Result<FileService.FileUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.success("上传成功", fileService.upload(file));
    }
}
