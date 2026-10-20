package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.SeekBar;

public class Gauge extends Item {
    public static final int INDEFINITE = -1;
    public static final int CONTINUOUS_IDLE = 0;
    public static final int INCREMENTAL_IDLE = 1;
    public static final int CONTINUOUS_RUNNING = 2;
    public static final int INCREMENTAL_UPDATING = 3;

    private final boolean interactive;
    private int max;
    private int value;

    public Gauge(String label, boolean interactive, int maxValue, int initialValue) {
        super(label);
        this.interactive = interactive;
        this.max = maxValue;
        this.value = initialValue;
    }

    public void setValue(int value) {
        this.value = max > 0 ? Math.max(0, Math.min(max, value)) : value;
        invalidate();
    }

    public int getValue() { return value; }

    public void setMaxValue(int maxValue) {
        max = maxValue;
        invalidate();
    }

    public int getMaxValue() { return max; }

    public boolean isInteractive() { return interactive; }

    @Override
    View createView(Context ctx) {
        if (max == INDEFINITE) {
            ProgressBar pb = new ProgressBar(ctx);
            pb.setIndeterminate(true);
            return pb;
        }
        if (interactive) {
            SeekBar s = new SeekBar(ctx);
            s.setMax(max);
            s.setProgress(value);
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    value = p;
                    notifyStateChanged();
                }

                public void onStartTrackingTouch(SeekBar sb) {}

                public void onStopTrackingTouch(SeekBar sb) {}
            });
            return s;
        }
        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, max));
        pb.setProgress(value);
        return pb;
    }
}
