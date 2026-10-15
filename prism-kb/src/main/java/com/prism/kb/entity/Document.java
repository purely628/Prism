package com.prism.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档状态机：PENDING（已上传待解析）→ PARSING（Python 消费中）→ READY / FAILED。
 * 状态由回调驱动推进，前端轮询列表可见。
 */
@Data
@TableName("t_document")
public class Document {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long kbId;

    private String docName;

    private String filePath;

    private Long fileSize;

    private Integer chunkCount;

    /** PENDING / PARSING / READY / FAILED */
    private String status;

    private String error;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
