package com.irwinlabs.recallvault.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RecallVault watch capture (v0.18.0 companion): one big button.
 * Tap, talk, and the words land in the phone vault as a voice
 * memory - the phone extracts dates and amounts, embeds the note
 * and arms any reminder. Dictation is the watch's own speech
 * recognizer; audio never crosses to the phone.
 */
public class MainActivity extends Activity {

    private static final int REQ_SPEECH = 42;
    private static final String PATH_NOTE = "/recallvault/note";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(24, 16, 24, 16);

        TextView title = new TextView(this);
        title.setText("RecallVault");
        title.setTextColor(Color.rgb(108, 140, 255));
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        Button talk = new Button(this);
        talk.setText("Tap & talk");
        talk.setTextSize(17);
        talk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dictate(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(8, 10, 8, 6);
        talk.setLayoutParams(blp);
        root.addView(talk);

        status = new TextView(this);
        status.setText("Say a memory - it files itself on your phone.");
        status.setTextColor(Color.rgb(152, 162, 179));
        status.setTextSize(11);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        setContentView(root);
    }

    private void dictate() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your memory");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (Exception e) {
            status.setText("Speech is not available on this watch.");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SPEECH || result != RESULT_OK || data == null) return;
        List<String> hits = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (hits == null || hits.isEmpty()) {
            status.setText("Did not catch that - try again.");
            return;
        }
        send(hits.get(0));
    }

    private void send(final String text) {
        status.setText("Sending...");
        Wearable.getNodeClient(this).getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                if (nodes == null || nodes.isEmpty()) {
                    status.setText("Phone not reachable - is it nearby?");
                    return;
                }
                Node node = nodes.get(0);
                Wearable.getMessageClient(MainActivity.this)
                    .sendMessage(node.getId(), PATH_NOTE, text.getBytes(StandardCharsets.UTF_8))
                    .addOnSuccessListener(id -> status.setText("Saved to your vault."))
                    .addOnFailureListener(e -> status.setText("Could not send - try again."));
            })
            .addOnFailureListener(e -> status.setText("Phone not reachable - is it nearby?"));
    }
}
