package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 称重复核多户拆分明细：认定为多户合并清运时，
 * 按各户验收袋数、材质重量估算与现场装车顺序，将车载净重与清运结算费拆分到各户。
 * 物业可逐户人工调整（必须填写调整理由）。
 */
@Data
@Entity
@Table(name = "weigh_review_share", indexes = {
        @Index(name = "idx_share_review", columnList = "reviewId"),
        @Index(name = "idx_share_appt", columnList = "appointmentId")
})
public class WeighReviewShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private Long appointmentId;

    @Column(nullable = false)
    private Long registrationId;

    @Column(nullable = false, length = 64)
    private String ownerName;

    @Column(nullable = false, length = 64)
    private String buildingRoom;     // 楼栋房号（1栋801）

    /** 现场验收袋数（含临时加袋） */
    @Column(nullable = false)
    private Integer acceptedBags;

    @Column(nullable = false)
    private Integer extraBags;

    /** 按材质估算的单袋重量（kg）：依据该户上报垃圾材质取参数 */
    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal estBagWeightKg;

    /** 材质重量估算合计 = 验收袋数 × 单袋估算（kg） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal estimatedWeightKg;

    /** 现场装车顺序（1 最先装车，数字越大越靠后；尾差归入最后装车户） */
    @Column(nullable = false)
    private Integer loadSequence;

    /** 系统按估算占比拆分的重量（kg） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal systemWeightKg;

    /** 最终确认的计费重量（kg），默认等于系统拆分，物业人工调整后不同 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal confirmedWeightKg;

    /** 该户分摊的清运公司结算费（元，按确认重量 × 吨单价） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal collectorFee;

    /** 该户业主清运费（元，按袋计费规则；被合并户复核确认时据此补建账单） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal ownerFee;

    /** 是否人工调整过该户拆分 */
    @Column(nullable = false)
    private Boolean manualAdjusted = false;

    /** 人工调整理由 */
    @Column(length = 512)
    private String adjustReason;

    private LocalDateTime createdAt = LocalDateTime.now();
}
