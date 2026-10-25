package dev.maxheater;
import android.os.Build;
import java.util.Locale;
final class DeviceProfile {
    final String chip, architecture, note;
    final boolean candidate;
    DeviceProfile(String model, boolean arm64) {
        String soc=model.toUpperCase(Locale.ROOT);
        if (soc.contains("SM8650")) { chip="Snapdragon 8 Gen 3"; architecture="HTP v75"; candidate=arm64; note="SDK 列出支援 · 真機待測"; }
        else if (soc.contains("SM8750")) { chip="Snapdragon 8 Elite"; architecture="HTP v79"; candidate=arm64; note="SDK 列出支援 · 真機待測"; }
        else if (soc.contains("SM8845")) { chip="Snapdragon 8 Gen 5"; architecture="HTP 待探測"; candidate=arm64; note="實驗性 · SDK 未明列此 SoC"; }
        else if (soc.contains("SM8850")) { chip="Snapdragon 8 Elite Gen 5"; architecture="HTP v81"; candidate=arm64; note="SDK 列出支援 · 真機待測"; }
        else { chip=model; architecture="NPU 未支援"; candidate=false; note="CPU / GPU 測試模式"; }
    }
    static DeviceProfile current() {
        boolean arm=false; for(String abi:Build.SUPPORTED_ABIS) if(abi.equals("arm64-v8a")) arm=true;
        return new DeviceProfile(Build.SOC_MODEL,arm);
    }
}
