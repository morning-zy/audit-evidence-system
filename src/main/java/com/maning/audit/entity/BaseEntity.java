package com.maning.audit.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类
 *
 * <p>把 4 张表都有的审计字段抽上来，避免每个实体重复声明。
 * 注意用 @TableField(fill = ...) 配合 MetaObjectHandler 自动填充，
 * 这样 Service 里不用手动 setCreateTime，也不会漏。
 */
@Data
public class BaseEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;
}
