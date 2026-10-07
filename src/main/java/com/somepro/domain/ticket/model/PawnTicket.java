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
 * 2. 当金必须是正数；
 * 3. 当金不得越过折当率上限量出的那条线：当金 ≤ 估值 × 该类别【当下生效】折当率上限，
 *    正好顶到上限仍放得出去，越一分都不放。开票用当物账面当下估值（快照即此刻抄录）；
 *    改老票用票面上折当当时抄下的估值快照 —— 当物日后重估不回写票面，卡线只跟票自己的
 *    账面算，改完的票才不会跟自己的估值打架。折当率不落票，每次办理都按类别现查；
 * 4. 到期日期不由人填，由起当日期按当期月数往后推算（plusMonths）；
 * 5. 新开的票默认在当（ACTIVE）；只有在当的票才能修改、才能撤销，
 *    已赎 / 已绝当 / 已撤销的都是定了案的历史票；
 * 6. 一件当物同一时刻只准挂在一张没结清（ACTIVE）的票上 —— 这是跨聚合的并发约束，
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
     * @param collateral 当物快照（当户、类别、估值以它为准；卡折当率也用这份当下估值）
     * @param pawnAmount 当金，正数，且不得越过估值 × 折当率上限
     * @param rate       该类别【当下生效】的费率配置：利率费率抄作票面快照，
     *                   折当率上限只用来卡当金、不落票
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
        requireLoanRatio(rate.maxLoanRatio());
        if (startDate == null) {
            throw new BizException("起当日期不能为空");
        }
        requireTerm(termMonths);
        requireWithinLoanCap(pawnAmount, collateral.appraisedValue(), rate.maxLoanRatio());

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
     * 改当金同样受折当率卡线：用票面上折当当时抄下的估值快照（不是当物今天的重估值）
     * 乘以该类别【当下生效】的折当率上限，越过即挡；不动当金时无需也不会去查配置。
     *
     * @param newPawnAmount 新当金；null 表示不动，非 null 必须是正数且不越折当率上限
     * @param maxLoanRatio  当金要动时该类别当下生效的折当率上限；当金不动时可传 null
     * @param newStartDate  新起当日期；null 表示不动
     * @param newTermMonths 新当期月数；null 表示不动，非 null 至少 1
     */
    public void revise(BigDecimal newPawnAmount, BigDecimal maxLoanRatio,
                       LocalDate newStartDate, Integer newTermMonths) {
        requireActive("修改");
        if (newPawnAmount != null) {
            requirePositiveAmount(newPawnAmount);
            requireLoanRatio(maxLoanRatio);
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

    /** 折当率上限：配置端口现查得到才谈得上卡线，且必须落在 (0,1]。 */
    private static void requireLoanRatio(BigDecimal maxLoanRatio) {
        if (maxLoanRatio == null) {
            throw new BizException("该当物所属类别没有生效的费率配置，取不到折当率上限，不能办理");
        }
        if (maxLoanRatio.compareTo(BigDecimal.ZERO) <= 0
                || maxLoanRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException("折当率上限配置不合法（必须大于 0 且不超过 1）："
                    + maxLoanRatio.toPlainString());
        }
    }

    /**
     * 折当率卡线：当金不得超过 估值 × 折当率上限。
     * 精确比较（不做进位/抹零），正好顶到上限放得出去，越一分都挡回。
     */
    private static void requireWithinLoanCap(BigDecimal pawnAmount,
                                             BigDecimal appraisedValue, BigDecimal maxLoanRatio) {
        if (appraisedValue == null || appraisedValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("当物估值缺失或不是正数，无法按折当率核算当金");
        }
        BigDecimal cap = appraisedValue.multiply(maxLoanRatio);
        if (pawnAmount.compareTo(cap) > 0) {
            throw new BizException("当金 " + pawnAmount.toPlainString()
                    + " 越过该类别折当率上限（估值 " + appraisedValue.toPlainString()
                    + " × " + maxLoanRatio.stripTrailingZeros().toPlainString()
                    + " = 最多可放 " + displayMoney(cap) + "），越线一分都不能放");
        }
    }

    /**
     * 上限金额的对外口径：估值最多 2 位小数、折当率最多 4 位，乘积理论最多 6 位；
     * 卡线用未舍入的精确值比较，这里只把结果按分（2 位、向下取值）显示，
     * 避免把 14000.000000 这种计算尾差当成可放额报给柜台。
     */
    private static String displayMoney(BigDecimal cap) {
        return cap.setScale(2, java.math.RoundingMode.DOWN).toPlainString();
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
