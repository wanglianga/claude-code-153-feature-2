package com.waste.enums;

/** 称重复核结论 */
public enum ReviewConclusion {
    MERGED_COLLECTION,  // 合并清运（同车混装了其他楼栋/户的垃圾）
    DEVICE_ANOMALY,     // 称重设备异常（车载秤与处置场地磅偏差）
    DRIVER_MISENTRY,    // 司机误填（人工录入重量错误）
    NO_ANOMALY,         // 复核无异常，维持原计量
    INCONCLUSIVE        // 证据不足，维持原计费并挂起说明
}
