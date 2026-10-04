package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 司机停留点（GPS/行程回传或人工补录）：
 * 称重复核时用于还原车辆当日路线，判断是否在其他楼栋垃圾点停留装车。
 */
@Data
@Entity
@Table(name = "vehicle_stop", indexes = {
        @Index(name = "idx_stop_plate_time", columnList = "plateNo,stopTime")
})
public class VehicleStop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String plateNo;

    @Column(nullable = false)
    private LocalDateTime stopTime;       // 到达停留点时间

    @Column(nullable = false, length = 128)
    private String location;              // 停留位置（如 "2号楼装修垃圾点"）

    @Column(nullable = false)
    private Integer durationMinutes;      // 停留时长

    /** 命中的预约 ID（可空，证据匹配后回填） */
    private Long matchedAppointmentId;

    /** GPS 行程回传 / MANUAL 人工补录 */
    @Column(nullable = false, length = 16)
    private String source = "GPS";

    @Column(length = 256)
    private String note;

    private LocalDateTime createdAt = LocalDateTime.now();
}
