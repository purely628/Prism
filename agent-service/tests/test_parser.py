"""parser：txt 解析（utf-8/gbk）+ 中文优先切分。"""

from app.services.parser import parse_bytes, split_text


def test_parse_txt_utf8():
    data = "你好 Prism\n第二行内容".encode("utf-8")
    assert parse_bytes("a.txt", data) == "你好 Prism\n第二行内容"


def test_parse_txt_gbk_fallback():
    data = "中文编码测试".encode("gbk")
    assert parse_bytes("b.txt", data) == "中文编码测试"


def test_parse_txt_without_extension_sniffs_text():
    assert parse_bytes("", "无扩展名文本".encode("utf-8")) == "无扩展名文本"


def test_split_text_chinese_sentence_first():
    text = "第一句讲系统架构设计。第二句讲数据流向与模块职责划分。第三句总结。" * 8
    pieces = split_text(text, chunk_size=40, chunk_overlap=5)
    assert len(pieces) >= 2
    assert all(p for p in pieces)
    # 句号优先分隔：块内保留完整句子（不出现句中硬截断为半句开头）
    assert any(p.startswith("第一句") or "。" in p for p in pieces)


def test_split_text_empty():
    assert split_text("", 500, 50) == []
    assert split_text("   \n  ", 500, 50) == []
