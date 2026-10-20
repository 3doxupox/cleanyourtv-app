package tv.cleanyour.curtain;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

// The curtain's one screen (RFC-CURTAIN-1). Started by the phone's plan with am start; finishes on
// Back, when another activity covers it, or on its own.
public final class CurtainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View v = new View(this);
        v.setBackgroundColor(0xFF000000);
        setContentView(v);
    }

    @Override protected void onStop() {
        super.onStop();
        finish();
    }
}
