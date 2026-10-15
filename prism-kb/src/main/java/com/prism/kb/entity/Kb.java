package com.prism.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_kb")
public class Kb {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kbName;

    private String description;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private Integer topK;

    private Integer status;

    private LocalDateTime createdAt;
}
