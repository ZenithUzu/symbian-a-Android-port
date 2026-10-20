package javax.microedition.lcdui;

import android.content.Context;
import android.view.View;

import java.text.DateFormat;
import java.util.Date;
import java.util.TimeZone;

public class DateField extends Item {
    public static final int DATE = 1;
    public static final int TIME = 2;
    public static final int DATE_TIME = 3;

    private Date date;
    private int mode;

    public DateField(String label, int mode) {
        this(label, mode, null);
    }

    public DateField(String label, int mode, TimeZone timeZone) {
        super(label);
        this.mode = mode;
    }

    public Date getDate() { return date; }

    public void setDate(Date date) {
        this.date = date;
        invalidate();
    }

    public int getInputMode() { return mode; }

    public void setInputMode(int mode) { this.mode = mode; }

    @Override
    View createView(Context ctx) {
        String s = "--";
        if (date != null) {
            DateFormat f = mode == TIME ? DateFormat.getTimeInstance()
                    : mode == DATE ? DateFormat.getDateInstance() : DateFormat.getDateTimeInstance();
            s = f.format(date);
        }
        return Ui.text(ctx, s, 16, false);
    }
}
