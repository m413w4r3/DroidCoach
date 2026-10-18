package dev.atlas.fixture.distraction;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Synthetic, content-free launch target used only by the ATLAS R3 emulator test. */
public final class DistractionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("Distraction test");
        label.setTextSize(28f);
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
