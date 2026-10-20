package tv.cleanyour.curtain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Takes the phone's cues (one am broadcast per stage). Only the shell or the system can send them:
// the manifest requires WRITE_SECURE_SETTINGS of the sender.
public final class CueReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
    }
}
