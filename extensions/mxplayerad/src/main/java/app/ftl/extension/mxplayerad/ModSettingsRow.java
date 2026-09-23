package app.ftl.extension.mxplayerad;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

public class ModSettingsRow extends TextView {
    public ModSettingsRow(Context context, AttributeSet attrs) {
        super(context, attrs);

        setText("Mod Settings");
        setTextSize(16f);
        setGravity(Gravity.CENTER_VERTICAL);

        float density = context.getResources().getDisplayMetrics().density;
        int horizontal = (int) (20 * density + 0.5f);
        int vertical = (int) (16 * density + 0.5f);
        setPadding(horizontal, vertical, horizontal, vertical);

        TypedValue background = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            && background.resourceId != 0) {
            setBackgroundResource(background.resourceId);
        }

        setClickable(true);
        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ModSettings.showDialog(view.getContext());
            }
        });
    }
}
