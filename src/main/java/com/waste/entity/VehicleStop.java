package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车辆行驶轨迹点 / 司机停留点：由车载 GPS 回传。
 * 称重复核时用于判断车辆在本小区装车前后是否还在其他楼栋（或其他小区）停留装货，
 * 是认定"合并清运（混装其他楼栋垃圾）"的关键证据之一。
 */
@Data
@Entity
@Table(name = "vehicle_stop", indexes = {
        @Index(name = "idx_stop_plate_time", columnList = "plateNo,eventTime")
})
public class VehicleStop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String plateNo;

    @Column(nullable = false, length = 64)
    private String driverName;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    /** 停留/打点位置描述，如 "2号楼西侧垃圾点" */
    @Column(nullable = false, length = 128)
    private String location;

    /** 关联垃圾点（可空，如小区外打点） */
    private Long wastePointId;

    /** 停留分钟数（行驶轨迹打点为 0） */
    @Column(nullable = false)
    private Integer stayMinutes = 0;

    /** 装车照片 URL（可空，停留点装车时回传） */
    @Column(length = 1024)
    private String photos;

    private LocalDateTime createdAt = LocalDateTime.now();
}
