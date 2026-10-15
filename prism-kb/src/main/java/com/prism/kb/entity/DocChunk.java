package com.prism.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档切块元数据（原文与检索索引：向量在 Chroma，content 冗余存 MySQL 便于引用溯源展示）。
 */
@Data
@TableName("t_doc_chunk")
public class DocChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long kbId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    private String chromaId;

    private LocalDateTime createdAt;
}
