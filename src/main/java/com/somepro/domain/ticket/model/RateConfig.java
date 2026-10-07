package com.somepro.domain.ticket.model;

import com.somepro.domain.collateral.model.Category;

import java.math.BigDecimal;

/**
 * 费率配置（不可变值对象）：当票模块眼里的 t_pawn_rate 一行 —— 某个类别当前生效的
 * 月利率、月综合费率与折当率上限。
 *
 * 开票时把 monthlyRate / serviceRate 抄进当票做快照：日后配置改了，
 * 已经开出去的票不受影响，对账才对得平。maxLoanRatio 不落票（表里没有这列）。
 */
public record RateConfig(Category category,
                         BigDecimal monthlyRate,
                         BigDecimal serviceRate,
                         BigDecimal maxLoanRatio) {
}
