package com.waste.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 计费与风控规则参数（application.yml waste.pricing.*） */
@Data
@Component
@ConfigurationProperties(prefix = "waste.pricing")
public class WasteProperties {
    private BigDecimal bagUnitPrice = new BigDecimal("8.0");
    private BigDecimal extraBagMultiplier = new BigDecimal("1.5");
    private BigDecimal avgBagWeightKg = new BigDecimal("25.0");
    private BigDecimal collectorPricePerTon = new BigDecimal("120.0");
    private BigDecimal fireLaneFine = new BigDecimal("200.0");
    private BigDecimal mixedWasteFine = new BigDecimal("100.0");
    private BigDecimal weighAnomalyThresholdPct = new BigDecimal("20.0");
    private BigDecimal capacityWarnRatio = new BigDecimal("0.9");
    private int lateToleranceMinutes = 30;

    // ---- 称重复核参数 ----
    /** 小区地磅净重与处置场回执重量偏差超过该比例，判定称重设备异常（%） */
    private BigDecimal scaleDiffThresholdPct = new BigDecimal("10.0");
    /** 车辆在某处停留达到该分钟数，视为一次有效装车停留 */
    private int mergedStopMinutes = 5;
    /** 分材质单袋重量估算（kg/袋）：木板较轻、玻璃/瓷砖密度大 */
    private BigDecimal woodBagWeightKg = new BigDecimal("18.0");
    private BigDecimal glassBagWeightKg = new BigDecimal("30.0");
    private BigDecimal tileBagWeightKg = new BigDecimal("35.0");
}
