package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 清运车辆 */
@Data
@Entity
@Table(name = "vehicle")
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 16)
    private String plateNo;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal capacityTons;    // 核载吨位

    @Column(nullable = false, length = 64)
    private String driverName;

    @Column(length = 32)
    private String driverPhone;

    @Column(nullable = false, length = 64)
    private String companyName;     // 所属清运公司

    /** 称重复核认定违规混装等情形时，暂停后续派单权限 */
    @Column(nullable = false)
    private Boolean dispatchBlocked = false;

    @Column(length = 256)
    private String blockReason;

    private LocalDateTime blockedAt;
}
