# -*- coding: utf-8 -*-
"""Prism 两份交付 PDF 生成：项目说明文档 + 面试技术拷打手册（ReportLab，Windows 字体）"""
import hashlib
import os
import re
import sys

PDF_SKILL_DIR = r"C:\Users\Xia purely\.zcode\cli\plugins\cache\zcode-plugins-official\pdf\0.1.7\skills\pdf"
sys.path.insert(0, os.path.join(PDF_SKILL_DIR, "scripts"))

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import cm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.pdfmetrics import registerFontFamily
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (CondPageBreak, HRFlowable, KeepTogether, PageBreak,
                                Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle)
from reportlab.platypus.tableofcontents import TableOfContents

# ---------- 字体（Windows 系统字体） ----------
pdfmetrics.registerFont(TTFont("Microsoft YaHei", r"C:\Windows\Fonts\msyh.ttc", subfontIndex=0))
pdfmetrics.registerFont(TTFont("SimHei", r"C:\Windows\Fonts\simhei.ttf"))
pdfmetrics.registerFont(TTFont("Times New Roman", r"C:\Windows\Fonts\times.ttf"))
pdfmetrics.registerFont(TTFont("Consolas", r"C:\Windows\Fonts\consola.ttf"))
registerFontFamily("Microsoft YaHei", normal="Microsoft YaHei", bold="Microsoft YaHei")
registerFontFamily("SimHei", normal="SimHei", bold="SimHei")
registerFontFamily("Times New Roman", normal="Times New Roman", bold="Times New Roman")
registerFontFamily("Consolas", normal="Consolas", bold="Consolas")

from pdf import install_font_fallback  # noqa: E402
install_font_fallback()

# ---------- 调色板（palette.cascade 生成，禁止手改） ----------
PAGE_BG = colors.HexColor("#f6f6f5")
SECTION_BG = colors.HexColor("#eeedec")
CARD_BG = colors.HexColor("#efeeec")
TABLE_STRIPE = colors.HexColor("#f0f0ed")
HEADER_FILL = colors.HexColor("#6f6341")
COVER_BLOCK = colors.HexColor("#6d6447")
BORDER = colors.HexColor("#d6d1c1")
ICON = colors.HexColor("#786c47")
ACCENT = colors.HexColor("#2188ab")
ACCENT2 = colors.HexColor("#40a640")
TEXT_PRIMARY = colors.HexColor("#242321")
TEXT_MUTED = colors.HexColor("#898780")

# ---------- 样式 ----------
S = {}
S["h1"] = ParagraphStyle("H1", fontName="Microsoft YaHei", fontSize=19, leading=27,
                         textColor=TEXT_PRIMARY, spaceBefore=18, spaceAfter=8, wordWrap="CJK")
S["h2"] = ParagraphStyle("H2", fontName="Microsoft YaHei", fontSize=14.5, leading=21,
                         textColor=TEXT_PRIMARY, spaceBefore=14, spaceAfter=6, wordWrap="CJK")
S["h3"] = ParagraphStyle("H3", fontName="Microsoft YaHei", fontSize=11.5, leading=17,
                         textColor=ACCENT, spaceBefore=10, spaceAfter=4, wordWrap="CJK")
S["body"] = ParagraphStyle("Body", fontName="SimHei", fontSize=10.5, leading=16,
                           textColor=TEXT_PRIMARY, alignment=TA_LEFT, spaceAfter=5, wordWrap="CJK")
S["bullet"] = ParagraphStyle("Bullet", fontName="SimHei", fontSize=10.5, leading=15.5,
                             textColor=TEXT_PRIMARY, leftIndent=14, spaceAfter=2, wordWrap="CJK")
S["code"] = ParagraphStyle("Code", fontName="Consolas", fontSize=8.5, leading=12.5,
                           textColor=TEXT_PRIMARY, backColor=CARD_BG, borderPadding=6,
                           leftIndent=8, spaceBefore=4, spaceAfter=8, wordWrap="CJK")
S["th"] = ParagraphStyle("TH", fontName="SimHei", fontSize=9.5, leading=13.5,
                         textColor=colors.white, alignment=TA_CENTER, wordWrap="CJK")
S["td"] = ParagraphStyle("TD", fontName="SimHei", fontSize=9.5, leading=13.5,
                         textColor=TEXT_PRIMARY, alignment=TA_LEFT, wordWrap="CJK")
S["quote"] = ParagraphStyle("Quote", fontName="SimHei", fontSize=10, leading=16,
                            textColor=TEXT_MUTED, leftIndent=20, spaceAfter=6, wordWrap="CJK")
S["toc_t"] = ParagraphStyle("TOCTitle", fontName="Microsoft YaHei", fontSize=18, leading=26,
                            textColor=TEXT_PRIMARY, spaceAfter=10, wordWrap="CJK")

AVAILABLE_W = A4[0] - 2 * 2.0 * cm
H1_ORPHAN = (A4[1] - 2 * 2.0 * cm) * 0.15


def esc(t):
    t = t.replace("&", "&").replace("<", "<").replace(">", ">")
    t = t.replace("⚠️", "【高危】").replace("⚠", "【高危】")
    t = t.replace("✓", "√").replace("✗", "×")
    t = t.replace("→", "->")
    return t


class TocDoc(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if hasattr(flowable, "bookmark_name"):
            self.notify("TOCEntry", (getattr(flowable, "bookmark_level", 0),
                                     getattr(flowable, "bookmark_text", ""),
                                     self.page,
                                     getattr(flowable, "bookmark_key", "")))


def heading(text, level):
    text = text.replace("⚠️", "【高危】").replace("⚠", "【高危】")
    key = "h_" + hashlib.md5(text.encode()).hexdigest()[:8]
    style = S["h1"] if level == 0 else (S["h2"] if level == 1 else S["h3"])
    p = Paragraph('<a name="%s"/><b>%s</b>' % (key, esc(text)), style)
    p.bookmark_name = text
    p.bookmark_level = level
    p.bookmark_text = text
    p.bookmark_key = key
    return p


def inline_md(t):
    t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"`(.+?)`", r'<font name="Consolas">\1</font>', t)
    return t


def md_table(rows, widths_ratio=None):
    header = rows[0]
    body = [r for r in rows[1:] if not re.match(r"^[\s\-:|]+$", "|".join(r))]
    ncol = len(header)
    if widths_ratio is None:
        widths_ratio = [1.0 / ncol] * ncol
    data = [[Paragraph("<b>%s</b>" % esc(c.strip()), S["th"]) for c in header]]
    for r in body:
        r = (r + [""] * ncol)[:ncol]
        data.append([Paragraph(inline_md(esc(c.strip())), S["td"]) for c in r])
    widths = [w * AVAILABLE_W for w in widths_ratio]
    t = Table(data, colWidths=widths, hAlign="CENTER", repeatRows=1)
    style = [
        ("BACKGROUND", (0, 0), (-1, 0), HEADER_FILL),
        ("GRID", (0, 0), (-1, -1), 0.5, BORDER),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4), ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    for i in range(1, len(data)):
        style.append(("BACKGROUND", (0, i), (-1, i), colors.white if i % 2 else TABLE_STRIPE))
    t.setStyle(TableStyle(style))
    return t


def code_block(text):
    # Consolas 无中文字形，CJK 段落回退到黑体（否则静默丢字）
    t = re.sub(r"[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef\u2014\u2018\u2019\u201c\u201d]+",
               lambda m: '<font name="SimHei">' + m.group(0) + "</font>", esc(text))
    return Paragraph(t.replace("\n", "<br/>"), S["code"])


def bullets(items):
    return [Paragraph("• " + inline_md(esc(x)), S["bullet"]) for x in items]


def footer(canv, doc_):
    canv.saveState()
    canv.setFont("SimHei", 7.5)
    canv.setFillColor(TEXT_MUTED)
    canv.drawString(2.0 * 28.35, 1.1 * 28.35, "Prism")
    canv.drawRightString(A4[0] - 2.0 * 28.35, 1.1 * 28.35, str(canv.getPageNumber()))
    canv.restoreState()


def parse_md_blocks(path):
    """把 INTERVIEW.md 解析成 (kind, payload) 流"""
    out = []
    lines = open(path, encoding="utf-8").read().splitlines()
    i, in_code = 0, False
    while i < len(lines):
        ln = lines[i]
        if ln.startswith("```"):
            in_code = not in_code
            i += 1
            continue
        if in_code:
            out.append(("code", ln))
            i += 1
            continue
        if ln.startswith("### "):
            out.append(("h3", ln[4:].strip()))
        elif ln.startswith("## "):
            out.append(("h2", ln[3:].strip()))
        elif ln.startswith("# "):
            out.append(("h1", ln[2:].strip()))
        elif ln.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c for c in lines[i].strip().strip("|").split("|")])
                i += 1
            out.append(("table", rows))
            continue
        elif re.match(r"^- ", ln):
            out.append(("bullet", ln[2:].strip()))
        elif ln.startswith("> "):
            out.append(("quote", ln[2:].strip()))
        elif re.match(r"^-{3,}$", ln.strip()):
            i += 1
            continue
        elif ln.strip():
            out.append(("body", ln.strip()))
        i += 1
    return out


def build_handbook():
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "INTERVIEW.md")
    blocks = parse_md_blocks(src)
    doc = TocDoc(r"..\Prism面试技术拷打手册.pdf", pagesize=A4,
                 leftMargin=2.0 * cm, rightMargin=2.0 * cm,
                 topMargin=2.2 * cm, bottomMargin=2.0 * cm,
                 title="Prism 面试技术拷打手册", author="夏梦楠",
                 creator="Z.ai", subject="Prism 项目面试拷打问答")
    story = [Paragraph("<b>目 录</b>", S["toc_t"])]
    toc = TableOfContents()
    toc.levelStyles = [
        ParagraphStyle("T1", fontName="Microsoft YaHei", fontSize=11.5, leading=18, leftIndent=6),
        ParagraphStyle("T2", fontName="SimHei", fontSize=10, leading=15, leftIndent=24,
                       textColor=TEXT_MUTED),
    ]
    story.append(toc)
    story.append(PageBreak())

    qno = 0
    for kind, payload in blocks:
        if kind == "h1":
            continue
        if kind == "h2":
            story.extend([CondPageBreak(H1_ORPHAN), heading(payload, 0)])
        elif kind == "h3":
            qno += 1
            story.extend([CondPageBreak(H1_ORPHAN * 0.7),
                          heading("%d. %s" % (qno, payload), 1)])
        elif kind == "bullet":
            story.extend(bullets([payload]))
        elif kind == "body":
            story.append(Paragraph(inline_md(esc(payload)), S["body"]))
        elif kind == "quote":
            story.append(Paragraph(inline_md(esc(payload)), S["quote"]))
        elif kind == "code":
            story.append(code_block(payload))
        elif kind == "table":
            n = len(payload[0])
            ratio = [0.22, 0.78] if n == 2 else None
            story.append(Spacer(1, 6))
            story.append(md_table(payload, ratio))
            story.append(Spacer(1, 8))
    doc.multiBuild(story, onFirstPage=footer, onLaterPages=footer)
    print("handbook body ok, questions:", qno)


TABLE_R = {
    "模块表": [0.16, 0.10, 0.42, 0.32],
    "亮点表": [0.22, 0.78],
    "表清单": [0.28, 0.20, 0.52],
    "实测表": [0.18, 0.44, 0.38],
    "边界表": [0.30, 0.70],
}


def build_project_doc():
    doc = TocDoc(r"..\Prism项目说明文档.pdf", pagesize=A4,
                 leftMargin=2.0 * cm, rightMargin=2.0 * cm,
                 topMargin=2.2 * cm, bottomMargin=2.0 * cm,
                 title="Prism 项目技术说明文档", author="夏梦楠",
                 creator="Z.ai", subject="Prism LLM 能力开放平台技术说明")
    story = [Paragraph("<b>目 录</b>", S["toc_t"])]
    toc = TableOfContents()
    toc.levelStyles = [
        ParagraphStyle("T1", fontName="Microsoft YaHei", fontSize=11.5, leading=18, leftIndent=6),
        ParagraphStyle("T2", fontName="SimHei", fontSize=10, leading=15, leftIndent=24,
                       textColor=TEXT_MUTED),
    ]
    story.append(toc)
    story.append(PageBreak())

    def h1(t):
        story.extend([CondPageBreak(H1_ORPHAN), heading(t, 0)])

    def h2(t):
        story.extend([CondPageBreak(H1_ORPHAN * 0.7), heading(t, 1)])

    def body(t):
        story.append(Paragraph(inline_md(esc(t)), S["body"]))

    def table(rows, key):
        story.append(Spacer(1, 6))
        story.append(md_table(rows, TABLE_R[key]))
        story.append(Spacer(1, 8))

    # 1 项目定位
    h1("一、项目定位")
    body("Prism 是一个 LLM 能力开放平台（AI 中台网关）：对外提供完全 OpenAI 兼容的 /v1/chat/completions "
         "接口，客户端只需把 base_url 与 API Key 换成 Prism 即可接入；对内实现多模型渠道路由与故障转移、"
         "Redis+Lua 令牌桶限流、语义缓存降本、Token 计量计费与月度配额管控，并内置 LangChain RAG 知识库"
         "问答（引用溯源）与 ReAct Agent 工具调用能力。")
    body("选型动机：企业接入大模型的真痛点不在调通 API，而在接入之后的治理——密钥、限流、成本、多模型切换、"
         "知识库统一。Java 微服务做工程底座，Python/LangChain 做 AI 编排，对应企业里业务中台与 AI 编排"
         "服务的真实分工；相对市面常见的商城/秒杀类项目，该题目在校招简历中几乎不撞车。")

    # 2 架构
    h1("二、架构总览")
    table([
        ["模块", "端口", "职责", "技术栈"],
        ["prism-gateway", "8080", "JWT/API Key 鉴权、Redis+Lua 令牌桶限流、路由转发、SSE 透传、内网保护",
         "Spring Cloud Gateway (WebFlux)"],
        ["prism-user", "8101", "账号、应用与 API Key 生命周期、模型渠道管理、种子数据",
         "Boot MVC + MyBatis-Plus + BCrypt"],
        ["prism-chat", "8102", "OpenAI 兼容推理：配额预检、语义缓存、加权路由+故障转移、SSE 流式透传、用量事件",
         "Boot WebFlux + Caffeine + Redis"],
        ["prism-billing", "8103", "用量消费（幂等/攒批）、月配额计数、每日对账、月度账单、看板聚合",
         "Boot MVC + Redis Stream/RabbitMQ"],
        ["prism-kb", "8104", "知识库/文档管理、解析任务派发、解析回调落库、检索与 RAG 代理（SSE 透传）",
         "Boot MVC + JDK HttpClient"],
        ["agent-service", "8200", "文档解析入库（切分/embedding/Chroma）、RAG 问答、ReAct Agent",
         "FastAPI + LangChain + Chroma"],
        ["frontend", "5173/80", "控制台 10 页面：仪表盘/应用/渠道/用量/知识库/RAG/Playground/Agent",
         "Vue3 + Vite + Pinia + Element Plus + ECharts"],
    ], "模块表")
    body("运行形态双轨：local profile 仅依赖 MySQL+Redis（MQ 以 Redis Stream 实现，含消费组/ACK/"
         "pending 认领语义），可离线跑通全链路；compose profile 一键拉起 Nacos、RabbitMQ、Nginx "
         "（静态托管 + 反代 + SSE 不缓冲 + 接入层 limit_req）的完整生产拓扑。")

    # 3 核心设计
    h1("三、核心技术设计")
    h2("3.1 网关层：鉴权与令牌桶限流")
    story.extend(bullets([
        "双鉴权：/v1 走 API Key（Redis hash 存应用信息，miss 回源 user 服务回填）；/api 走 JWT "
        "（网关验签后透传 X-User-Id，并剥离外部伪造的同名头）。",
        "令牌桶限流：Lua 脚本原子完成按流逝时间补令牌+扣减，key 为 prism:rl:{appKey}，"
        "rate 取应用速率、容量为 2 倍速率允许受控突发；集群安全（Redis 集中执行）。",
        "错误语义分级：/v1 返回 OpenAI 风格 error 对象（429 rate_limit_error / 401 invalid_api_key），"
        "/api 返回统一 Result 结构；/internal 外部一律 403。",
    ]))
    h2("3.2 语义缓存")
    story.extend(bullets([
        "prompt 归一化（去空白/转小写）后向量化：默认 512 维本地 bigram 哈希 TF 向量（离线可跑），"
        "可配置切换远程 embedding 服务（agent-service /embed）。",
        "余弦相似度与该模型槽位全部缓存条目取最大，阈值 0.92 命中即回放，重复问答零上游成本；"
        "命中标记 cacheHit=true 进入看板统计。",
        "已知边界：单机 Caffeine（256 条/1h TTL），多实例应下沉 Redis Hash；误命中风险通过"
        "保守阈值 + cacheHit 可观测 + 高风险场景跳过缓存缓解。",
    ]))
    h2("3.3 多渠道路由与故障转移")
    story.extend(bullets([
        "同名模型（alias）多渠道加权随机；渠道列表走 Redis 缓存（TTL 60s + 变更主动失效双保险）。",
        "健康摘除：连续失败 3 次 INCR 计数（TTL 5min 到期自动恢复=半开），调用侧对上游 5xx/超时"
        "透明重试，最多换 2 个渠道。",
    ]))
    h2("3.4 WebFlux SSE 流式透传")
    story.extend(bullets([
        "全链路响应式：上游 delta 逐块转发的同时增量累计 token（CJK+1、其他/4，上游真实 usage 优先），"
        "流结束 doFinally 发布用量事件；不缓冲整包、不聚合响应体。",
        "客户端断连 doOnCancel 取消上游订阅，止血省 token。",
    ]))
    h2("3.5 异步计量与一致性")
    story.extend(bullets([
        "用量事件走消息队列（local=Redis Stream MAXLEN 10000；compose=RabbitMQ 直连交换机+DLQ），"
        "推理主链路不被写库拖累。",
        "消费端双重幂等：Redis SETNX(msgId) 快速去重 + t_usage_record 唯一索引兜底；幂等顺序为"
        "先查后置，落库前崩溃由重投+唯一索引吸收。",
        "攒批入库：满 500 条或 2 秒批量 INSERT 削峰；账单是流水表的派生视图，出账前先对账。",
        "对账：每日 02:30 按应用汇总 DB 流水 vs Redis 计数，diff 落 t_reconcile_diff 留痕并以 "
        "DB 为准覆写修正。",
    ]))
    h2("3.6 跨语言 RAG 编排")
    story.extend(bullets([
        "Java 管编排与状态：上传白名单校验+路径穿越清洗，派发任务 {docId, kbId}，回调驱动状态机 "
        "PENDING-PARSING-READY/FAILED（chunks 先删后插幂等）。",
        "Python 管模型生态：RecursiveCharacterTextSplitter 中文分隔符优先切分，Chroma cosine 空间"
        "存储（uuid5 幂等 upsert），RAG 回答强制编号引用并回传 citations 事件。",
        "可靠性：任务处理成功才 ACK，回调不可达留在 pending 重投；MockReActChatModel 走真实 "
        "AgentExecutor 循环，离线演示与真实 LLM 共用同一链路。",
    ]))

    # 4 数据模型
    h1("四、数据模型（MySQL prism 库）")
    table([
        ["表", "关键字段/约束", "说明"],
        ["t_user / t_app", "uk_username；uk_app_key；rate_limit/monthly_quota", "控制台账号与接入应用（API Key=sk-prism-+32hex）"],
        ["t_model_channel", "idx_alias；weight/status", "上游渠道：同 alias 加权随机"],
        ["t_usage_record", "uk_msg_id；(app_id,created_at)；idx_time", "用量流水：唯一索引是幂等最后防线"],
        ["t_bill", "uk_app_month", "月度账单（流水派生视图，可重算）"],
        ["t_reconcile_diff", "idx_day", "对账差异留痕（DB 为准覆写 Redis）"],
        ["t_failed_message", "—", "毒消息/消费失败兜底"],
        ["t_kb / t_document / t_doc_chunk", "document 状态机；chunk (doc,kb) 索引", "知识库域：向量在 Chroma，原文冗余存 MySQL"],
    ], "表清单")

    # 5 快速开始
    h1("五、快速开始（local 模式）")
    story.append(code_block(
        "mysql -h127.0.0.1 -uroot -p < sql/init.sql\n"
        "mvn clean package -DskipTests\n"
        "java -jar prism-user/target/prism-user-1.0.0.jar\n"
        "java -jar prism-gateway/target/prism-gateway-1.0.0.jar\n"
        "java -jar prism-chat/target/prism-chat-1.0.0.jar\n"
        "java -jar prism-billing/target/prism-billing-1.0.0.jar\n"
        "java -jar prism-kb/target/prism-kb-1.0.0.jar\n"
        "cd agent-service && .venv/Scripts/uvicorn app.main:app --port 8200\n"
        "cd frontend && npm i && npm run dev   # admin / admin123"))
    body("60 秒体验 OpenAI 兼容接口（内置 mock 渠道离线可跑）：")
    story.append(code_block(
        'curl -N http://localhost:8080/v1/chat/completions \\\n'
        '  -H "Authorization: Bearer sk-prism-demo0000demo0000demo0000demo0000" \\\n'
        '  -H "Content-Type: application/json" \\\n'
        '  -d \'{"model":"prism-demo","messages":[{"role":"user",'
        '"content":"用一句话介绍你自己"}],"stream":true}\''))

    # 6 实测
    h1("六、实测验收记录（2026-10-11 真实运行）")
    table([
        ["验收项", "实测过程", "实测结果"],
        ["登录与 JWT", "login admin/admin123 取 token，经网关访问 /api/apps", "签发 HS384 JWT；应用列表含月已用 token 与配额进度"],
        ["鉴权与内网保护", "无 Key POST /v1；外部访问 /internal", "401 invalid_api_key；403 拒绝"],
        ["OpenAI 兼容 SSE", "经网关流式请求 prism-demo 模型", "逐块输出 chat.completion.chunk，末尾 usage+[DONE]"],
        ["语义缓存", "同 prompt 重复请求两次", "第二次 cache_hit=1 落库（看板命中率 28.57%）"],
        ["计量计费链路", "XADD 造消息+真实调用事件", "流水入库；summary 聚合 7 calls/843 tokens/按模型与 topApps 分组"],
        ["对账修正", "重复投递使计数器漂移 500 vs 250", "reconcile diffCount=1，以 DB 为准覆写并留痕"],
        ["RAG 全链路", "上传 txt → 解析 → 检索 → 流式问答", "状态机 READY；检索 score=0.255；回答 102 字带引用溯源"],
        ["ReAct Agent", "goal 混合任务（检索+计算）", "22 个 SSE 事件：thought/action(kb_search)/observation/answer"],
        ["单测", "mvn test + pytest", "Java 68（user 13/chat 41/billing 7/kb 7）+ Python 28，全绿"],
    ], "实测表")

    # 7 边界
    h1("七、已知边界与演进方向")
    table([
        ["已知边界", "演进方案"],
        ["单库多服务（user/billing/kb 共库）", "按服务拆库；跨服务数据走事件快照字段或服务间查询+缓存"],
        ["配额软限制（预检只看已用量）", "预扣-确认-回补硬化（每请求两次 Redis 往返，演示场景收益不成比例）"],
        ["语义缓存单机内存", "下沉 Redis Hash 或专用向量库（接口已抽象，配置级改造）"],
        ["Token 为估算", "接入真实 tokenizer 离线校准系数；上游 usage 优先已实现"],
        ["内网头信任模型", "mTLS 或 HMAC 签名转发头；密钥 KMS 化"],
        ["无监控体系", "Prometheus 指标+traceId 贯穿网关到消费端"],
    ], "边界表")
    doc.multiBuild(story, onFirstPage=footer, onLaterPages=footer)
    print("project doc ok")


if __name__ == "__main__":
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    build_handbook()
    build_project_doc()
