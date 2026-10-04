package com.waste.enums;

/** 称重复核单状态 */
public enum ReviewStatus {
    PENDING,     // 已发起，证据已汇总，待物业定论
    CONFIRMED,   // 已出复核结论并完成费用拆分/结算调整
    CLOSED       // 已结案归档（含车辆限制解除等后续事项处理完毕）
}
