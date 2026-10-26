package app.wn;

import android.app.Notification;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import app.wn.audio.MixerConfig;

/** User-started media FGS. No timers, boot receivers, polling jobs, network or microphone. */
public final class NoiseService extends Service {
    public static final String START = "app.wn.START", STOP = "app.wn.STOP", UPDATE = "app.wn.UPDATE";
    private static final String CHANNEL = "noise_playback";
    private static final int NOTIFICATION = 10;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SettingsStore settings;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private AudioOutput output;
    private boolean pausedByFocus, ownsFocus, foreground;
    private long outputGeneration;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) stopByUser("Output disconnected · stopped");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        settings = SettingsStore.get(this);
        audioManager = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioOutput.attributes()).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::onFocusChange, main).build();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WN:NoiseSynthesis");
        wakeLock.setReferenceCounted(false);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Noise playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback status and Stop control"); channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        session = new MediaSession(this, "WN");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (key == null) return super.onMediaButtonEvent(intent);
                int code = key.getKeyCode();
                if (code != KeyEvent.KEYCODE_MEDIA_STOP && code != KeyEvent.KEYCODE_MEDIA_PAUSE
                        && code != KeyEvent.KEYCODE_MEDIA_PLAY && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    return super.onMediaButtonEvent(intent);
                if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_MEDIA_PLAY) begin();
                    else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !settings.wantsPlayback()) begin();
                    else stopByUser("Stopped");
                }
                return true;
            }
            @Override public void onPlay() { begin(); }
            @Override public void onPause() { stopByUser("Stopped"); }
            @Override public void onStop() { stopByUser("Stopped"); }
        }, main);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "WN · Noise mixer")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Noise generators").build());
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) { stopByUser("Stopped"); return START_NOT_STICKY; }
        if (UPDATE.equals(action)) {
            if(output==null&&!settings.wantsPlayback()){stopSelf();return START_NOT_STICKY;}
            if (output != null) output.update(settings.snapshot());
            if (foreground) refreshNotification();
            return settings.wantsPlayback() ? START_STICKY : START_NOT_STICKY;
        }
        if (START.equals(action) || (intent == null && settings.wantsPlayback())) {
            begin();
            if(output!=null&&intent!=null)PreviewController.activate(intent.getStringExtra("preview"));
        }
        else stopSelf();
        return settings.wantsPlayback() ? START_STICKY : START_NOT_STICKY;
    }

    private void begin() {
        if (output != null) { output.update(settings.snapshot()); return; }
        if (!settings.snapshot().hasAudibleLayer()) {
            PlaybackStatus.error = "Enable a layer and set its level and master volume above zero.";
            stopByUser("Stopped"); return;
        }
        PlaybackStatus.error = "";
        settings.setWantsPlayback(true);
        PlaybackStatus.active = true;
        PlaybackStatus.message = "Starting noise…";
        session.setActive(true);
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION, n);
        foreground = true;
        if (!ownsFocus) {
            ownsFocus = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!ownsFocus) {
                PlaybackStatus.error = "Audio focus is unavailable. Finish the call or other playback, then tap Start.";
                stopByUser("Could not start"); return;
            }
        }
        startOutput();
    }

    // User-controlled continuous playback has no timer. releaseOutput/onDestroy release this lock.
    @SuppressLint("WakelockTimeout")
    private void startOutput() {
        if (output != null || !settings.wantsPlayback()) return;
        pausedByFocus = false;
        // AudioTrack does not own the CPU synthesis work; hold only while generating.
        wakeLock.acquire();
        long generation = ++outputGeneration;
        output = new AudioOutput(settings.snapshot(), error -> main.post(() -> {
            if (generation == outputGeneration) { PlaybackStatus.error = error; stopByUser("Playback stopped"); }
        }));
        output.start();
        PlaybackStatus.active = PlaybackStatus.playing = true;
        PlaybackStatus.message = "Playing";
        refreshNotification();
    }

    private void onFocusChange(int change) {
        if (!settings.wantsPlayback()) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (pausedByFocus) startOutput();
            if (output != null) output.setDucked(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (output != null) output.setDucked(true);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausedByFocus = true; releaseOutput();
            PlaybackStatus.message = "Paused by another app · will resume";
            refreshNotification();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            stopByUser("Stopped · another app took audio focus");
        }
    }

    private void releaseOutput() {
        outputGeneration++;
        if (output != null) { output.stop(); output = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        PlaybackStatus.playing = false;
    }

    private void stopByUser(String message) {
        PreviewController.stop();
        settings.setWantsPlayback(false);
        pausedByFocus = false;
        releaseOutput();
        if (ownsFocus) { audioManager.abandonAudioFocusRequest(focus); ownsFocus = false; }
        PlaybackStatus.active = false; PlaybackStatus.message = message;
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0).build());
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NoiseService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        MixerConfig c = settings.snapshot();
        String layers = c.enabledCount() + (c.enabledCount() == 1 ? " generator" : " generators");
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_noise)
                .setContentTitle("WN · " + layers).setContentText(PlaybackStatus.message)
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .setOngoing(true).setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0))
                .build();
    }

    private void refreshNotification() {
        if (!foreground) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                .setState(PlaybackStatus.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, PlaybackStatus.playing ? 1 : 0).build());
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }

    @Override public void onDestroy() {
        PreviewController.stop();
        releaseOutput();
        if (ownsFocus) audioManager.abandonAudioFocusRequest(focus);
        if (session != null) { session.setActive(false); session.release(); }
        unregisterReceiver(noisyReceiver);
        PlaybackStatus.active = false;
        if (settings.wantsPlayback()) PlaybackStatus.message = "Playback interrupted · tap Start to resume";
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
