package com.maning.audit.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务
 */
public interface FileService {

    /**
     * 上传证据附件
     *
     * @return 可直接存进 audit_evidence.file_path 的相对路径
     */
    FileUploadResult upload(MultipartFile file);

    record FileUploadResult(String filePath, String originalName, Long size) {
    }
}
