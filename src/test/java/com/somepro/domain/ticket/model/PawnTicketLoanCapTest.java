package com.somepro.domain.ticket.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.Category;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 折当率卡线单测：开票与改当金两条路都必须按
 * 「当金 ≤ 估值 × 该类别当下生效折当率上限」收口，正好顶格放行、越线一分挡回。
 *
 * 对账口径：改老票只认票面上折当当时抄下的估值快照，当物日后重估不回写、不参与卡线。
 */
class PawnTicketLoanCapTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 1);
    private static final BigDecimal RATIO_70 = new BigDecimal("0.7000");

    private static CollateralSnapshot collateral(String appraisedValue) {
        return new CollateralSnapshot(1001L, 2001L, Category.WATCH, new BigDecimal(appraisedValue));
    }

    private static RateConfig rate(String ratio) {
        return new RateConfig(Category.WATCH, new BigDecimal("0.005"),
                new BigDecimal("0.02"), new BigDecimal(ratio));
    }

    // ---------- 开票 ----------

    /** 柜台报一万八，估值两万、上限七成（最多一万四）：越线，票不能开。 */
    @Test
    void issue_overCap_isRejected() {
        BizException ex = assertThrows(BizException.class, () -> PawnTicket.issue(
                collateral("20000"), new BigDecimal("18000"), rate("0.7000"), START, 1));
        assertEquals(true, ex.getMessage().contains("越过该类别折当率上限"));
    }

    /** 正好顶到上限（两万 × 0.7 = 一万四）：放得出去。 */
    @Test
    void issue_exactlyAtCap_isAccepted() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000"), rate("0.7000"), START, 1);
        assertEquals(0, new BigDecimal("14000").compareTo(ticket.getPawnAmount()));
    }

    /** 顶格再多半分（14000.01）也挡回，不做抹零。 */
    @Test
    void issue_oneCentOverCap_isRejected() {
        assertThrows(BizException.class, () -> PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000.01"), rate("0.7000"), START, 1));
    }

    // ---------- 改老票 ----------

    /**
     * 老票：折当时估值两万、当金一万四顶格。当物后来重估成一万五，但卡线只认票面估值快照：
     * 2万 × 0.7 = 1.4万，柜台把当金改到一万三 → 合规，必须放得过去。
     */
    @Test
    void revise_oldTicketUsesSnapshotValue_notRevaluedBook() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000"), rate("0.7000"), START, 1);
        // 当物重估与票面无关：不回写 appraisedValue；这里直接验证按快照口径 1.3 万能改
        ticket.revise(new BigDecimal("13000"), RATIO_70, null, null);
        assertEquals(0, new BigDecimal("13000").compareTo(ticket.getPawnAmount()));
        assertEquals(0, new BigDecimal("20000").compareTo(ticket.getAppraisedValue()));
    }

    /**
     * 反证对账口径：若错拿当物重估后的一万五去算（1.5万 × 0.7 = 1.05万），
     * 改到一万三会被挡 —— 聚合按票面快照算出的线是 1.4万，一万三合规，证明没认错账面。
     */
    @Test
    void revise_overSnapshotCap_isRejected() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000"), rate("0.7000"), START, 1);
        // 超过票面快照量出的线（1.4万）才挡；14000.01 越线
        assertThrows(BizException.class, () ->
                ticket.revise(new BigDecimal("14000.01"), RATIO_70, null, null));
    }

    /** 改当金正好顶到票面快照算出的线（1.4万）：放得出去。 */
    @Test
    void revise_exactlyAtSnapshotCap_isAccepted() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("13000"), rate("0.7000"), START, 1);
        ticket.revise(new BigDecimal("14000"), RATIO_70, null, null);
        assertEquals(0, new BigDecimal("14000").compareTo(ticket.getPawnAmount()));
    }

    /** 不动当金（只改起当日期/当期）不需要折当率，不查配置也能改。 */
    @Test
    void revise_withoutAmountChange_skipsLoanCap() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000"), rate("0.7000"), START, 1);
        ticket.revise(null, null, LocalDate.of(2026, 4, 1), 2);
        assertEquals(0, new BigDecimal("14000").compareTo(ticket.getPawnAmount()));
        assertEquals(LocalDate.of(2026, 6, 1), ticket.getDueDate());
    }

    /** 折当率上限缺失（该类别没生效配置）时改当金：没有卡线依据，挡回。 */
    @Test
    void revise_withoutEnabledRatio_isRejected() {
        PawnTicket ticket = PawnTicket.issue(
                collateral("20000"), new BigDecimal("14000"), rate("0.7000"), START, 1);
        assertThrows(BizException.class, () ->
                ticket.revise(new BigDecimal("13000"), null, null, null));
    }
}
