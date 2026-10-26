package app.wn;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import app.wn.audio.PreviewClip;
import app.wn.audio.PreviewSession;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fine controls and a once-through, local source/masker comparison. */
public final class OscillationActivity extends Activity {
    private static final int AUDIO=720;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SettingsStore settings;
    private String id;
    private EditText start,duration;
    private Button choose,play,stop;
    private TextView status,balanceText;
    private Spinner monitor;
    private SeekBar balance;
    private LinearLayout fine;
    private PreviewClip clip;
    private PreviewSession session;
    private AndroidProfiler.Control decoding;
    private boolean busy,ownsPlayback;
    private long previewStarted;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(session!=null) {
            if(session.finished){stopPreview();status.setText(R.string.preview_finished);}
            else if(!PlaybackStatus.active&&android.os.SystemClock.elapsedRealtime()-previewStarted>3000){stopPreview();status.setText(R.string.preview_stopped);}
            else status.setText(String.format(Locale.getDefault(),"%.1f / %.1f s",session.seconds(),session.clip.frames/48000.0));
        }
        main.postDelayed(this,150);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        settings=SettingsStore.get(this);id=getIntent().getStringExtra("generator");
        SettingsStore.Layer layer=settings.layer(id);
        if(layer==null){finish();return;}
        LinearLayout body=Ui.screen(this,"Generator settings",true);
        fine=Ui.card(this,body);renderFine();
        LinearLayout preview=Ui.card(this,body);
        preview.addView(Ui.text(this,"Jamming preview",18,Ui.INK,true));
        Ui.add(this,preview,Ui.text(this,"Compare a file with all enabled generators. The file plays once.",13,Ui.MUTED,false),8);
        SettingsStore.SavedProfile profile=settings.findProfile(layer.profileId);
        String[] types=profile!=null&&profile.preset.voice!=null?new String[]{"Continuous noise","Oscillating noise + tone","Voice masker"}:new String[]{"Continuous noise","Oscillating noise + tone"};
        Spinner type=new Spinner(this);type.setContentDescription("Preview generator type");
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,types));
        type.setSelection(Math.min(types.length-1,layer.texture.ordinal()));Ui.add(this,preview,type,8);
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int index,long row) {
                SettingsStore.Layer current=settings.layer(id);app.wn.audio.MixerConfig.Texture texture=app.wn.audio.MixerConfig.Texture.values()[index];
                if(current!=null&&current.texture!=texture)try{settings.texture(id,texture);update();renderFine();}catch(RuntimeException e){Ui.error(OscillationActivity.this,"Generator type",e.getMessage());}
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        start=input(preview,"File start (seconds)",state==null?"0":state.getString("start","0"));
        duration=input(preview,"Duration (1–60 seconds)",state==null?"60":state.getString("duration","60"));
        choose=Ui.button(this,"Choose preview audio",false);choose.setOnClickListener(v->choose());Ui.add(this,preview,choose,12);
        balanceText=Ui.text(this,"",14,Ui.INK,true);Ui.add(this,preview,balanceText,12);
        balance=new SeekBar(this);balance.setContentDescription("Masker over source");balance.setMax(72);
        balance.setProgress(state==null?getPreferences(0).getInt("balance",36):state.getInt("balance",36));preview.addView(balance,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        balance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int p,boolean user){updateBalance();}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });updateBalance();
        monitor=new Spinner(this);monitor.setContentDescription("Preview monitor");
        monitor.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Mixed","Source only","Masker only"}));
        monitor.setSelection(state==null?getPreferences(0).getInt("monitor",0):state.getInt("monitor",0));Ui.add(this,preview,monitor,8);
        monitor.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int index,long row){if(session!=null)session.monitor=PreviewSession.Monitor.values()[index];}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        Button resetPreview=Ui.button(this,"Reset preview balance",false);Ui.add(this,preview,resetPreview,8);
        resetPreview.setOnClickListener(v->{balance.setProgress(36);monitor.setSelection(0);});
        Button recalibrate=Ui.button(this,"Rebalance preview",false);Ui.add(this,preview,recalibrate,8);
        recalibrate.setOnClickListener(v->{if(session!=null)session.recalibrate();});
        play=Ui.button(this,"Play preview",true);play.setEnabled(false);play.setOnClickListener(v->play());Ui.add(this,preview,play,12);
        stop=Ui.button(this,"Stop preview",false);stop.setEnabled(false);stop.setOnClickListener(v->{stopPreview();status.setText(R.string.preview_stopped);});Ui.add(this,preview,stop,8);
        status=Ui.text(this,"Choose audio to preview",13,Ui.MUTED,false);Ui.add(this,preview,status,10);
        Ui.add(this,preview,Ui.text(this,"Balance sets the starting RMS ratio. Fine controls then change the masking; Rebalance sets a new reference. Room acoustics affect the result.",12,Ui.MUTED,false),10);
    }
    private void renderFine() {
        SettingsStore.Layer current=settings.layer(id);fine.removeAllViews();
        boolean continuous=current==null||current.texture==app.wn.audio.MixerConfig.Texture.CONTINUOUS;
        fine.setVisibility(continuous?android.view.View.GONE:android.view.View.VISIBLE);
        if(!continuous)OscillationControls.add(this,fine,settings,id,this::update);
    }
    private EditText input(LinearLayout parent,String label,String value) {
        Ui.add(this,parent,Ui.text(this,label,13,Ui.INK,true),10);EditText e=new EditText(this);e.setTextColor(Ui.INK);e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setText(value);e.setContentDescription(label);Ui.add(this,parent,e,2);return e;
    }
    private double number(EditText field,double low,double high) {
        double value=Double.parseDouble(field.getText().toString());
        if(!Double.isFinite(value)||value<low||value>high)throw new IllegalArgumentException("Enter a value from "+low+" to "+high+".");
        return value;
    }
    private void choose() {
        try {
            number(start,0,86400);number(duration,1,60);stopPreview();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),AUDIO);
        }catch(RuntimeException e){Ui.error(this,"Preview audio",e.getMessage());}
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==AUDIO&&result==RESULT_OK&&data!=null&&data.getData()!=null)load(data.getData());
    }
    void load(Uri uri) {
        double offset,length;
        try{offset=number(start,0,86400);length=number(duration,1,60);}catch(RuntimeException e){Ui.error(this,"Preview audio",e.getMessage());return;}
        decoding=new AndroidProfiler.Control();AndroidProfiler.Control control=decoding;busy=true;choose.setEnabled(false);play.setEnabled(false);status.setText(R.string.preview_loading);clip=null;
        io.execute(()->{
            try {
                PreviewClip loaded=AndroidProfiler.previewFile(getApplicationContext(),uri,offset,length,control,s->{});
                AndroidProfiler.check(control);
                main.post(()->{if(!isDestroyed()&&!control.cancelled&&decoding==control){clip=loaded;loaded();status.setText(String.format(Locale.getDefault(),"Ready · %.1f s",loaded.frames/48000.0));}});
            }catch(Exception e){main.post(()->{if(!isDestroyed()&&decoding==control){loaded();status.setText(control.cancelled?R.string.preview_cancelled:R.string.preview_failed);if(!control.cancelled)Ui.error(this,"Preview audio",e.getMessage());}});}
        });
    }
    private void loaded(){busy=false;choose.setEnabled(true);play.setEnabled(clip!=null);}
    private void updateBalance() {
        double db=balance.getProgress()/2.0-12;
        balanceText.setText(String.format(Locale.getDefault(),"Starting balance: %+.1f dB (%.2f× RMS)",db,Math.pow(10,db/20)));
        if(session!=null)session.balanceDb=db;
    }
    private void play() {
        if(clip==null)return;
        if(!settings.snapshot().hasAudibleLayer()){Ui.error(this,"Preview","Enable a generator and raise its level, tone/noise levels and Master.");return;}
        stopPreview();session=new PreviewSession(clip);session.balanceDb=balance.getProgress()/2.0-12;
        session.monitor=PreviewSession.Monitor.values()[monitor.getSelectedItemPosition()];ownsPlayback=!PlaybackStatus.active;
        previewStarted=android.os.SystemClock.elapsedRealtime();
        PreviewController.prepare(session);
        try{startForegroundService(new Intent(this,NoiseService.class).setAction(NoiseService.START).putExtra("preview",session.id));stop.setEnabled(true);play.setEnabled(false);}
        catch(RuntimeException e){stopPreview();Ui.error(this,"Preview",e.getMessage());}
    }
    private void stopPreview() {
        if(session!=null){PreviewController.stop(session);session=null;}
        if(ownsPlayback){startService(new Intent(this,NoiseService.class).setAction(NoiseService.STOP));ownsPlayback=false;}
        if(stop!=null)stop.setEnabled(false);if(play!=null)play.setEnabled(clip!=null&&!busy);
    }
    private void update(){if(PlaybackStatus.active)startService(new Intent(this,NoiseService.class).setAction(NoiseService.UPDATE));}
    @Override protected void onResume(){super.onResume();if(settings!=null&&settings.layer(id)==null){finish();return;}main.postDelayed(poll,250);}
    @Override protected void onPause(){main.removeCallbacks(poll);if(decoding!=null)decoding.cancelled=true;stopPreview();
        if(balance!=null)getPreferences(0).edit().putInt("balance",balance.getProgress()).putInt("monitor",monitor.getSelectedItemPosition()).apply();super.onPause();}
    @Override protected void onDestroy(){if(decoding!=null)decoding.cancelled=true;io.shutdownNow();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(start==null)return;state.putString("start",start.getText().toString());state.putString("duration",duration.getText().toString());state.putInt("balance",balance.getProgress());state.putInt("monitor",monitor.getSelectedItemPosition());}
}
