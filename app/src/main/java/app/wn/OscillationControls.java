package app.wn;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import app.wn.audio.Oscillation;
import java.util.Locale;

final class OscillationControls {
    private static final String[] NAMES={"Tone level","Noise level","Voice oscillation speed","Variation","Pitch shift","Tone spread"};
    static void add(Activity activity,LinearLayout parent,SettingsStore settings,String id,Runnable changed) {
        LinearLayout controls=Ui.column(activity);parent.addView(controls);
        fill(activity,controls,settings,id,changed);
    }
    private static void fill(Activity a,LinearLayout body,SettingsStore settings,String id,Runnable changed) {
        body.removeAllViews();SettingsStore.Layer layer=settings.layer(id);if(layer==null)return;
        Oscillation values=layer.oscillation;
        for(int field=0;field<NAMES.length;field++) {
            final int index=field;LinearLayout row=Ui.row(a);
            row.addView(Ui.text(a,NAMES[field],13,Ui.INK,false),new LinearLayout.LayoutParams(0,-2,1));
            TextView value=Ui.text(a,format(field,values.value(field)),13,Ui.ACCENT,true);row.addView(value);Ui.add(a,body,row,12);
            SeekBar slider=new SeekBar(a);slider.setContentDescription(NAMES[field]);
            slider.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));slider.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
            slider.setMax(field==2?375:field==4?240:200);
            slider.setProgress(field==2?(int)Math.round(values.speed*100)-25:field==4?(int)Math.round(values.semitones*10)+120:(int)Math.round(values.value(field)*100));
            body.addView(slider,new LinearLayout.LayoutParams(-1,Ui.dp(a,48)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) {
                    if(!user)return;
                    double v=index==2?(progress+25)/100.0:index==4?(progress-120)/10.0:progress/100.0;
                    settings.oscillation(id,index,v);value.setText(format(index,v));changed.run();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    try{settings.saveControls();}catch(RuntimeException e){Ui.error(a,"Save settings",e.getMessage());}
                }
            });
        }
        Button reset=Ui.button(a,"Reset oscillating settings",false);Ui.add(a,body,reset,12);
        reset.setOnClickListener(v->{try{settings.resetOscillation(id);changed.run();fill(a,body,settings,id,changed);}
            catch(RuntimeException e){Ui.error(a,"Reset settings",e.getMessage());}});
    }
    private static String format(int field,double value) {
        return field==4?String.format(Locale.getDefault(),"%+.1f st",value):String.format(Locale.getDefault(),"%.0f%%",value*100);
    }
}
