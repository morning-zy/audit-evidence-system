package com.maning.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 复核请求 DTO（通过/驳回共用一个）
 */
@Data
@Schema(description = "证据复核请求")
public class ReviewDTO {

    @Size(max = 512, message = "复核意见最长512字符")
    @Schema(description = "复核意见（驳回时必填，通过时选填）", example = "证据形式完整，可采用")
    private String comment;
}
