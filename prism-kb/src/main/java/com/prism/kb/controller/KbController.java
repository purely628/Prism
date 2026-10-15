package com.prism.kb.controller;

import com.prism.kb.entity.Kb;
import com.prism.kb.service.KbService;
import com.prism.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理（/api/kb/**，网关 JWT 鉴权后透传 X-User-Id）。
 */
@RestController
@RequestMapping("/api/kb")
@Validated
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.ok(kbService.list(userId));
    }

    @PostMapping
    public Result<Kb> create(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                             @Valid @RequestBody KbUpsertRequest req) {
        Kb kb = new Kb();
        kb.setKbName(req.getKbName());
        kb.setDescription(req.getDescription());
        kb.setChunkSize(req.getChunkSize());
        kb.setChunkOverlap(req.getChunkOverlap());
        kb.setTopK(req.getTopK());
        return Result.ok(kbService.create(userId, kb));
    }

    @PutMapping("/{id}")
    public Result<Kb> update(@PathVariable Long id, @RequestBody KbUpsertRequest req) {
        Kb patch = new Kb();
        patch.setKbName(req.getKbName());
        patch.setDescription(req.getDescription());
        patch.setChunkSize(req.getChunkSize());
        patch.setChunkOverlap(req.getChunkOverlap());
        patch.setTopK(req.getTopK());
        return Result.ok(kbService.update(id, patch));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }

    @Data
    public static class KbUpsertRequest {
        @NotBlank(message = "知识库名称不能为空")
        private String kbName;
        private String description;
        private Integer chunkSize;
        private Integer chunkOverlap;
        private Integer topK;
    }
}
