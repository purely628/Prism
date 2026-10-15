package com.prism.kb.service;

import com.prism.common.util.SnowFlakeId;
import com.prism.kb.entity.Document;
import com.prism.kb.mapper.DocChunkMapper;
import com.prism.kb.mapper.DocumentMapper;
import com.prism.kb.mapper.KbMapper;
import com.prism.kb.mq.KbTaskDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回调 chunk "先删后插"幂等：重投/重解析不产生重复块 */
class ChunkIdempotentTest {

    @Test
    void 回调落库_先删后插() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocChunkMapper docChunkMapper = mock(DocChunkMapper.class);
        KbMapper kbMapper = mock(KbMapper.class);
        KbTaskDispatcher dispatcher = mock(KbTaskDispatcher.class);

        DocumentService service = new DocumentService(documentMapper, docChunkMapper, kbMapper,
                dispatcher, new SnowFlakeId(5));

        Document doc = new Document();
        doc.setId(1L);
        doc.setKbId(10L);
        when(documentMapper.selectById(1L)).thenReturn(doc);

        service.updateStatus(1L, "READY", 2, null, List.of(
                new DocumentService.ChunkPayload(0, "first chunk", 10),
                new DocumentService.ChunkPayload(1, "second chunk", 12)));

        // 先删旧块（幂等核心）
        verify(docChunkMapper, times(1)).delete(any());
        // 再插入 2 条新块
        ArgumentCaptor<com.prism.kb.entity.DocChunk> captor =
                ArgumentCaptor.forClass(com.prism.kb.entity.DocChunk.class);
        verify(docChunkMapper, times(2)).insert(captor.capture());
        assertEquals(0, captor.getAllValues().get(0).getChunkIndex());
        assertEquals("1:1", captor.getAllValues().get(1).getChromaId());
    }
}
