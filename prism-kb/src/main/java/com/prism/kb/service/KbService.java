package com.prism.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.util.SnowFlakeId;
import com.prism.kb.entity.Document;
import com.prism.kb.entity.Kb;
import com.prism.kb.mapper.DocumentMapper;
import com.prism.kb.mapper.KbMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 CRUD。删除时同步清 Chroma（best-effort）。
 */
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final AgentClient agentClient;
    private final SnowFlakeId snowFlakeId;

    public List<Map<String, Object>> list(Long userId) {
        LambdaQueryWrapper<Kb> wrapper = new LambdaQueryWrapper<Kb>().orderByDesc(Kb::getCreatedAt);
        if (userId != null) {
            wrapper.eq(Kb::getUserId, userId);
        }
        List<Kb> kbs = kbMapper.selectList(wrapper);
        return kbs.stream().map(kb -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", kb.getId());
            vo.put("kbName", kb.getKbName());
            vo.put("description", kb.getDescription());
            vo.put("chunkSize", kb.getChunkSize());
            vo.put("chunkOverlap", kb.getChunkOverlap());
            vo.put("topK", kb.getTopK());
            Long docCount = documentMapper.selectCount(
                    new LambdaQueryWrapper<Document>().eq(Document::getKbId, kb.getId()));
            vo.put("docCount", docCount);
            return vo;
        }).toList();
    }

    public Kb create(Long userId, Kb kb) {
        kb.setId(null);
        kb.setUserId(userId);
        if (kb.getChunkSize() == null || kb.getChunkSize() < 100 || kb.getChunkSize() > 4000) {
            kb.setChunkSize(500);
        }
        if (kb.getChunkOverlap() == null || kb.getChunkOverlap() < 0 || kb.getChunkOverlap() >= kb.getChunkSize()) {
            kb.setChunkOverlap(50);
        }
        if (kb.getTopK() == null || kb.getTopK() < 1 || kb.getTopK() > 10) {
            kb.setTopK(4);
        }
        if (kb.getStatus() == null) {
            kb.setStatus(1);
        }
        kbMapper.insert(kb);
        return kb;
    }

    public Kb update(Long id, Kb patch) {
        Kb kb = require(id);
        if (patch.getKbName() != null) {
            kb.setKbName(patch.getKbName());
        }
        if (patch.getDescription() != null) {
            kb.setDescription(patch.getDescription());
        }
        if (patch.getChunkSize() != null) {
            kb.setChunkSize(patch.getChunkSize());
        }
        if (patch.getChunkOverlap() != null) {
            kb.setChunkOverlap(patch.getChunkOverlap());
        }
        if (patch.getTopK() != null) {
            kb.setTopK(patch.getTopK());
        }
        kbMapper.updateById(kb);
        return kb;
    }

    public void delete(Long id) {
        require(id);
        agentClient.purgeQuietly(id, null);
        kbMapper.deleteById(id);
    }

    public Kb require(Long id) {
        Kb kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
        }
        return kb;
    }
}
