package dev.maxheater;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.CheckBox;
import java.util.Arrays;
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private boolean benchmark;
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; benchmark=args!=null && "bench".equals(args.getString("mode")); start(); }
    private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private Button button(View view,String prefix) {
        if(view instanceof Button && ((Button)view).getText().toString().startsWith(prefix)) return (Button)view;
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) { Button found=button(group.getChildAt(i),prefix); if(found!=null) return found; } }
        return null;
    }
    private void click(String prefix) {
        runOnMainSync(()->{ Button target=button(activity.getWindow().getDecorView(),prefix); require(target!=null,"Missing button: "+prefix); require(target.isEnabled(),"Button disabled: "+prefix); target.performClick(); });
    }
    private void disableGpu(View view) {
        if(view instanceof Switch && "GPU 負載開關".contentEquals(view.getContentDescription())) ((Switch)view).setChecked(false);
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) disableGpu(group.getChildAt(i)); }
    }
    private CheckBox acceptance(View view) {
        if(view instanceof CheckBox) return (CheckBox)view;
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view; for(int i=0;i<group.getChildCount();i++) { CheckBox found=acceptance(group.getChildAt(i)); if(found!=null) return found; } }
        return null;
    }
    private void gateOnly() {
        Bundle result=new Bundle();
        try {
            Intent entry=new Intent(getTargetContext(),DisclaimerActivity.class); entry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=startActivitySync(entry); waitForIdleSync();
            runOnMainSync(()->{
                CheckBox consent=acceptance(activity.getWindow().getDecorView());
                require(consent!=null && !consent.isChecked(),"Disclaimer must default to unchecked");
                require(!button(activity.getWindow().getDecorView(),"確認風險").isEnabled(),"Enter enabled without consent");
            });
            click("退出"); waitForIdleSync(); require(activity.isFinishing(),"Exit did not finish gate");
            activity=startActivitySync(entry); waitForIdleSync();
            ActivityMonitor main=addMonitor(MainActivity.class.getName(),null,false);
            runOnMainSync(()->acceptance(activity.getWindow().getDecorView()).setChecked(true)); click("確認風險");
            Activity opened=waitForMonitorWithTimeout(main,4000); removeMonitor(main); require(opened!=null,"Accepted gate did not open main");
            long[] counts=NativeEngine.snapshot(); require(counts[0]==0 && counts[1]==0 && counts[2]==0,"Entry submitted pressure workload");
            runOnMainSync(opened::finish);
            result.putString("stream","PASS: disclaimer unchecked by default, enter disabled before consent, exit, accepted navigation, no pressure workload submitted.\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("stream","FAIL: "+error+"\n"); finish(Activity.RESULT_CANCELED,result); }
    }
    @Override public void onStart() {
        if(arguments!=null && "gate".equals(arguments.getString("mode"))) { gateOnly(); return; }
        if(benchmark) { Bench.run(this,arguments); return; }
        Bundle result=new Bundle();
        try {
            require(SafetyPolicy.stopReason(44.9f,16,2)==null,"Normal boundary rejected");
            require(SafetyPolicy.stopReason(45,90,0)!=null,"45 C must stop");
            require(SafetyPolicy.stopReason(Float.NaN,90,0)!=null,"Missing temperature must stop");
            require(SafetyPolicy.stopReason(30,15,0)!=null,"15 percent must stop");
            require(SafetyPolicy.stopReason(30,90,3)!=null,"Severe thermal status must stop");
            long[] ops=NativeEngine.operationCounts();
            require(ops[1]==17179869184L,"GPU operation count mismatch");
            require(ops[2]==274877906944L,"NPU operation count mismatch");
            require(Math.abs(Throughput.operationsPerSecond(100,17179869184L,2)/1e12-0.8589934592)<1e-10,"TOPS conversion");
            require(Throughput.operationsPerSecond(Long.MAX_VALUE,ops[2],1)>1e20,"Overflow protection");
            require(Throughput.operationsPerSecond(10,ops[1],0)==0,"Zero duration");
            // Software GLES in the emulator needs a bounded smaller kernel; the
            // production defaults were checked above. Real phones keep defaults.
            if(!DeviceProfile.current().candidate) require(NativeEngine.configure(512,1,64,4,1),"Emulator configuration");
            Intent intent=new Intent(getTargetContext(),MainActivity.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=startActivitySync(intent); waitForIdleSync();
            if(DeviceProfile.current().candidate) {
                require(NativeEngine.prepareQnnBlocking(getTargetContext().getApplicationInfo().nativeLibraryDir),"HTP preparation: "+NativeEngine.detail(2));
                long compiled=NativeEngine.qnnPreparation()[1];
                require(NativeEngine.prepareQnnBlocking(getTargetContext().getApplicationInfo().nativeLibraryDir),"HTP cache reuse failed");
                require(NativeEngine.qnnPreparation()[1]==compiled,"Unchanged graph compiled twice");
            }
            boolean hasCompute=((android.app.ActivityManager)getTargetContext().getSystemService(android.content.Context.ACTIVITY_SERVICE)).getDeviceConfigurationInfo().reqGlEsVersion>=0x30001;
            if(!hasCompute) runOnMainSync(()->disableGpu(activity.getWindow().getDecorView()));
            click("啟動 MAX");
            long readyDeadline=SystemClock.elapsedRealtime()+20000; long[] before;
            do {
                SystemClock.sleep(100); before=NativeEngine.snapshot();
                if(before[4]==3 || before[5]==3) break;
                if(before[0]>0 && (!hasCompute || before[1]>0) && (!DeviceProfile.current().candidate || before[2]>0)) break;
            } while(SystemClock.elapsedRealtime()<readyDeadline);
            require(before[0]>0,"CPU did not complete work: "+Arrays.toString(before));
            if(hasCompute) require(before[1]>0 && before[4]==2,"GPU did not complete work: "+NativeEngine.detail(1));
            if(DeviceProfile.current().candidate) require(before[2]>0 && before[5]==2,"HTP did not complete work: "+NativeEngine.detail(2));
            click("■"); long end=SystemClock.elapsedRealtime()+10000; long[] after;
            do { SystemClock.sleep(100); after=NativeEngine.snapshot(); } while(SystemClock.elapsedRealtime()<end && (after[3]==1||after[3]==2||after[4]==1||after[4]==2||after[5]==1||after[5]==2));
            require(after[3]==4 && (!hasCompute || after[4]==4),"CPU/GPU did not stop");
            if(DeviceProfile.current().candidate) require(after[5]==4,"HTP did not stop");
            SystemClock.sleep(200); require(Arrays.equals(after,NativeEngine.snapshot()),"Counters increased after stop");
            long first=NativeEngine.beginSession(); require(first!=0,"Session not released"); require(NativeEngine.beginSession()==0,"Overlapping session allowed");
            NativeEngine.cancelSession(first); NativeEngine.finishSession(first);
            long second=NativeEngine.beginSession(); require(second>first,"Session token did not advance"); NativeEngine.cancelSession(first);
            NativeEngine.run(true,false,false,getTargetContext().getApplicationInfo().nativeLibraryDir,1);
            require(NativeEngine.snapshot()[0]>0,"Stale owner cancelled current session"); NativeEngine.finishSession(second);
            result.putString("stream","PASS: safety boundaries, TOPS/TFLOPS math, CPU"+(hasCompute?"/GPU":" (GPU SKIPPED: device advertises GLES below 3.1)")+(DeviceProfile.current().candidate?"/HTP":" (HTP SKIPPED: unsupported SoC)")+", manual stop, stable counters, session ownership. Completed work: "+Arrays.toString(after)+"\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("stream","FAIL: "+error+"\n"); finish(Activity.RESULT_CANCELED,result); }
        finally { NativeEngine.stop(); }
    }
}
