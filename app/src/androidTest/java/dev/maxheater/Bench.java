package dev.maxheater;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class Bench {
    static void run(Instrumentation test, Bundle arguments) {
        Bundle result=new Bundle(); StringBuilder report=new StringBuilder();
        File saved=new File(test.getTargetContext().getFilesDir(),"workload-bench.txt");
        Activity activity=null;
        try {
            persist(saved,"RUNNING id="+arguments.getString("run_id","manual")+"\n",false);
            Intent open=new Intent(test.getTargetContext(),MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=test.startActivitySync(open); test.waitForIdleSync();
            final Activity screen=activity;
            test.runOnMainSync(()->screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            long focusDeadline=SystemClock.elapsedRealtime()+3000;
            while(!screen.hasWindowFocus() && SystemClock.elapsedRealtime()<focusDeadline) SystemClock.sleep(100);
            if(!screen.hasWindowFocus()) throw new AssertionError("Unlock device and keep app in foreground before benchmarking");
            while(NativeEngine.isPreparing()) { safe(test); SystemClock.sleep(100); }
            // matrix, layers, GPU iterations, independent chains, queue depth, CPU/GPU/NPU mask
            int[][] cases={{1024,8,256,16,4,4},{2048,16,512,16,4,4},{3072,8,512,16,4,4},{2048,16,512,16,1,2},{2048,16,512,16,4,2},{2048,16,512,16,4,7}};
            int selected=Integer.parseInt(arguments.getString("case","-1"));
            if(selected < -1 || selected >= cases.length) throw new IllegalArgumentException("case must be 0.."+(cases.length-1));
            for(int caseIndex=0;caseIndex<cases.length;caseIndex++) {
                if(selected>=0 && selected!=caseIndex) continue;
                int[] c=cases[caseIndex];
                safe(test);
                persist(saved,"CASE "+caseIndex+" uptime_ms="+SystemClock.elapsedRealtime()+"\n",true);
                if(!NativeEngine.configure(c[0],c[1],c[2],c[3],c[4])) throw new AssertionError("Configuration rejected");
                if((c[5]&4)!=0 && !NativeEngine.prepareQnnBlocking(test.getTargetContext().getApplicationInfo().nativeLibraryDir)) throw new AssertionError("HTP preparation: "+NativeEngine.detail(2));
                safe(test);
                if(!screen.hasWindowFocus()) throw new AssertionError("Lost foreground during preparation");
                long token=NativeEngine.beginSession(); if(token==0) throw new AssertionError("Session busy");
                int mask=c[5]; long[] operations=NativeEngine.operationCounts();
                Thread worker=new Thread(()->{try { NativeEngine.run((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,test.getTargetContext().getApplicationInfo().nativeLibraryDir,9); } finally { NativeEngine.finishSession(token); }},"bounded-bench");
                worker.start();
                long[] begin=null,last=null; long beginAt=0,endAt=0,limit=SystemClock.elapsedRealtime()+8500;
                String stop="completed";
                try {
                    while(worker.isAlive() && SystemClock.elapsedRealtime()<limit) {
                        SystemClock.sleep(100); safe(test);
                        if(!screen.hasWindowFocus()) { stop="lost foreground"; break; }
                        long[] sample=NativeEngine.snapshot(); long now=SystemClock.elapsedRealtime();
                        boolean ready=true;
                        for(int i=0;i<3;i++) if((mask&(1<<i))!=0) { if(sample[i+3]==3) throw new AssertionError(NativeEngine.detail(i)); ready &= sample[i+3]==2 && sample[i]>0; }
                        if(ready) {
                            if(begin==null) { begin=sample; beginAt=now; persist(saved,"MEASURE case="+caseIndex+" begin_uptime_ms="+now+"\n",true); }
                            last=sample; endAt=now;
                            if(endAt-beginAt>=1800) break;
                        }
                    }
                } finally { NativeEngine.cancelSession(token); worker.join(15000); }
                if(worker.isAlive()) throw new AssertionError("Driver call did not return after stop");
                if(begin==null || last==null || endAt<=beginAt) throw new AssertionError("No measurement: "+stop);
                double elapsed=(endAt-beginAt)/1000.0;
                String line=String.format(Locale.ROOT,"case=%d end_uptime_ms=%d matrix=%d layers=%d chains=%d queue=%d mask=%d seconds=%.3f CPU=%.3f_GFLOPS GPU=%.3f_TFLOPS_FP32 NPU=%.3f_TOPS_INT8; GPU: %s; NPU: %s\n",caseIndex,endAt,c[0],c[1],c[3],c[4],mask,elapsed,Throughput.operationsPerSecond(last[0]-begin[0],operations[0],elapsed)/1e9,Throughput.operationsPerSecond(last[1]-begin[1],operations[1],elapsed)/1e12,Throughput.operationsPerSecond(last[2]-begin[2],operations[2],elapsed)/1e12,NativeEngine.detail(1),NativeEngine.detail(2));
                report.append(line); persist(saved,line,true); Bundle update=new Bundle(); update.putString("stream",line); test.sendStatus(0,update); SystemClock.sleep(500);
            }
            persist(saved,"PASS workload sweep\n",true);
            result.putString("stream","PASS workload sweep\n"+report); test.finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { String failure="FAIL / STOPPED: "+error+"\n"; try { persist(saved,failure,true); } catch(Exception ignored) {} result.putString("stream",failure+report); test.finish(Activity.RESULT_CANCELED,result); }
        finally { NativeEngine.stop(); }
    }
    private static void persist(File file,String line,boolean append) throws java.io.IOException {
        try(FileOutputStream out=new FileOutputStream(file,append)) { out.write(line.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }
    private static void safe(Instrumentation test) {
        Intent battery=test.getTargetContext().registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery==null) throw new AssertionError("No battery sensor");
        float temperature=battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10f;
        int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        int percent=level>=0 && scale>0?Math.round(level*100f/scale):-1;
        PowerManager power=(PowerManager)test.getTargetContext().getSystemService(android.content.Context.POWER_SERVICE);
        String reason=SafetyPolicy.stopReason(temperature,percent,power.getCurrentThermalStatus());
        if(reason!=null) throw new AssertionError(reason+" ("+temperature+" C)");
    }
}
