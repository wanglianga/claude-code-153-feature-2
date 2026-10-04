package com.waste.entity;

import com.waste.enums.ReviewConclusion;
import com.waste.enums.ReviewStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 称重差异复核单：清运车回传净重明显高于现场验收袋数时，
 * 物业比对装车照片、车辆路线、司机停留点、处置场回执与同日其他预约，
 * 判定是否合并清运、称重设备异常或司机误填，并据此调整业主分摊、清运结算与车辆派单权限。
 */
@Data
@Entity
@Table(name = "weigh_review")
public class WeighReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String reviewNo;

    @Column(nullable = false)
    private Long weighRecordId;

    @Column(nullable = false)
    private Long dispatchId;

    /** 触发复核的预约（回传重量归属的现场验收批次） */
    @Column(nullable = false)
    private Long appointmentId;

    @Column(nullable = false, length = 16)
    private String plateNo;

    @Column(nullable = false, length = 64)
    private String driverName;

    // ---- 证据比对结果（系统分析，随证据补传自动刷新）----

    /** 装车照片清点可见袋数（含其他楼栋标签照片时作为合并清运证据） */
    private Integer photoVisibleBags;

    @Column(length = 512)
    private String photoLabels;         // 照片中识别到的楼栋/房号标签，逗号分隔

    /** 车辆 GPS 轨迹停留点数量与非预约点位（JSON 文本摘要） */
    @Column(length = 1024)
    private String routeAnalysis;

    /** 处置场回执重量与车载净重偏差（%），无回执为 null */
    private java.math.BigDecimal receiptDeviationPct;

    /** 同日同车其他已完成预约（合并清运候选，逗号分隔预约号） */
    @Column(length = 512)
    private String sameDayAppointments;

    /** 按各户验收袋数与材质估算的合并总重量（kg） */
    private java.math.BigDecimal mergedEstimatedKg;

    /** 系统比对后的建议结论 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ReviewConclusion systemSuggestion;

    /** 系统建议理由（逐项证据说明） */
    @Column(length = 1024)
    private String suggestionReason;

    // ---- 物业复核结论 ----

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ReviewConclusion finalConclusion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    /** 复核认定的有效计费重量（kg）：合并清运为各户分摊之和；设备异常取回执重量；误填取按袋预估 */
    private java.math.BigDecimal confirmedWeightKg;

    /** 物业是否人工调整了系统拆分/认定（true 时必须填写人工调整理由） */
    @Column(nullable = false)
    private Boolean manualAdjusted = false;

    /** 物业人工调整/确认理由 */
    @Column(length = 512)
    private String adjustReason;

    private Long confirmerId;

    @Column(length = 64)
    private String confirmerName;

    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
}
