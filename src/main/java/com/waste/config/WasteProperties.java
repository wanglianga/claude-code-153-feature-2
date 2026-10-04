package com.waste.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

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

    // ---- 称重差异复核参数 ----
    /** 各材质单袋估算重量（kg），用于多户合并清运时按材质估算拆账 */
    private Map<String, BigDecimal> materialBagWeightKg = new HashMap<>(Map.of(
            "wood", new BigDecimal("18.0"),       // 木板（较轻）
            "glass", new BigDecimal("32.0"),      // 玻璃
            "tile", new BigDecimal("30.0"),       // 瓷砖
            "concrete", new BigDecimal("35.0"),   // 混凝土
            "mixed", new BigDecimal("25.0")       // 混入生活垃圾/混合
    ));
    /** 停留点达到该分钟数视为装车停留 */
    private int reviewMergeStayMinutes = 5;
    /** 同车预约合并排查时间窗（称重时刻前后小时数） */
    private int reviewMergeWindowHours = 6;
    /** 多户估算合计与净重偏差在该比例内 → 判定合并清运 */
    private BigDecimal reviewMergeTolerancePct = new BigDecimal("25.0");
    /** 处置场回执与按袋估算偏差在该比例内视为相互印证 */
    private BigDecimal reviewReceiptMatchPct = new BigDecimal("20.0");
    /** 车载净重与处置场回执偏差超过该比例 → 称重设备异常 */
    private BigDecimal reviewDeviceDeviationPct = new BigDecimal("10.0");
}
