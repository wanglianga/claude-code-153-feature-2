package com.waste.enums;

/** 称重复核结论 */
public enum WeightConclusion {
    MERGED_COLLECTION("合并清运"),
    SCALE_MALFUNCTION("称重设备异常"),
    DRIVER_MISENTRY("司机误填"),
    SUSPICIOUS_MIXED("疑似混装证据不足"),
    NORMAL("复核无异常");

    private final String text;

    WeightConclusion(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}
