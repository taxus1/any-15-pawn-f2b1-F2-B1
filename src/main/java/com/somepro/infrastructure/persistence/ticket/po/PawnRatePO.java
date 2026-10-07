package com.somepro.infrastructure.persistence.ticket.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * t_pawn_rate 表的持久化对象（PO，基础设施层）：两个模块共用 —— 当票模块只读
 * （取该类别当前生效的月利率、月综合费率与折当率上限），
 * 费率配置模块负责录入/修改/停用。本类不做任何建表/改表动作。
 */
@Getter
@Setter
@TableName("t_pawn_rate")
public class PawnRatePO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("category")
    private String category;

    @TableField("monthly_rate")
    private BigDecimal monthlyRate;

    @TableField("service_rate")
    private BigDecimal serviceRate;

    @TableField("max_loan_ratio")
    private BigDecimal maxLoanRatio;

    @TableField("status")
    private String status;
}
