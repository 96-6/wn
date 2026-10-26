package app.wn;

/** Updated by the service, observed only while the activity is visible. */
public final class PlaybackStatus {
    private PlaybackStatus() {}
    public static volatile boolean active, playing;
    public static volatile String message = "Stopped";
    public static volatile String error = "";
}
