package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 称重复核费用拆分明细：
 * 多户合并清运认定后，按各户验收袋数、材质重量估算与现场装车顺序
 * 将整车净重拆分到各预约/业主，并联动生成/调整业主分摊账单与清运公司结算拆分。
 * 物业可人工调整拆分重量，但必须填写调整理由。
 */
@Data
@Entity
@Table(name = "weight_review_item", indexes = {
        @Index(name = "idx_review_item", columnList = "reviewId")
})
public class WeightReviewItem {
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
    private String buildingRoom;     // 楼栋房号（如 2栋-502）

    @Column(nullable = false)
    private Integer acceptedBags;    // 现场验收袋数

    /** 按材质估算的单袋重量（kg/袋，木板/玻璃/瓷砖等密度差异） */
    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal materialBagWeightKg;

    /** 材质重量估算 = 验收袋数 × 材质单袋重量 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal materialEstimateKg;

    /** 现场装车顺序（1 = 最先装车） */
    @Column(nullable = false)
    private Integer loadOrder;

    /** 系统按"材质估算占比"拆分的重量（kg） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal allocatedWeightKg;

    /** 物业人工调整后的最终重量（kg），未调整时与 allocatedWeightKg 相同 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal finalWeightKg;

    /** 该户应分摊清运费（元） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal allocatedFee;

    @Column(nullable = false)
    private Boolean manualAdjusted = false;

    @Column(length = 256)
    private String adjustReason;    // 人工调整理由（调整时必填）

    /** 拆分联动生成的业主账单 ID（可空，主预约为调整原账单） */
    private Long ownerBillId;

    /** 清运公司结算拆分账单 ID（可空） */
    private Long collectorBillId;

    private LocalDateTime createdAt = LocalDateTime.now();
}
