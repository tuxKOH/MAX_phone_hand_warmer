package dev.maxheater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG=0xff101211, PANEL=0xff1b1e1a, LINE=0xff343a31, INK=0xfff0f3e9, MUTED=0xff9ea697, LIME=0xffdcff71, ORANGE=0xffffa071;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Switch[] toggles=new Switch[3];
    private final TextView[] rates=new TextView[3], details=new TextView[3], badges=new TextView[3];
    private final long[] previous=new long[3];
    private final long[] operations=NativeEngine.operationCounts();
    private TextView temperature, session, battery, thermal, status;
    private Button start;
    private Trace trace;
    private PowerManager power;
    private DeviceProfile profile;
    private boolean busy, stopping, foreground, destroyed;
    private int seconds=60, batteryPercent=-1;
    private float batteryTemperature=Float.NaN;
    private long startedAt, lastSample, ownedSession, finishedAt;
    private String lastReason="準備就緒 · 等待啟動";
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if (!intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) batteryTemperature=Float.NaN;
            else batteryTemperature=intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10f;
            int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            batteryPercent=level>=0 && scale>0 ? Math.round(level*100f/scale) : -1;
            evaluateSafety();
        }
    };
    private final PowerManager.OnThermalStatusChangedListener thermalListener=value -> evaluateSafety();
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            evaluateSafety(); refresh(); handler.postDelayed(this,500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        power=(PowerManager)getSystemService(POWER_SERVICE); profile=DeviceProfile.current();
        buildUi(); registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        power.addThermalStatusListener(getMainExecutor(),thermalListener); handler.post(ticker);
        if(profile.candidate) NativeEngine.prepareQnnAsync(getApplicationInfo().nativeLibraryDir);
    }
    private void evaluateSafety() {
        if (busy && !stopping) {
            String reason=SafetyPolicy.stopReason(batteryTemperature,batteryPercent,power.getCurrentThermalStatus());
            if (reason!=null) stopSession(reason);
        }
    }
    private void buildUi() {
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root=column(); root.setPadding(dp(22),dp(20),dp(22),dp(24)); scroll.addView(root);
        LinearLayout shell=column(); shell.setBackgroundColor(BG); shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout dock=column(); dock.setPadding(dp(22),dp(6),dp(22),dp(10)); shell.addView(dock);
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(0,bars.top,0,bars.bottom); return insets;
        });
        LinearLayout top=row(); TextView logo=text("M / X",18,LIME); logo.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        top.addView(logo,new LinearLayout.LayoutParams(0,dp(36),1));
        TextView lab=text("THERMAL LAB   /   001",10,MUTED); lab.setGravity(Gravity.CENTER_VERTICAL|Gravity.END); top.addView(lab); root.addView(top);
        root.addView(text("MAX",76,INK)); TextView title=text("手機暖手實驗室",22,INK); title.setTypeface(null,Typeface.BOLD); root.addView(title);
        add(root,text("CPU + GPU + NPU，三路同步壓力測試。",12,MUTED),6,16);
        LinearLayout device=column(); device.setPadding(dp(14),dp(12),dp(14),dp(12)); device.setBackground(box(PANEL,LINE));
        device.addView(text(profile.chip,16,INK)); device.addView(text(android.os.Build.SOC_MODEL+"  /  "+profile.architecture+"\n"+profile.note,11,MUTED)); add(root,device,0,14);
        LinearLayout heat=column(); heat.setPadding(dp(18),dp(16),dp(18),dp(16)); heat.setBackground(box(PANEL,LINE));
        LinearLayout heatTitle=row(); heatTitle.addView(text("BATTERY TEMPERATURE",10,MUTED),new LinearLayout.LayoutParams(0,dp(22),1));
        thermal=text("溫控 —",11,LIME); heatTitle.addView(thermal); heat.addView(heatTitle);
        temperature=text("— °C",52,INK); temperature.setTypeface(Typeface.MONOSPACE); heat.addView(temperature);
        LinearLayout readings=row(); battery=text("電量 —",12,MUTED); session=text("60 秒測試",12,MUTED); session.setGravity(Gravity.END);
        readings.addView(battery,new LinearLayout.LayoutParams(0,dp(24),1)); readings.addView(session); heat.addView(readings);
        trace=new Trace(this); LinearLayout.LayoutParams chartParams=new LinearLayout.LayoutParams(-1,dp(44)); chartParams.topMargin=dp(12); heat.addView(trace,chartParams);
        heat.addView(text("電池感測值；不代表 SoC 或機殼表面溫度",10,MUTED)); add(root,heat,0,18);
        root.addView(text("COMPUTE ENGINES",11,MUTED));
        engineCard(root,0,"01","CPU","全核心 · FP32 FMA",true,true);
        engineCard(root,1,"02","GPU","OpenGL ES 3.1 · Compute shader",true,true);
        engineCard(root,2,"03","NPU","Qualcomm QNN · HTP INT8 MatMul",profile.candidate,profile.candidate);
        add(root,text("實測工作量換算：CPU GFLOPS / GPU FP32 TFLOPS / NPU INT8 TOPS；乘加計 2 ops。不是晶片峰值或功耗。",11,MUTED),8,14);
        LinearLayout durations=row();
        for(int duration:new int[]{60,120,300}) {
            Button b=new Button(this); b.setText(duration/60+" 分鐘"); b.setTextSize(12); b.setTextColor(INK); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1); p.setMargins(0,0,dp(6),0); durations.addView(b,p);
            b.setOnClickListener(v->{ if (!busy) { seconds=duration; lastReason="已設定 "+duration/60+" 分鐘測試"; refresh(); } });
        }
        root.addView(durations);
        start=new Button(this); start.setAllCaps(false); start.setTextSize(17); start.setTypeface(null,Typeface.BOLD); start.setTextColor(BG); start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LIME));
        add(dock,start,0,2); start.setOnClickListener(v->{ if(busy) stopSession("手動停止"); else startSession(); });
        status=text(lastReason,12,LIME); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(dock,status,3,0);
        add(root,text("高功耗壓力測試工具，勿貼身取暖或無人值守。\n45°C 電池上限不代表外殼安全溫度。\n系統嚴重熱壓力即停 · 離開畫面自動停止\n最長 5 分鐘 · 電量需大於 15%",11,MUTED),2,10);
        TextView info=text("支援與運作說明  ↗",12,INK); info.setPadding(0,dp(10),0,dp(10)); info.setOnClickListener(v->showInfo()); root.addView(info);
        root.addView(text("MAX HEATER   /   EXPERIMENTAL BUILD 0.2.1",9,MUTED)); setContentView(shell); refresh();
    }
    private void engineCard(LinearLayout root,int index,String number,String name,String description,boolean selected,boolean enabled) {
        LinearLayout card=column(); card.setPadding(dp(14),dp(12),dp(14),dp(12)); card.setBackground(box(PANEL,LINE)); LinearLayout header=row();
        TextView label=text(number+"  "+name,20,INK); label.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); header.addView(label,new LinearLayout.LayoutParams(0,dp(36),1));
        Switch toggle=new Switch(this); toggle.setContentDescription(name+" 負載開關"); toggle.setChecked(selected); toggle.setEnabled(enabled); toggles[index]=toggle; header.addView(toggle); card.addView(header);
        card.addView(text(description,11,MUTED)); LinearLayout metrics=row(); rates[index]=text("—",17,LIME); badges[index]=text(enabled?"待命":"不可用",11,MUTED);
        metrics.addView(rates[index],new LinearLayout.LayoutParams(0,dp(32),1)); badges[index].setGravity(Gravity.CENTER_VERTICAL|Gravity.END); metrics.addView(badges[index]); card.addView(metrics);
        details[index]=text(index==2&&!enabled?"此裝置不在目標 SoC 內":"等待啟動",10,MUTED); details[index].setTextIsSelectable(true); card.addView(details[index]); add(root,card,8,0);
    }
    private void startSession() {
        if(!foreground || busy) return;
        String reason=SafetyPolicy.stopReason(batteryTemperature,batteryPercent,power.getCurrentThermalStatus());
        if(reason!=null) { lastReason=reason; refresh(); return; }
        final boolean cpu=toggles[0].isChecked(),gpu=toggles[1].isChecked(),npu=profile.candidate&&toggles[2].isChecked();
        if(!cpu&&!gpu&&!npu) { lastReason="請至少啟用一個運算引擎"; refresh(); return; }
        final long token=NativeEngine.beginSession();
        if(token==0) { lastReason=NativeEngine.isPreparing()?"HTP 首次編譯中，完成後即可啟動":"前次運算仍在釋放資源，請稍後再試"; refresh(); return; }
        ownedSession=token; System.arraycopy(NativeEngine.operationCounts(),0,operations,0,3);
        busy=true; stopping=false; finishedAt=0; startedAt=lastSample=SystemClock.elapsedRealtime(); java.util.Arrays.fill(previous,0); lastReason="建立運算環境中…";
        for(Switch toggle:toggles) toggle.setEnabled(false); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final int duration=seconds;
        executor.execute(()->{
            String failure=null;
            try { NativeEngine.run(cpu,gpu,npu,getApplicationInfo().nativeLibraryDir,duration); }
            catch(Throwable error) { NativeEngine.cancelSession(token); failure="引擎錯誤："+error.getClass().getSimpleName()+" · "+error.getMessage(); }
            finally { NativeEngine.finishSession(token); }
            final long endTime=SystemClock.elapsedRealtime();
            final String error=failure;
            handler.post(()->{
                if(destroyed) return;
                if(error!=null) lastReason=error;
                else if(!stopping) { long[] snap=NativeEngine.snapshot(); boolean failed=snap[3]==3||snap[4]==3||snap[5]==3; lastReason=failed?"測試結束 · 有引擎失敗，請查看各路詳情":"倒數結束 · 已停止所有引擎"; }
                finishedAt=endTime; busy=false; stopping=false; for(int i=0;i<3;i++) toggles[i].setEnabled(i!=2||profile.candidate);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); refresh();
            });
        }); refresh();
    }
    private void stopSession(String reason) {
        if(!busy||stopping) return; stopping=true; lastReason=reason; NativeEngine.cancelSession(ownedSession); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); refresh();
    }
    private void refresh() {
        if(temperature==null) return;
        boolean valid=Float.isFinite(batteryTemperature)&&batteryTemperature>0&&batteryTemperature<=80;
        temperature.setText(valid?String.format(Locale.ROOT,"%.1f °C",batteryTemperature):"— °C"); temperature.setTextColor(valid&&batteryTemperature>=42?ORANGE:INK);
        battery.setText(batteryPercent>=0?"電量 "+batteryPercent+"%":"電量 —"); int severity=power.getCurrentThermalStatus();
        String[] labels={"正常","輕微","中度","嚴重","危急","緊急","關機"}; thermal.setText("溫控 · "+labels[Math.min(Math.max(severity,0),6)]); thermal.setTextColor(severity>=2?ORANGE:LIME);
        if(valid) trace.push(batteryTemperature); long now=SystemClock.elapsedRealtime();
        session.setText(busy?"剩餘 "+Math.max(0,seconds-(now-startedAt)/1000)+" 秒":seconds+" 秒測試");
        if(busy&&!stopping&&now-startedAt>=seconds*1000L) { stopSession("倒數結束 · 已停止"); return; }
        long[] sample=NativeEngine.snapshot(); double elapsed=Math.max(0.001,(now-lastSample)/1000.0); int executing=0,requested=0; boolean failed=false;
        for(int i=0;i<3;i++) {
            int state=(int)sample[i+3]; if(toggles[i].isChecked()) requested++; if(state==2) executing++; if(state==3) failed=true;
            if(busy && !stopping && sample[i]>=previous[i] && now-lastSample>=400) {
                double throughput=Throughput.operationsPerSecond(sample[i]-previous[i],operations[i],elapsed);
                rates[i].setText(metric(i,throughput,false));
            } else if(!busy) {
                double duration=finishedAt>startedAt?(finishedAt-startedAt)/1000.0:0;
                rates[i].setText(duration>0?metric(i,Throughput.operationsPerSecond(sample[i],operations[i],duration),true):metric(i,0,false));
            }
            badges[i].setText(switch(state) { case 1 -> "準備中"; case 2 -> stopping?"停止中":"運算中"; case 3 -> "失敗"; case 4 -> "已停止"; default -> i==2&&!profile.candidate?"不可用":"待命"; });
            badges[i].setTextColor(state==3?ORANGE:state==2?LIME:MUTED); String message=NativeEngine.detail(i);
            if(!message.isEmpty()) {
                String count=i==0?String.format(Locale.ROOT,"%.2f GFLOP 已完成",sample[i]/1e9):sample[i]+(i==1?" dispatch 已完成":" graph 已完成");
                if(busy && !stopping && now-lastSample>=400 && i>0) count=String.format(Locale.ROOT,"%.1f %s/s · ",(sample[i]-previous[i])/elapsed,i==1?"dispatch":"graph")+count;
                details[i].setText(message+"\n"+count);
            }
        }
        if(now-lastSample>=400) { for(int i=0;i<3;i++) previous[i]=sample[i]; lastSample=now; }
        String current=lastReason;
        if(busy&&!stopping) current=failed?"部分引擎失敗 · 請查看詳情":executing==requested?executing+" 路引擎已執行 · 持續提交負載":"初始化中 · "+executing+" / "+requested+" 路已執行";
        if(!busy && NativeEngine.isPreparing()) current="HTP 首次初始化 / 圖編譯中…完成後直接復用";
        if(stopping) current=lastReason+" · 等待本次運算返回"; status.setText(current); status.setTextColor(failed?ORANGE:LIME);
        start.setText(busy?(stopping?"正在停止…":"■  立即停止"):NativeEngine.isPreparing()?"HTP 圖準備中…":"啟動 MAX  ↗"); start.setEnabled(!stopping&&!NativeEngine.isPreparing()); start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(busy?ORANGE:LIME));
    }
    private String metric(int engine,double ops,boolean average) {
        return String.format(Locale.ROOT,"%.3f %s%s",ops/(engine==0?1e9:1e12),engine==0?"GFLOPS":engine==1?"TFLOPS FP32":"TOPS INT8",average?" 平均":"");
    }
    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("運作與相容性").setMessage("目標平台\n• 8 Gen 2 / SM8550：HTP v73（真機待測）\n• 8 Gen 3 / SM8650：HTP v75\n• 8 Elite / SM8750：HTP v79\n• 8 Gen 5 / SM8845：實驗性，SDK 晶片表未明列，需真機探測\n• 8 Elite Gen 5 / SM8850：HTP v81\n\n三路皆為實際計算。NPU 使用 QNN HTP 建立 16 層 2048×2048 INT8 MatMul；沒有 CPU 推論回退。\n\n一般 App 無法鎖定最高功耗，也無法可靠取得各引擎瓦數；圖執行成功僅證明 HTP 運算成功，不代表 NPU 滿功耗。目前預設每個 HTP graph 為 274,877,906,944 ops，每次 GPU dispatch 的主迴圈為 17,179,869,184 FLOPs；數值由原生引擎目前設定取得。即時值按約 0.5 秒取樣；平均值包含初始化與釋放時間。數值代表此測試核心的邏輯運算吞吐，會受編譯器最佳化影響，並非硬體計數器或晶片理論峰值。最高功耗與其他機型相容性仍需真機驗證。\n\n電池溫度不同於表面溫度。45°C 上限不是持握安全保證。達上限、溫控嚴重、低電量、感測值缺失、到時或離開畫面都會停止提交負載；進行中的驅動呼叫返回後釋放資源。").setPositiveButton("知道了",null).show();
    }
    @Override protected void onResume() { super.onResume(); foreground=true; }
    @Override protected void onPause() { foreground=false; stopSession("已離開畫面，自動停止"); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true; NativeEngine.cancelSession(ownedSession); handler.removeCallbacksAndMessages(null); unregisterReceiver(batteryReceiver); power.removeThermalStatusListener(thermalListener); executor.shutdown(); super.onDestroy(); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value,int size,int color) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); t.setLineSpacing(dp(3),1); return t; }
    private GradientDrawable box(int color,int stroke) { GradientDrawable b=new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(12)); b.setStroke(dp(1),stroke); return b; }
    private void add(LinearLayout parent,View child,int before,int after) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(before),0,dp(after)); parent.addView(child,p); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private final class Trace extends View {
        private final float[] samples=new float[90]; private int count=0; private long sampledAt; private final Paint paint=new Paint(3);
        Trace(Context context) { super(context); setContentDescription("電池溫度趨勢，範圍 15 至 50 度"); }
        void push(float value) { if(SystemClock.elapsedRealtime()-sampledAt<1000) return; sampledAt=SystemClock.elapsedRealtime(); if(count<samples.length) samples[count++]=value; else { System.arraycopy(samples,1,samples,0,samples.length-1); samples[samples.length-1]=value; } invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStrokeWidth(dp(1)); paint.setColor(LINE); for(int i=0;i<4;i++) canvas.drawLine(0,getHeight()*i/3f,getWidth(),getHeight()*i/3f,paint);
            paint.setColor(LIME); paint.setStrokeWidth(dp(2));
            for(int i=1;i<count;i++) { float x0=(i-1)*getWidth()/89f,x1=i*getWidth()/89f; float y0=getHeight()*(1-(Math.max(15,Math.min(50,samples[i-1]))-15)/35f),y1=getHeight()*(1-(Math.max(15,Math.min(50,samples[i]))-15)/35f); canvas.drawLine(x0,y0,x1,y1,paint); }
        }
    }
}
