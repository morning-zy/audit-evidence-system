package com.maning.audit.service.impl;

import com.maning.audit.common.exception.BusinessException;
import com.maning.audit.common.result.ResultCode;
import com.maning.audit.config.AuditProperties;
import com.maning.audit.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * 文件服务实现（本地磁盘存储）
 *
 * <p>安全要点，都是真实会被攻击的地方：
 * ① <b>白名单校验扩展名</b>，不是黑名单。黑名单永远漏（.jsp/.jspx/.war...），
 *    而且攻击者可以玩双扩展名 xxx.jpg.jsp
 * ② <b>不信任客户端传来的文件名</b>，服务端重新生成 UUID 文件名。
 *    直接用原文件名会有路径穿越风险（../../etc/passwd）
 * ③ 按年月分目录，避免单目录几十万文件后 ls 卡死、备份变慢
 *
 * <p>局限：本地存储无法多机共享，生产要换 MinIO/OSS。
 * 但作为课设，把"怎么换"讲清楚比强行上对象存储更实在。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final AuditProperties props;

    /** 审计证据允许的附件类型 */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "jpg", "jpeg", "png", "gif", "bmp", "zip", "rar", "7z", "txt", "csv"
    );

    @Override
    public FileUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename());

        String ext = getExtension(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_ALLOWED,
                    "不支持的文件类型: " + ext + "，允许: " + String.join(", ", ALLOWED_EXT));
        }

        // 按 yyyy/MM 分目录
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        Path baseDir = Paths.get(props.getFile().getUploadDir())
                .toAbsolutePath().normalize();
        Path targetDir = baseDir.resolve(datePath).normalize();

        // 纵深防御：确认最终路径确实在 baseDir 之内，防路径穿越
        if (!targetDir.startsWith(baseDir)) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "非法文件路径");
        }

        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(storedName);
            file.transferTo(target.toFile());

            // 存相对路径，迁移存储位置时只改配置不用改数据
            String relativePath = "/" + datePath + "/" + storedName;
            log.info("文件上传成功: {} -> {}, size={}B", originalName, relativePath, file.getSize());

            return new FileUploadResult(relativePath, originalName, file.getSize());
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase();
    }
}
