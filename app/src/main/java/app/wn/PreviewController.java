package app.wn;

import app.wn.audio.PreviewSession;
import java.util.concurrent.atomic.AtomicReference;

/** The source clip shares the service's AudioTrack, focus, notification and Stop path. */
final class PreviewController {
    private static final AtomicReference<PreviewSession> current=new AtomicReference<>();
    private static final AtomicReference<PreviewSession> pending=new AtomicReference<>();
    static void start(PreviewSession session){current.set(session);}
    static void prepare(PreviewSession session){pending.set(session);}
    static void activate(String id){PreviewSession p=pending.get();if(p!=null&&p.id.equals(id)&&pending.compareAndSet(p,null))current.set(p);}
    static PreviewSession session(){return current.get();}
    static void stop(){current.set(null);}
    static void stop(PreviewSession session){current.compareAndSet(session,null);pending.compareAndSet(session,null);}
    static void render(float[] block,double duck,double master) {
        PreviewSession session=current.get();
        if(session!=null){session.render(block,duck,master);if(session.finished)current.compareAndSet(session,null);}
    }
}
