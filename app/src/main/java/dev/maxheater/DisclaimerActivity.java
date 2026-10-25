package dev.maxheater;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Entry gate: no native engine is referenced before the user accepts. */
public final class DisclaimerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(0xff101211);
        shell.setOnApplyWindowInsetsListener((view,insets)->{ android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(0,bars.top,0,bars.bottom); return insets; });
        ScrollView scroll=new ScrollView(this); LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24),dp(24),dp(24),dp(24)); scroll.addView(content); shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView title=text("高功耗壓力測試\n風險告知與免責聲明",26,0xffffa071); title.setTypeface(null,Typeface.BOLD); content.addView(title);
        content.addView(text("請完整閱讀後再進入 MAX。",16,0xffdcff71));
        content.addView(text("本應用會同時對 CPU、GPU 和 Qualcomm NPU 提交持續計算，可能造成極高整機功耗、快速升溫和耗電。它是實驗性壓力測試工具，不適合當作實際暖手寶或貼身取暖設備。\n\n可能風險\n• 機身或接口燙傷、電池加速老化或受損。\n• 嚴重降頻、卡頓、應用崩潰、系統關機及未儲存資料遺失。\n• 邊充電邊測試可能增加整機熱負荷。\n\n使用要求\n• 先儲存資料；使用時持續看管設備，置於通風、耐熱的平面。\n• 勿長時間手握、貼身接觸、放入口袋、床被或密閉環境；勿無人值守。\n• 電池膨脹、設備損傷、異味或異常發熱時不要使用；出現異常立即停止並斷開充電。\n\n保護與限制\n應用設有電池溫度 45°C、電量不高於 15%、系統嚴重熱壓力、離開前台和最長 5 分鐘停止條件。電池感測溫度不代表晶片、外殼或接口溫度；這些保護不能保證免於燙傷或設備損壞。停止後，正在執行的驅動呼叫仍需返回，機身也不會立即冷卻。\n\n數據與責任\nTOPS / TFLOPS 是本測試工作量換算的吞吐，不能代表官方峰值、硬體利用率或各引擎瓦數。本工具以現狀提供，不承諾效能、溫度或適用性；請自行評估設備狀況並承擔使用風險。本聲明不排除法律規定不得免除的責任。",16,0xfff0f3e9));
        LinearLayout dock=new LinearLayout(this); dock.setOrientation(LinearLayout.VERTICAL); dock.setPadding(dp(20),dp(8),dp(20),dp(12)); shell.addView(dock);
        CheckBox accepted=new CheckBox(this); accepted.setText("我已閱讀並理解風險，自願使用"); accepted.setTextColor(0xfff0f3e9); accepted.setChecked(false); dock.addView(accepted);
        Button enter=new Button(this); enter.setText("確認風險並進入"); enter.setEnabled(false); dock.addView(enter);
        accepted.setOnCheckedChangeListener((button,checked)->enter.setEnabled(checked));
        enter.setOnClickListener(view->{ if(!accepted.isChecked()) return; startActivity(new Intent(this,MainActivity.class)); finish(); });
        Button exit=new Button(this); exit.setText("退出"); dock.addView(exit); exit.setOnClickListener(view->finishAndRemoveTask()); setContentView(shell);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size,int color) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(4),1); view.setPadding(0,dp(10),0,dp(10)); return view; }
}
