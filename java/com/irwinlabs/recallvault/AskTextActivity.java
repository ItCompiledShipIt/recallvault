package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Ask from anywhere (v0.47.0): highlight text in any app and the
 * system selection menu offers "Ask vault about this". This
 * activity receives the selection (PROCESS_TEXT), searches the
 * vault for it, and shows the matches - tap one to open it. The
 * app lock gates it like every other door, and private memories
 * never appear.
 */
public class AskTextActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLock.isEnabled(this) && AppLock.needsUnlock(this)) {
            startActivity(new Intent(this, LockActivity.class));
            finish();
            return;
        }
        String selected = getIntent() != null
            ? getIntent().getStringExtra(Intent.EXTRA_PROCESS_TEXT) : null;
        if (selected == null) selected = "";
        selected = selected.trim();

        Ui.applyChrome(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        TextView h = Ui.text(this, "In your vault about:", 12, Ui.ACCENT, true);
        root.addView(h);
        TextView q = Ui.text(this, selected, 15, Ui.TEXT, true);
        q.setLayoutParams(Ui.matchWrap(6, this));
        q.setMaxLines(3);
        root.addView(q);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Db db = new Db(this);
        List<Ask.Result> results = selected.isEmpty()
            ? new java.util.ArrayList<Ask.Result>()
            : Ask.search(Privacy.visible(db.all()), selected, null, true);
        if (results.isEmpty()) {
            TextView none = Ui.text(this, "Nothing in your vault matches that yet.",
                13, Ui.DIM, false);
            none.setLayoutParams(Ui.matchWrap(10, this));
            col.addView(none);
        } else {
            int shown = 0;
            for (final Ask.Result r : results) {
                if (shown++ >= 8) break;
                Button row = Ui.ghostButton(this, r.item.title);
                row.setLayoutParams(Ui.matchWrap(6, this));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent i = new Intent(AskTextActivity.this, DetailActivity.class);
                        i.putExtra("id", r.item.id);
                        startActivity(i);
                        finish();
                    }
                });
                col.addView(row);
                String snip = Ask.snippet(r.item, selected);
                if (!snip.isEmpty()) {
                    TextView s = Ui.text(this, snip, 12, Ui.DIM, false);
                    s.setLayoutParams(Ui.matchWrap(2, this));
                    col.addView(s);
                }
            }
        }
        Button close = Ui.ghostButton(this, "Close");
        close.setLayoutParams(Ui.matchWrap(10, this));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(close);
        setContentView(root);
    }
}
