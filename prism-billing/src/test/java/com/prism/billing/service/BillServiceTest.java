package com.prism.billing.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 账单计价与月份解析（纯函数，无外部依赖） */
class BillServiceTest {

    @Test
    void calcAmount_差异化计价() {
        // prompt 1000 × 0.002/1k + completion 1000 × 0.004/1k = 0.0060
        assertEquals(new BigDecimal("0.0060"), BillService.calcAmount(1000, 1000));
        assertEquals(new BigDecimal("0.0000"), BillService.calcAmount(0, 0));
        // 500 prompt = 0.0010；250 completion = 0.0010
        assertEquals(new BigDecimal("0.0020"), BillService.calcAmount(500, 250));
    }

    @Test
    void calcAmount_非整数千位_四舍五入() {
        // 333 → 0.333×0.002=0.000666 → 0.0007
        assertEquals(new BigDecimal("0.0007"), BillService.calcAmount(333, 0));
    }

    @Test
    void normalize_两种月份格式与缺省() {
        assertEquals("2026-10", BillService.normalize("2026-10"));
        assertEquals("2026-10", BillService.normalize("202610"));
        assertEquals(YearMonth.now().toString(), BillService.normalize(""));
        assertEquals(YearMonth.now().toString(), BillService.normalize(null));
    }

    @Test
    void parse_回环() {
        assertEquals(YearMonth.of(2026, 10), BillService.parse("2026-10"));
        assertEquals(YearMonth.of(2026, 10), BillService.parse("202610"));
    }
}
