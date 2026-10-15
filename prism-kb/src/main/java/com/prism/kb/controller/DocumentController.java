package com.prism.kb.controller;

import com.prism.kb.entity.Document;
import com.prism.kb.service.AgentClient;
import com.prism.kb.service.DocumentService;
import com.prism.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理：上传派发解析任务、状态列表、删除（元数据 + 本地文件 + 向量）。
 */
@RestController
@RequestMapping("/api/kb/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final AgentClient agentClient;

    @PostMapping
    public Result<Map<String, Object>> upload(@PathVariable Long kbId,
                                              @RequestParam("file") MultipartFile file) {
        Document doc = documentService.upload(kbId, file);
        return Result.ok(Map.of("docId", doc.getId(), "docName", doc.getDocName(), "status", doc.getStatus()));
    }

    @GetMapping
    public Result<List<Document>> list(@PathVariable Long kbId) {
        return Result.ok(documentService.listByKb(kbId));
    }

    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable Long kbId, @PathVariable Long docId) {
        documentService.delete(kbId, docId);
        agentClient.purgeQuietly(kbId, docId);
        return Result.ok();
    }
}
