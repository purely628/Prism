package com.prism.kb.service;

import com.prism.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上传安全边界：扩展名白名单 + 路径穿越清洗 */
class FileNameUtilTest {

    @Test
    void 白名单内_正常通过() {
        assertEquals("产品手册.pdf", FileNameUtil.sanitize("产品手册.pdf"));
        assertEquals("notes.md", FileNameUtil.sanitize("notes.md"));
        assertEquals("my doc (v2).txt", FileNameUtil.sanitize("my doc (v2).txt"));
        assertEquals("说明书.docx", FileNameUtil.sanitize("说明书.DOCX"));
    }

    @Test
    void 白名单外_拒绝() {
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("evil.exe"));
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("shell.jsp"));
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("noext"));
    }

    @Test
    void 路径穿越_拒绝与清洗() {
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("../../etc/passwd.txt"));
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("..\\windows\\system32\\x.txt"));
        // 单层目录成分被剥离，只留文件名
        assertEquals("a.txt", FileNameUtil.sanitize("/var/data/a.txt"));
        assertEquals("a.txt", FileNameUtil.sanitize("C:\\data\\a.txt"));
    }

    @Test
    void 特殊字符_替换为下划线() {
        String cleaned = FileNameUtil.sanitize("报告#2026$版%.pdf");
        assertEquals("报告_2026_版_.pdf", cleaned);
    }

    @Test
    void 空名_拒绝() {
        assertThrows(BizException.class, () -> FileNameUtil.sanitize(null));
        assertThrows(BizException.class, () -> FileNameUtil.sanitize("   "));
    }

    @Test
    void 扩展名判断_大小写与无扩展名() {
        assertTrue(FileNameUtil.allowedExtension("x.PDF"));
        assertFalse(FileNameUtil.allowedExtension("x."));
        assertFalse(FileNameUtil.allowedExtension("x"));
    }
}
