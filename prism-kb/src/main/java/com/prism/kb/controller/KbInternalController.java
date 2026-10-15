package com.prism.kb.controller;

import com.prism.kb.entity.Document;
import com.prism.kb.entity.Kb;
import com.prism.kb.service.DocumentService;
import com.prism.kb.service.KbService;
import com.prism.common.result.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 内部接口：仅 Python agent-service 消费端调用（网关已拒绝外部 /internal 请求）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class KbInternalController {

    private final KbService kbService;
    private final DocumentService documentService;

    /** Python 解析时回查切分参数 */
    @GetMapping("/kbs/{kbId}")
    public Result<Map<String, Object>> kbParams(@PathVariable Long kbId) {
        Kb kb = kbService.require(kbId);
        return Result.ok(Map.of(
                "kbId", kb.getId(),
                "kbName", kb.getKbName(),
                "chunkSize", kb.getChunkSize(),
                "chunkOverlap", kb.getChunkOverlap(),
                "topK", kb.getTopK()));
    }

    /** Python 下载待解析文件 */
    @GetMapping("/documents/{docId}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable Long docId) {
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.getDocName().endsWith(".pdf") ? "application/pdf"
                : doc.getDocName().endsWith(".docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "text/plain; charset=utf-8";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + doc.getDocName())
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(path));
    }

    /** Python 解析回调：READY+chunks / FAILED+error（幂等：先删后插） */
    @PostMapping("/documents/{docId}/status")
    public Result<Void> status(@PathVariable Long docId, @RequestBody StatusCallback cb) {
        documentService.updateStatus(docId, cb.getStatus(), cb.getChunkCount(), cb.getError(),
                cb.getChunks() == null ? List.of()
                        : cb.getChunks().stream()
                                .map(c -> new DocumentService.ChunkPayload(c.getChunkIndex(), c.getContent(), c.getTokenCount()))
                                .toList());
        return Result.ok();
    }

    @Data
    public static class ChunkItem {
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
    }

    @Data
    public static class StatusCallback {
        private String status;
        private Integer chunkCount;
        private String error;
        private List<ChunkItem> chunks;
    }
}
