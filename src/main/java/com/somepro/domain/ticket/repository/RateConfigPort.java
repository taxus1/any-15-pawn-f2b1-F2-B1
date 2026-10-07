package com.somepro.domain.ticket.repository;

import com.somepro.domain.collateral.model.Category;
import com.somepro.domain.ticket.model.RateConfig;
import reactor.core.publisher.Mono;

/**
 * 费率配置端口：当票模块开票/改当金时要的当前事实 —— 这个类别现在生效的
 * 月利率、月综合费率与折当率上限。
 *
 * 每次用每次现查：抄进票里的是开票当下的配置快照，日后配置改了不影响已开出的票；
 * 只读不写。
 */
public interface RateConfigPort {

    /**
     * 该类别当前生效（ENABLED、未删除）的费率配置；没配或已停用时返回空。
     */
    Mono<RateConfig> findEnabled(Category category);
}
