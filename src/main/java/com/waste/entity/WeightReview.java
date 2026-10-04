package com.waste.entity;

import com.waste.enums.ReviewStatus;
import com.waste.enums.WeightConclusion;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 称重差异复核单：
 * 清运车辆回传净重明显高于现场验收袋数时，物业发起复核，
 * 系统汇总装车照片、车辆路线、司机停留点、处置场回执、同日其他预约五类证据，
 * 给出"合并清运 / 称重设备异常 / 司机误填 / 疑似混装 / 无异常"的判定建议；
 * 涉及多户合并清运时按各户验收袋数、材质重量估算与现场装车顺序拆分费用。
 */
@Data
@Entity
@Table(name = "weight_review")
public class WeightReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String reviewNo;

    @Column(nullable = false)
    private Long weighRecordId;

    @Column(nullable = false)
    private Long appointmentId;       // 被复核的预约（差额申诉的楼栋/户）

    @Column(nullable = false)
    private Long dispatchId;

    @Column(nullable = false, length = 16)
    private String plateNo;

    @Column(nullable = false, length = 64)
    private String driverName;

    // ---- 复核时锁定的关键数据 ----
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal netWeightKg;          // 车辆回传净重

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal estimatedWeightKg;    // 按袋预估重量

    @Column(precision = 10, scale = 2)
    private BigDecimal disposalWeightKg;     // 处置场回执接收重量（可空）

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal deviationPct;         // 净重相对预估偏差%

    /** 系统证据汇总与判定建议（JSON 文本） */
    @Column(length = 4096)
    private String evidenceSummary;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private WeightConclusion suggestion;     // 系统建议结论

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private WeightConclusion conclusion;     // 物业最终结论（定论前为空）

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status = ReviewStatus.PENDING;

    // ---- 复核结论影响 ----
    /** 认定归属本预约的净重（kg）：合并清运时为拆给该户的重量，设备异常/误填时为核定重量 */
    @Column(precision = 10, scale = 2)
    private BigDecimal confirmedWeightKg;

    /** 是否暂停涉事车辆后续派单权限 */
    @Column(nullable = false)
    private Boolean vehicleBlocked = false;

    @Column(length = 512)
    private String conclusionNote;           // 定论说明

    private Long confirmerId;

    @Column(length = 64)
    private String confirmerName;

    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
}
