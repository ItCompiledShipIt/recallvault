package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scan-to-pair (v0.79.0): point the phone at the QR code in
 * Clio for Desktop's Preferences and the two are paired -
 * no address to type, no key to paste. The code is the
 * pairing payload SyncClient.parsePairUri defines; the
 * scan saves it exactly as the typed setup does, then a
 * first sync runs before this screen closes.
 */
public class ScanActivity extends Activity
        implements androidx.lifecycle.LifecycleOwner {

    // CameraX binds to a LifecycleOwner; the rest of Clio
    // uses plain Activities (no AppCompat), so this screen
    // carries a small manual LifecycleRegistry instead of
    // pulling in a new activity base class for one screen.
    private final androidx.lifecycle.LifecycleRegistry registry =
        new androidx.lifecycle.LifecycleRegistry(this);

    @androidx.annotation.NonNull
    @Override
    public androidx.lifecycle.Lifecycle getLifecycle() {
        return registry;
    }

    @Override
    protected void onStart() {
        super.onStart();
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.RESUMED);
    }

    @Override
    protected void onPause() {
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.STARTED);
        super.onPause();
    }

    @Override
    protected void onStop() {
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.CREATED);
        super.onStop();
    }

    private PreviewView preview;
    private ExecutorService exec;
    private BarcodeScanner scanner;
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.CREATED);
        Ui.applyTheme(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
        TextView h = Ui.text(this, "Pair with your computer", 20, Ui.TEXT, true);
        h.setTypeface(Ui.faceDisplay(this));
        root.addView(h);
        TextView sub = Ui.text(this,
            "Open Clio on your computer, go to Preferences, and point "
            + "this phone at the QR code there.", 14, Ui.DIM, false);
        sub.setLayoutParams(Ui.matchWrap(6, this));
        root.addView(sub);
        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.setMargins(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        preview.setLayoutParams(plp);
        root.addView(preview);
        TextView cancel = Ui.text(this, "Cancel", 15, Ui.WARN, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(cancel);
        setContentView(root);
        Ui.installInsets(this);

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
        exec = Executors.newSingleThreadExecutor();
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 971);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 971) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this,
                    "Pairing by scan needs the camera - you can still type "
                    + "the address and key in Settings.",
                    Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> fut =
            ProcessCameraProvider.getInstance(this);
        fut.addListener(new Runnable() {
            @Override public void run() {
                try {
                    ProcessCameraProvider provider = fut.get();
                    Preview pv = new Preview.Builder().build();
                    pv.setSurfaceProvider(preview.getSurfaceProvider());
                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                    analysis.setAnalyzer(exec, new ImageAnalysis.Analyzer() {
                        @Override public void analyze(ImageProxy image) {
                            scan(image);
                        }
                    });
                    provider.unbindAll();
                    provider.bindToLifecycle(ScanActivity.this,
                        CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis);
                } catch (Exception e) {
                    Toast.makeText(ScanActivity.this,
                        "The camera could not start.", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void scan(final ImageProxy image) {
        if (done || image.getImage() == null) {
            image.close();
            return;
        }
        InputImage in = InputImage.fromMediaImage(image.getImage(),
            image.getImageInfo().getRotationDegrees());
        scanner.process(in)
            .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<java.util.List<Barcode>>() {
                @Override public void onSuccess(java.util.List<Barcode> codes) {
                    for (Barcode b : codes) {
                        String[] pair = SyncClient.parsePairUri(b.getRawValue());
                        if (pair != null && !done) {
                            done = true;
                            paired(pair[0], pair[1]);
                            break;
                        }
                    }
                }
            })
            .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<java.util.List<Barcode>>() {
                @Override public void onComplete(com.google.android.gms.tasks.Task<java.util.List<Barcode>> t) {
                    image.close();
                }
            });
    }

    private void paired(final String addr, final String key) {
        SyncClient.savePairing(this, addr, key);
        Toast.makeText(this, "Paired with " + addr + " - syncing...",
            Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                SyncClient.syncNow(getApplicationContext());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        registry.setCurrentState(androidx.lifecycle.Lifecycle.State.DESTROYED);
        super.onDestroy();
        if (exec != null) exec.shutdown();
        if (scanner != null) scanner.close();
    }
}
