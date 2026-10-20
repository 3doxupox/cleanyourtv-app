package tv.cleanyour.curtain;

import android.os.SystemClock;

import java.util.ArrayList;

// Hands cues from the receiver to the live curtain, in the one process, on the main thread. Before
// the first curtain of the process attaches, a few cues are kept for at most ten seconds (the start
// and the first cues are sent back to back and may beat the activity's creation); once a curtain
// has gone, cues are dropped. Nothing here ever starts an activity.
final class CueBus {
    interface Listener {
        void onCue(Cue cue);
    }

    private static final int KEEP = 64;
    private static final long KEEP_MS = 10_000;
    private static final ArrayList<Cue> early = new ArrayList<>();
    private static final ArrayList<Long> earlyAt = new ArrayList<>();
    private static Listener listener;
    private static boolean ended;

    private CueBus() {}

    static void post(Cue cue) {
        if (listener != null) {
            listener.onCue(cue);
        } else if (!ended && early.size() < KEEP) {
            early.add(cue);
            earlyAt.add(SystemClock.uptimeMillis());
        }
    }

    static void attach(Listener l) {
        listener = l;
        ended = false;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < early.size(); i++) if (now - earlyAt.get(i) <= KEEP_MS) l.onCue(early.get(i));
        early.clear();
        earlyAt.clear();
    }

    static void detach(Listener l) {
        if (listener != l) return;
        listener = null;
        ended = true;
        early.clear();
        earlyAt.clear();
    }
}
