package com.somepro.domain.ticket.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.Category;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 当票聚合根（纯领域对象，不带任何持久化注解）。
 *
 * 一条记录 = 一张当票。核心不变量：
 * 1. 票面上的一切「随物带出」与「随配置抄录」都在开票当下定格：当户以当物底账为准、
 *    类别与估值是当物快照、月利率与月综合费率是该类别当时费率配置的快照 ——
 *    日后当物档案或费率配置再改，都不回写已开出的票，对账才对得平；
 * 2. 当金必须是正数，且不得越过「估值 × 该类别折当率上限」：顶到上限那一档照常放行，
 *    越线一分也挡回（开票与改当金同一把尺子）；
 * 3. 到期日期不由人填，由起当日期按当期月数往后推算（plusMonths）；
 * 4. 新开的票默认在当（ACTIVE）；只有在当的票才能修改、才能撤销，
 *    已赎 / 已绝当 / 已撤销的都是定了案的历史票；
 * 5. 一件当物同一时刻只准挂在一张没结清（ACTIVE）的票上 —— 这是跨聚合的并发约束，
 *    由仓储在写锁内落库时保证，本聚合只管单票自身的规则。
 *
 * 票号 ticketNo（DP-2026-0001 样式）由仓储按当年序号生成，全局唯一、一票一号。
 */
@Getter
@Setter
public class PawnTicket extends BaseEntity {

    private Long id;

    /** 当票号，如 DP-2026-0001；开票时由仓储生成，业务上不可改。 */
    private String ticketNo;

    /** 当户 id（t_pawner.id），随当物底账带出。 */
    private Long pawnerId;

    /** 押的是哪件当物（t_collateral.id）。 */
    private Long collateralId;

    /** 类别快照，随当物带出。 */
    private Category category;

    /** 当金（元），正数。 */
    private BigDecimal pawnAmount;

    /** 折当时估值快照（元），开票当下从当物抄录。 */
    private BigDecimal appraisedValue;

    /** 月利率快照，开票当下从该类别费率配置抄录。 */
    private BigDecimal monthlyRate;

    /** 月综合费率快照，开票当下从该类别费率配置抄录。 */
    private BigDecimal serviceRate;

    private LocalDate startDate;

    /** 到期日期 = 起当日期 + 当期月数，推算得出，不接受指定。 */
    private LocalDate dueDate;

    /** 当期月数，至少 1 个月。 */
    private Integer termMonths;

    private TicketStatus status;

    /**
     * 工厂方法：开立当票。默认落在当（ACTIVE）。
     *
     * @param collateral 当物快照（当户、类别、估值以它为准）
     * @param pawnAmount 当金，正数
     * @param rate       该类别当前生效的费率配置，利率费率抄作快照
     * @param startDate  起当日期（缺省值由应用层补，这里只认非空）
     * @param termMonths 当期月数，至少 1
     */
    public static PawnTicket issue(CollateralSnapshot collateral, BigDecimal pawnAmount,
                                   RateConfig rate, LocalDate startDate, Integer termMonths) {
        if (collateral == null) {
            throw new BizException("必须指定押的是哪件当物");
        }
        requirePositiveAmount(pawnAmount);
        if (rate == null) {
            throw new BizException("缺少该类别生效的费率配置，不能开票");
        }
        requireWithinLoanCap(pawnAmount, collateral.appraisedValue(), rate.maxLoanRatio());
        if (startDate == null) {
            throw new BizException("起当日期不能为空");
        }
        requireTerm(termMonths);

        PawnTicket ticket = new PawnTicket();
        ticket.pawnerId = collateral.pawnerId();
        ticket.collateralId = collateral.id();
        ticket.category = collateral.category();
        ticket.pawnAmount = pawnAmount;
        ticket.appraisedValue = collateral.appraisedValue();
        ticket.monthlyRate = rate.monthlyRate();
        ticket.serviceRate = rate.serviceRate();
        ticket.startDate = startDate;
        ticket.termMonths = termMonths;
        ticket.dueDate = startDate.plusMonths(termMonths);
        ticket.status = TicketStatus.ACTIVE;
        return ticket;
    }

    /**
     * 修改票面上的当金 / 起当日期 / 当期月数。只有在当的票才改得动；
     * 票号、当户、当物、类别、估值与利率费率快照永不改（改了票就和账对不上）。
     *
     * @param newPawnAmount 新当金；null 表示不动，非 null 必须是正数且不越折当率上限
     * @param maxLoanRatio  该类别当前生效的折当率上限（由应用层实时点配置带入，
     *                      不落票）；newPawnAmount 为 null 时本项忽略，可传 null
     * @param newStartDate  新起当日期；null 表示不动
     * @param newTermMonths 新当期月数；null 表示不动，非 null 至少 1
     */
    public void revise(BigDecimal newPawnAmount, BigDecimal maxLoanRatio,
                       LocalDate newStartDate, Integer newTermMonths) {
        requireActive("修改");
        if (newPawnAmount != null) {
            requirePositiveAmount(newPawnAmount);
            // 改当金与开票同一把尺子：估值以票上快照为准，上限以该类别当前配置为准
            requireWithinLoanCap(newPawnAmount, this.appraisedValue, maxLoanRatio);
            this.pawnAmount = newPawnAmount;
        }
        if (newStartDate != null) {
            this.startDate = newStartDate;
        }
        if (newTermMonths != null) {
            requireTerm(newTermMonths);
            this.termMonths = newTermMonths;
        }
        // 起当日期或当期变了，到期日期跟着重算；都没变时重算结果与原值一致，无副作用
        this.dueDate = this.startDate.plusMonths(this.termMonths);
    }

    /**
     * 撤销：开错的票作废。只有在当的票才撤得掉；撤掉后当物回到在库（由仓储同事务联动）。
     * 撤销时刻由审计字段 update_time 落账。
     */
    public void cancel() {
        requireActive("撤销");
        this.status = TicketStatus.CANCELLED;
    }

    public boolean isActive() {
        return status == TicketStatus.ACTIVE;
    }

    /** 只有在当的票才办得动这个操作；已赎 / 已绝当 / 已撤销都是定了案的历史票。 */
    private void requireActive(String action) {
        if (this.status != TicketStatus.ACTIVE) {
            throw new BizException("只有在当的当票才能" + action + "，当前状态："
                    + (this.status == null ? "-" : this.status.label()));
        }
    }

    /** 当金必须为正数：null、零、负数一律不收。 */
    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("当金必须是正数，零和负数一律不收");
        }
    }

    /**
     * 折当率上限核算：当金 ≤ 估值 × 上限。compareTo 只挡「严格大于」，顶到上限那一档
     * 照常放行，越线一分也挡回；compareTo 不看小数位，14000 与 14000.000000 算平。
     */
    private static void requireWithinLoanCap(BigDecimal amount, BigDecimal appraisedValue,
                                             BigDecimal maxLoanRatio) {
        if (appraisedValue == null || maxLoanRatio == null) {
            throw new BizException("缺少估值或折当率上限，无法核算当金上限，不能放当");
        }
        BigDecimal cap = appraisedValue.multiply(maxLoanRatio);
        if (amount.compareTo(cap) > 0) {
            throw new BizException("当金超过折当率上限：估值 " + appraisedValue.toPlainString()
                    + " 元 × 上限 " + maxLoanRatio.toPlainString()
                    + "，最多放 " + cap.stripTrailingZeros().toPlainString()
                    + " 元，本次 " + amount.toPlainString() + " 元越线，一分别放");
        }
    }

    private static void requireTerm(Integer termMonths) {
        if (termMonths == null) {
            throw new BizException("当期月数不能为空");
        }
        if (termMonths < 1) {
            throw new BizException("当期月数至少 1 个月");
        }
    }
}
