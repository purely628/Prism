package com.prism.kb.service;

import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;

import java.util.Locale;
import java.util.Set;

/**
 * 上传文件安全处理：扩展名白名单 + 文件名清洗（拒绝路径穿越）。
 * 独立成纯工具类便于单测——安全边界代码必须有测试。
 */
public final class FileNameUtil {

    private FileNameUtil() {
    }

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");

    /** @return 清洗后的文件名；非法直接抛 PARAM_ERROR */
    public static String sanitize(String original) {
        if (original == null || original.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        if (original.contains("..")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法文件名");
        }
        // 只保留文件名部分，去掉任何目录成分（Windows/Linux 分隔符都防）
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 白名单字符：中英文、数字、点、空格、下划线、短横线、括号
        name = name.replaceAll("[^\\w\\u4e00-\\u9fa5.()\\- ]", "_").trim();
        if (name.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "非法文件名");
        }
        // 扩展名统一小写，便于白名单判断与 Python 解析器分发
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            name = name.substring(0, dot) + "." + name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (!allowedExtension(name)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 pdf/docx/txt/md 格式");
        }
        return name;
    }

    public static boolean allowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext);
    }
}
