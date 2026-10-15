package com.prism.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.util.SnowFlakeId;
import com.prism.kb.entity.DocChunk;
import com.prism.kb.entity.Document;
import com.prism.kb.entity.Kb;
import com.prism.kb.mapper.DocChunkMapper;
import com.prism.kb.mapper.DocumentMapper;
import com.prism.kb.mapper.KbMapper;
import com.prism.kb.mq.KbTaskDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档上传 / 状态回调 / 删除。
 * 回调 chunks 采用"先删后插"幂等策略：消息重投或文档重解析不会产生重复块。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocChunkMapper docChunkMapper;
    private final KbMapper kbMapper;
    private final KbTaskDispatcher dispatcher;
    private final SnowFlakeId snowFlakeId;

    @Value("${prism.kb.storage:./data/kb}")
    private String storageRoot;

    public Document upload(Long kbId, MultipartFile file) {
        Kb kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId);
        }
        String safeName = FileNameUtil.sanitize(file.getOriginalFilename());
        long docId = snowFlakeId.nextId();

        Document doc = new Document();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setDocName(safeName);
        doc.setFileSize(file.getSize());
        doc.setChunkCount(0);
        doc.setStatus("PENDING");
        doc.setCreatedAt(LocalDateTime.now());

        // {storage}/{kbId}/{docId}/{safeName}：docId 隔离防同名覆盖，安全文件名防穿越
        Path dir = Paths.get(storageRoot, String.valueOf(kbId), String.valueOf(docId));
        try {
            Files.createDirectories(dir);
            // 再校验一次 resolve 后仍在存储根目录下（纵深防御，注意先转绝对路径再比较）
            Path target = dir.resolve(safeName).normalize().toAbsolutePath();
            Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "非法存储路径");
            }
            file.transferTo(target);
            doc.setFilePath(target.toString());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件保存失败: " + e.getMessage());
        }
        documentMapper.insert(doc);
        dispatcher.dispatch(kbId, docId);
        return doc;
    }

    public List<Document> listByKb(Long kbId) {
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getCreatedAt));
    }

    public Document getById(Long docId) {
        return documentMapper.selectById(docId);
    }

    /** Python 解析回调：状态 + 块元数据落库（幂等：先删后插） */
    public void updateStatus(long docId, String status, Integer chunkCount, String error,
                             List<ChunkPayload> chunks) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("回调文档不存在 docId={} status={}", docId, status);
            return;
        }
        doc.setStatus(status);
        doc.setError(error == null ? "" : error);
        doc.setChunkCount(chunkCount == null ? 0 : chunkCount);
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(doc);

        if (chunks != null) {
            docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, docId));
            for (ChunkPayload c : chunks) {
                DocChunk chunk = new DocChunk();
                chunk.setDocumentId(docId);
                chunk.setKbId(doc.getKbId());
                chunk.setChunkIndex(c.chunkIndex());
                chunk.setContent(c.content());
                chunk.setTokenCount(c.tokenCount());
                chunk.setChromaId(docId + ":" + c.chunkIndex());
                chunk.setCreatedAt(LocalDateTime.now());
                docChunkMapper.insert(chunk);
            }
        }
    }

    public void delete(Long kbId, Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        docChunkMapper.delete(new LambdaQueryWrapper<DocChunk>().eq(DocChunk::getDocumentId, docId));
        documentMapper.deleteById(docId);
        try {
            Path dir = Paths.get(storageRoot, String.valueOf(kbId), String.valueOf(docId));
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> p.toFile().delete());
            }
        } catch (IOException e) {
            log.warn("本地文件清理失败（不影响向量清理）docId={}: {}", docId, e.getMessage());
        }
        // 向量清理由 controller 调 Python purge（best-effort）
    }

    public record ChunkPayload(int chunkIndex, String content, int tokenCount) {
    }
}
