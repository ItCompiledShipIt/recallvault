package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * Voice capture, two honest modes:
 *  - Dictate: live speech-to-text (Android SpeechRecognizer) -> a text
 *    memory you can ask for later word-for-word.
 *  - Record: an audio memo (MediaRecorder) saved as a file, with the
 *    dictated transcript attached when dictation ran first.
 * In "ask" mode, dictation returns the transcript to MainActivity's
 * Ask screen instead of saving a memory.
 */
public class VoiceActivity extends Activity {

    private Db db;
    private String mode = "note";
    private TextView status;
    private TextView transcriptView;
    private Button recordBtn;
    private Button dictateBtn;
    private MediaRecorder recorder;
    private PcmRecorder pcm;
    private Thread captionThread;
    private volatile boolean captionRunning = false;
    private long captionedBytes = 0;
    private final StringBuilder captions = new StringBuilder();
    private TextView captionView;
    private android.widget.ScrollView captionScroll;
    private File audioFile;
    private boolean recording = false;
    private String transcript = "";
    private SpeechRecognizer recognizer;
    private TextView elapsedView;
    private long recordStartedAt = 0;
    private final android.os.Handler timer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long s = (System.currentTimeMillis() - recordStartedAt) / 1000;
            elapsedView.setText(String.format(java.util.Locale.US, "Recording  %d:%02d", s / 60, s % 60));
            timer.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Db(this);
        mode = getIntent().getStringExtra("mode") != null ? getIntent().getStringExtra("mode") : "note";

        Ui.applyChrome(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        boolean meeting = "meeting".equals(mode);
        root.addView(Ui.text(this,
            "ask".equals(mode) ? "Ask out loud" : meeting ? "Meeting recorder" : "Voice note",
            20, Ui.TEXT, true));

        status = Ui.text(this, meeting
            ? "Records the room and files it under Meetings. With the "
              + "transcription model on this phone, captions appear below "
              + "as people talk; on save, a full clean transcript runs in "
              + "the background and the words become searchable. Dictate a "
              + "short summary too if you like - dates and amounts are "
              + "pulled out as reminders either way."
            : "Dictate turns your speech into searchable text. "
              + "Record keeps the audio itself.", 13, Ui.DIM, false);
        status.setLayoutParams(Ui.matchWrap(8, this));
        root.addView(status);

        dictateBtn = Ui.iconButton(this, "Start dictation", R.drawable.ic_mic, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        dictateBtn.setLayoutParams(Ui.matchWrap(14, this));
        dictateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDictation(); }
        });
        root.addView(dictateBtn);

        transcriptView = Ui.text(this, "", 15, Ui.TEXT, false);
        transcriptView.setBackground(Ui.panelBg(Ui.PANEL, Ui.BORDER, 12, this));
        transcriptView.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        transcriptView.setMinHeight(Ui.dp(this, 56));
        transcriptView.setLayoutParams(Ui.matchWrap(12, this));
        root.addView(transcriptView);

        if (meeting) {
            TextView capLabel = Ui.sectionLabel(this, "Live captions");
            capLabel.setLayoutParams(Ui.matchWrap(4, this));
            root.addView(capLabel);
            captionView = Ui.text(this, "Captions start a few seconds after recording does.",
                14, Ui.DIM, false);
            captionView.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            captionScroll = new android.widget.ScrollView(this);
            captionScroll.setBackground(Ui.panelBg(Ui.PANEL, Ui.BORDER, 12, this));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 170));
            clp.setMargins(0, Ui.dp(this, 4), 0, 0);
            captionScroll.setLayoutParams(clp);
            captionScroll.addView(captionView);
            root.addView(captionScroll);
        }

        if (!"ask".equals(mode)) {
            elapsedView = Ui.text(this, "", 26, Ui.WARN, true);
            elapsedView.setLayoutParams(Ui.matchWrap(10, this));
            root.addView(elapsedView);
            recordBtn = Ui.iconButton(this, "Start recording audio", R.drawable.ic_record, Ui.PANEL_HI, Ui.DANGER);
            recordBtn.setLayoutParams(Ui.matchWrap(10, this));
            recordBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (recording) stopRecording(); else startRecording();
                }
            });
            root.addView(recordBtn);

            Button save = Ui.iconButton(this, "meeting".equals(mode) ? "Save meeting" : "Save voice note",
                R.drawable.ic_check, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
            save.setLayoutParams(Ui.matchWrap(14, this));
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { saveNote(); }
            });
            root.addView(save);
        }

        Button back = Ui.iconButton(this, "Back", R.drawable.ic_back, Ui.PANEL_HI, Ui.DIM);
        back.setLayoutParams(Ui.matchWrap(10, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back);

        setContentView(root);
    }

    private void startDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this device", Toast.LENGTH_LONG).show();
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { status.setText("Listening... speak now."); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float f) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { status.setText("Processing..."); }
            @Override public void onError(int e) {
                status.setText("Dictation did not catch that (error " + e + "). Try again, "
                    + "or use Record to keep the audio.");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts != null && !texts.isEmpty()) {
                    transcript = texts.get(0);
                    transcriptView.setText(transcript);
                    status.setText("Got it. " + ("ask".equals(mode) ? "Asking..." : "Save it, or dictate again to replace."));
                    if ("ask".equals(mode)) {
                        Intent out = new Intent();
                        out.putExtra("transcript", transcript);
                        setResult(RESULT_OK, out);
                        finish();
                    }
                }
            }
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int i, Bundle b) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(intent);
    }

    private void startRecording() {
        try {
            File dir = new File(getFilesDir(), "voice");
            dir.mkdirs();
            if ("meeting".equals(mode)) {
                // PCM/WAV so the caption loop can read finished chunks
                // from the file while recording continues.
                audioFile = new File(dir, "voice_" + System.currentTimeMillis() + ".wav");
                pcm = new PcmRecorder();
                pcm.start(audioFile);
                captionedBytes = 0;
                synchronized (captions) { captions.setLength(0); }
                startCaptionLoop();
            } else {
                audioFile = new File(dir, "voice_" + System.currentTimeMillis() + ".m4a");
                recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setOutputFile(audioFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
            }
            recording = true;
            recordStartedAt = System.currentTimeMillis();
            timer.post(tick);
            recordBtn.setText("Stop recording");
            status.setText("Recording... tap Stop when you are done.");
        } catch (Exception e) {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show();
        }
    }

    /** Caption worker: every ~12s of new audio becomes a caption segment. */
    private void startCaptionLoop() {
        captionRunning = true;
        captionThread = new Thread(new Runnable() {
            @Override public void run() {
                while (captionRunning) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    if (pcm == null) continue;
                    long avail = pcm.dataBytes();
                    long pending = avail - captionedBytes;
                    long minChunk = 12L * PcmRecorder.BYTES_PER_SEC;
                    if (pending >= minChunk) {
                        long take = Math.min(pending, 30L * PcmRecorder.BYTES_PER_SEC);
                        take -= take % 2;
                        if (!captionSegment(take)) return; // model missing: stop quietly
                    }
                }
                // Final flush: caption the tail (down to ~1.5s) on stop.
                if (pcm != null) {
                    long pending = pcm.dataBytes() - captionedBytes;
                    if (pending >= 3L * PcmRecorder.RATE) {
                        captionSegment(pending - pending % 2);
                    }
                }
            }
        }, "caption-loop");
        captionThread.start();
    }

    /** Transcribe one segment starting at captionedBytes. False = captions unavailable. */
    private boolean captionSegment(long takeBytes) {
        try {
            byte[] raw = pcm.readPcm(captionedBytes, (int) takeBytes);
            if (raw.length < 2) return true;
            captionedBytes += raw.length;
            float[] samples = PcmRecorder.toFloats(raw);
            final String text = Whisper.transcribeSegment(getApplicationContext(), samples);
            if (text != null && !text.isEmpty()) {
                synchronized (captions) {
                    if (captions.length() > 0) captions.append(' ');
                    captions.append(text);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (captionView != null) {
                            String all;
                            synchronized (captions) { all = captions.toString(); }
                            captionView.setText(all);
                            captionView.setTextColor(Ui.TEXT);
                            if (captionScroll != null) {
                                captionScroll.post(new Runnable() {
                                    @Override public void run() { captionScroll.fullScroll(View.FOCUS_DOWN); }
                                });
                            }
                        }
                    }
                });
            }
            return true;
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (captionView != null) {
                        captionView.setText("Live captions are off - the transcription model is not on this phone yet (Settings). The recording itself is unaffected.");
                    }
                }
            });
            return false;
        }
    }

    private void stopRecording() {
        if ("meeting".equals(mode) && pcm != null) {
            recording = false;
            timer.removeCallbacks(tick);
            captionRunning = false;
            pcm.stop();
            // v0.11.0: no UI-thread join. Stop is instant; the caption
            // worker flushes the final segment on its own thread into
            // the shared captions buffer, saveNote snapshots whatever
            // has landed, and the full background transcription pass
            // replaces it with the complete transcript anyway.
            captionThread = null;
            pcm = null;
            if (elapsedView != null) elapsedView.setText("");
            recordBtn.setText("Start recording audio");
            status.setText("Audio recorded. Save it - the full transcript finishes in the background.");
            return;
        }
        try {
            recorder.stop();
        } catch (Exception e) {
            // stop can throw if called too early; the file may be unusable.
        }
        try { recorder.release(); } catch (Exception e) {}
        recorder = null;
        recording = false;
        timer.removeCallbacks(tick);
        if (elapsedView != null) elapsedView.setText("");
        recordBtn.setText("Start recording audio");
        status.setText("Audio recorded. Save it, with any dictated text attached.");
    }

    private void saveNote() {
        if (recording) stopRecording();
        if ((audioFile == null || !audioFile.exists()) && transcript.isEmpty()) {
            Toast.makeText(this, "Dictate or record something first", Toast.LENGTH_SHORT).show();
            return;
        }
        Item it = new Item();
        it.type = "VOICE";
        it.createdAt = System.currentTimeMillis();
        it.body = transcript;
        String captionText;
        synchronized (captions) { captionText = captions.toString().trim(); }
        boolean meetingMode = "meeting".equals(mode);
        // Meetings: live captions are searchable immediately; the full
        // background pass below replaces them with a cleaner transcript.
        it.extracted = meetingMode && !captionText.isEmpty() ? captionText : transcript;
        it.filePath = audioFile != null ? audioFile.getAbsolutePath() : "";
        it.mime = audioFile != null ? (meetingMode ? "audio/wav" : "audio/mp4") : "";
        String titleSource = transcript.isEmpty() && meetingMode ? captionText : transcript;
        Extract.Facts f = Extract.analyze(titleSource);
        if (meetingMode) {
            it.category = "Meetings";
            it.title = Extract.suggestTitle(titleSource, "Meeting " + Ui.dateLine(it.createdAt));
        } else {
            it.category = f.category;
            it.title = Extract.suggestTitle(transcript, "Voice note " + Ui.dateLine(it.createdAt));
        }
        it.reminderAt = f.dueDate;
        StringBuilder am = new StringBuilder();
        for (int i = 0; i < f.amounts.size(); i++) {
            if (i > 0) am.append(',');
            am.append(f.amounts.get(i));
        }
        it.amounts = am.toString();
        db.insert(it);
        if (meetingMode && it.extracted != null && !it.extracted.isEmpty()) {
            // Action items from the live captions right away (v0.29.0);
            // the full transcript pass below adds any it finds too -
            // duplicates are skipped by title.
            Actions.fileActions(db, it, it.extracted);
        }
        Embed.embedPending(this, db);
        boolean meeting = "meeting".equals(mode);
        Toast.makeText(this, meeting ? "Meeting saved" : "Voice note saved", Toast.LENGTH_SHORT).show();
        if (audioFile != null && audioFile.exists()) {
            // Best-effort: transcribe the recording on this phone in
            // the background; the words land on the saved item when
            // ready. v0.11.0: voice notes get this too, not just
            // meetings - a note recorded without dictation used to
            // stay wordless until someone tapped Transcribe in Detail.
            final long savedId = it.id;
            final File audio = audioFile;
            final boolean meetingF = meeting;
            Toast.makeText(this, "Transcribing on this phone in the background...", Toast.LENGTH_SHORT).show();
            Transcribe.transcribe(getApplicationContext(), audio, new Transcribe.Callback() {
                @Override public void onText(String text) {
                    Db d2 = new Db(getApplicationContext());
                    Item saved = d2.get(savedId);
                    if (saved == null) return;
                    saved.extracted = text;
                    saved.embedding = "";
                    saved.embeddingLocal = "";
                    if (!meetingF && (saved.body == null || saved.body.isEmpty())
                            && saved.title != null && saved.title.startsWith("Voice note ")) {
                        // Undictated voice note: name it from its own words.
                        saved.title = Extract.suggestTitle(text, saved.title);
                    }
                    Extract.Facts f = Extract.analyze(saved.title + "\n" + text);
                    if (f.dueDate > 0 && saved.reminderAt <= 0) saved.reminderAt = f.dueDate;
                    if (!f.amounts.isEmpty() && saved.amounts.isEmpty()) {
                        StringBuilder am = new StringBuilder();
                        for (int i = 0; i < f.amounts.size(); i++) {
                            if (i > 0) am.append(',');
                            am.append(f.amounts.get(i));
                        }
                        saved.amounts = am.toString();
                    }
                    d2.update(saved);
                    if (meetingF) {
                        // Commitments in the full transcript become
                        // action items (v0.29.0).
                        Actions.fileActions(d2, saved, text);
                    }
                    Embed.embedPending(getApplicationContext(), d2);
                }
                @Override public void onError(String message) {
                    // Meeting is saved either way; transcription can be
                    // retried from the meeting's detail screen.
                }
            });
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        captionRunning = false;
        if (pcm != null) {
            try { pcm.stop(); } catch (Exception e) {}
            pcm = null;
        }
        if (recognizer != null) recognizer.destroy();
        if (recorder != null) {
            try { recorder.release(); } catch (Exception e) {}
        }
        super.onDestroy();
    }
}
