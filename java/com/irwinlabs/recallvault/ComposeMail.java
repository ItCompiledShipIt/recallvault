package com.irwinlabs.recallvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * ComposeMail - the human-confirmed send sheet (v0.57.0).
 * Clio can draft all day (skills do that), but a send only
 * happens when a person fills this in and taps Send. Used
 * from Settings (blank) and from a memory's Detail screen
 * (prefilled with the memory).
 */
public final class ComposeMail {

    private ComposeMail() {}

    public static void show(final Activity a, String subject, String body) {
        if (!MailFetch.configured(a)) {
            Toast.makeText(a, "Connect Gmail first - Settings, Email - Gmail", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 4));
        final EditText toIn = field(a, "To", "");
        final EditText subIn = field(a, "Subject", subject);
        final EditText bodyIn = field(a, "Message", body);
        bodyIn.setSingleLine(false);
        bodyIn.setMinLines(6);
        bodyIn.setGravity(android.view.Gravity.TOP);
        box.addView(toIn);
        box.addView(subIn);
        box.addView(bodyIn);
        final AlertDialog dlg = new AlertDialog.Builder(a)
            .setTitle("Email from Clio")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create();
        Button send = Ui.button(a, "Send", Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        send.setLayoutParams(Ui.matchWrap(8, a));
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String to = toIn.getText().toString();
                final String sub = subIn.getText().toString();
                final String bod = bodyIn.getText().toString();
                String err = MailFilter.composeError(to, sub, bod);
                if (!err.isEmpty()) {
                    Toast.makeText(a, err, Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(a, "Sending...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String sendErr = MailFetch.send(a, to, sub, bod);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (sendErr.isEmpty()) {
                                    Toast.makeText(a, "Sent to " + to.trim(), Toast.LENGTH_LONG).show();
                                    dlg.dismiss();
                                } else {
                                    Toast.makeText(a, sendErr, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }, "mail-send").start();
            }
        });
        box.addView(send);
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) { toIn.requestFocus(); }
        });
        dlg.show();
    }

    private static EditText field(Activity a, String hint, String value) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setSingleLine(true);
        if (value != null) e.setText(value);
        e.setLayoutParams(Ui.matchWrap(4, a));
        return e;
    }
}
