package com.somepro.application.ticket;

import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.Category;
import com.somepro.domain.pawner.model.PawnerStatus;
import com.somepro.domain.pawner.repository.PawnerStatePort;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.PawnTicketQuery;
import com.somepro.domain.ticket.model.TicketStatus;
import com.somepro.domain.ticket.repository.PawnTicketRepository;
import com.somepro.domain.ticket.repository.RateConfigPort;
import com.somepro.domain.ticket.repository.TicketCollateralPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 当票应用服务：编排开票、修改、详情、撤销、按条件翻票五个用例，不写表映射。
 *
 * 出入参用领域对象/基础类型，不认识 PO 与 VO。
 *
 * 开票这条链在这里收口：认物（当物快照端口）→ 抄配置（费率配置端口）→ 聚合落票
 * → 仓储在写锁内点「一物一票」、生成票号、联动当物状态落库。
 * 票号唯一与一物一票的并发约束在仓储里；单票自身规则在 PawnTicket 聚合里。
 */
@Service
public class PawnTicketAppService {

    /** 业务日期统一按行里所在时区算，避免容器 UTC 下起当日期偏一天。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final PawnTicketRepository pawnTicketRepository;
    private final TicketCollateralPort ticketCollateralPort;
    private final RateConfigPort rateConfigPort;
    private final PawnerStatePort pawnerStatePort;

    public PawnTicketAppService(PawnTicketRepository pawnTicketRepository,
                                TicketCollateralPort ticketCollateralPort,
                                RateConfigPort rateConfigPort,
                                PawnerStatePort pawnerStatePort) {
        this.pawnTicketRepository = pawnTicketRepository;
        this.ticketCollateralPort = ticketCollateralPort;
        this.rateConfigPort = rateConfigPort;
        this.pawnerStatePort = pawnerStatePort;
    }

    /**
     * 开票：当户/类别/估值随当物快照带出，利率费率照该类别当前配置抄快照，
     * 当金不得越过「估值 × 折当率上限」（聚合内核算，顶到上限照常放行，越线挡回），
     * 到期日期按起当日期 + 当期月数推算，新票落在当。
     * 起当日期不传按当天（行里时区）算；票号由仓储按 DP-年份-序号 生成。
     *
     * 冻结门禁：当户冻住（FROZEN）后名下不许开新票，办理前先挡一道；与冻结并发的缝由仓储
     * 写锁事务内的当户行锁终检兜住。注销（CLOSED）档案开不了票（物/票早随注销前结清），同样挡回。
     */
    public Mono<PawnTicket> issue(Long collateralId, String pawnAmount, String startDate, Integer termMonths) {
        if (collateralId == null) {
            return Mono.error(new BizException("必须指定押的是哪件当物"));
        }
        BigDecimal amount = parseAmount(pawnAmount);
        LocalDate start = parseStartDate(startDate);

        return ticketCollateralPort.findSnapshot(collateralId)
                .switchIfEmpty(Mono.error(new BizException("当物不存在，不能就查无此物的东西开票")))
                .flatMap(collateral -> pawnerStatePort.findStatus(collateral.pawnerId())
                        .switchIfEmpty(Mono.error(new BizException("当物归属的当户档案不存在，不能开票")))
                        .doOnNext(this::requireNotFrozen)
                        .then(rateConfigPort.findEnabled(collateral.category()))
                        .switchIfEmpty(Mono.error(new BizException("该当物所属类别（"
                                + collateral.category().label() + "）没有生效的费率配置，不能开票")))
                        .flatMap(rate -> pawnTicketRepository.insert(
                                PawnTicket.issue(collateral, amount, rate, start, termMonths))));
    }

    /** 冻结户名下不能开新票 / 不能续当；注销档案同样不再受理新业务。 */
    private void requireNotFrozen(PawnerStatus status) {
        if (status == PawnerStatus.FROZEN) {
            throw new BizException("该当户已冻结，不能开新当票；请先解冻再办理");
        }
        if (status == PawnerStatus.CLOSED) {
            throw new BizException("该当户已注销，不能开新当票");
        }
    }

    /**
     * 修改：当金 / 起当日期 / 当期月数，任一项留空表示该项不动；到期日期跟着重算。
     * 只有在当的票改得动（利率费率快照不动）。改当金仍受折当率上限约束：
     * 实时点该类别当前生效的配置来核算，不动当金就不必点配置。
     */
    public Mono<PawnTicket> update(Long id, String pawnAmount, String startDate, Integer termMonths) {
        BigDecimal amount = pawnAmount == null || pawnAmount.isBlank() ? null : parseAmount(pawnAmount);
        LocalDate start = startDate == null || startDate.isBlank() ? null : parseStartDate(startDate);

        return requireTicket(id).flatMap(ticket -> {
            if (amount == null) {
                ticket.revise(null, null, start, termMonths);
                return pawnTicketRepository.update(ticket);
            }
            return rateConfigPort.findEnabled(ticket.getCategory())
                    .switchIfEmpty(Mono.error(new BizException("该当票所属类别（"
                            + ticket.getCategory().label() + "）没有生效的费率配置，不能改当金")))
                    .flatMap(rate -> {
                        ticket.revise(amount, rate.maxLoanRatio(), start, termMonths);
                        return pawnTicketRepository.update(ticket);
                    });
        });
    }

    /** 详情：id 或 ticketNo（DP-编号）任一指定。 */
    public Mono<PawnTicket> detail(Long id, String ticketNo) {
        if (id != null) {
            return requireTicket(id);
        }
        if (ticketNo != null && !ticketNo.isBlank()) {
            return pawnTicketRepository.findByTicketNo(ticketNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("当票不存在")));
        }
        return Mono.error(new BizException("请指定要查看的当票（id 或 ticketNo）"));
    }

    /**
     * 撤销：开错的票作废，只有在当的票撤得掉；票置已撤销、当物联动回在库，
     * 同一事务落库，撤销时刻由审计 update_time 落账。
     */
    public Mono<PawnTicket> cancel(Long id) {
        return requireTicket(id).flatMap(ticket -> {
            ticket.cancel();
            return pawnTicketRepository.cancel(ticket);
        });
    }

    /** 翻票：当户/类别/状态随意拼，都不填翻整份；每行带 ticketNo，分页稳定走。 */
    public Mono<PageResult<PawnTicket>> page(int pageNum, int pageSize, Long pawnerId,
                                             String category, String status) {
        if (pageNum < 1 || pageSize < 1) {
            return Mono.error(new BizException("页码与每页条数必须为正整数"));
        }
        PawnTicketQuery query = PawnTicketQuery.of(
                pawnerId,
                Category.ofCode(blankToNull(category)),
                TicketStatus.ofCode(blankToNull(status)));
        return pawnTicketRepository.page(pageNum, pageSize, query);
    }

    private Mono<PawnTicket> requireTicket(Long id) {
        if (id == null) {
            return Mono.error(new BizException("必须指定当票 id"));
        }
        return pawnTicketRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("当票不存在")));
    }

    /** 当金入参解析：必须是正数金额；空串按「没填」挡回。 */
    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("当金不能为空");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException("当金必须是正数金额：" + raw);
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("当金必须是正数，零和负数一律不收");
        }
        return value;
    }

    /** 起当日期入参解析：yyyy-MM-dd；留空按行里当天算。 */
    private LocalDate parseStartDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now(BIZ_ZONE);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BizException("起当日期格式应为 yyyy-MM-dd：" + raw);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
