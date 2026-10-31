package dev.maxheater;

/** Host-side check; does not call Android APIs or submit any compute workload. */
public final class DeviceProfileTest {
    private static void expect(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for(String model:new String[]{"SM8550","sm8550","SM8550-AC","Qualcomm SM8550-AB"}) {
            DeviceProfile profile=new DeviceProfile(model,true);
            expect(profile.candidate,"8 Gen 2 NPU disabled: "+model);
            expect(profile.chip.equals("Snapdragon 8 Gen 2"),"Incorrect chip: "+model);
            expect(profile.architecture.equals("HTP v73"),"Incorrect HTP architecture: "+model);
        }
        expect(!new DeviceProfile("SM8550",false).candidate,"Non-ARM64 NPU enabled");
        expect(!new DeviceProfile("SM8450",true).candidate,"Unsupported chip enabled");
        String[][] existing={{"SM8650","HTP v75"},{"SM8750","HTP v79"},{"SM8850","HTP v81"},{"SM8845","HTP 待探測"}};
        for(String[] pair:existing) {
            DeviceProfile profile=new DeviceProfile(pair[0],true);
            expect(profile.candidate && profile.architecture.equals(pair[1]),"Existing profile regressed: "+pair[0]);
        }
        System.out.println("PASS: SM8550 variants, ARM64 requirement, unsupported chip, existing SoC profiles; no device workload.");
    }
}
