package dev.maxheater;
final class SafetyPolicy {
    static String stopReason(float temperature, int batteryPercent, int thermalStatus) {
        if (!Float.isFinite(temperature) || temperature <= 0 || temperature > 80) return "無有效電池溫度，無法開始／繼續測試";
        if (temperature >= 45) return "電池達到 45°C，已停止";
        if (thermalStatus >= 3) return "系統回報嚴重熱壓力，已停止";
        if (batteryPercent < 0) return "無有效電量資料，已停止";
        if (batteryPercent <= 15) return "電量低於或等於 15%，已停止";
        return null;
    }
}
