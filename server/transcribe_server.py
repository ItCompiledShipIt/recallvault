#!/usr/bin/env python3
"""RecallVault transcription server - bigpc, Tailscale only.

POST /transcribe  (raw audio bytes in the body, any format PyAV reads)
  -> 200 {"text": "...", "language": "en", "duration": 12.3}
GET  /health -> {"status": "ok"}

faster-whisper base.en, model loaded once at startup and kept resident.
Single inference lock: transcription is serialized (it is a home server
for one phone, not a fleet).
"""
import json
import os
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from faster_whisper import WhisperModel

HOST = os.environ.get("RV_HOST", "100.88.174.124")
PORT = int(os.environ.get("RV_PORT", "11436"))
MODEL = os.environ.get("RV_MODEL", "base.en")
DEVICE = os.environ.get("RV_DEVICE", "cpu")
COMPUTE = os.environ.get("RV_COMPUTE", "int8")

print(f"loading faster-whisper {MODEL} (device={DEVICE}, compute={COMPUTE})...", flush=True)
model = WhisperModel(MODEL, device=DEVICE, compute_type=COMPUTE)
print("model ready", flush=True)
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "ok"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/transcribe":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 512 * 1024 * 1024:
            self._json(400, {"error": "bad body size"})
            return
        data = self.rfile.read(length)
        path = None
        raw = None
        try:
            with tempfile.NamedTemporaryFile(suffix=".audio", delete=False) as f:
                f.write(data)
                path = f.name
            # Decode with ffmpeg, not PyAV: the venv's PyAV rejects
            # faster-whisper's metadata_errors kwarg (found live
            # Oct 31, 2026). ffmpeg -> 16 kHz mono float32 -> numpy.
            raw = path + ".f32"
            subprocess.run(
                ["ffmpeg", "-v", "error", "-y", "-i", path,
                 "-ar", "16000", "-ac", "1", "-f", "f32le", raw],
                check=True, capture_output=True, timeout=600)
            samples = np.fromfile(raw, dtype=np.float32)
            if samples.size == 0:
                self._json(400, {"error": "no audio decoded"})
                return
            with lock:
                segments, info = model.transcribe(samples, beam_size=1, vad_filter=True)
                text = "".join(seg.text for seg in segments).strip()
            self._json(200, {
                "text": text,
                "language": getattr(info, "language", "en"),
                "duration": round(float(samples.size) / 16000.0, 2),
            })
        except Exception as e:
            self._json(500, {"error": str(e)})
        finally:
            for p in (path, raw):
                if p:
                    try:
                        os.unlink(p)
                    except OSError:
                        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
