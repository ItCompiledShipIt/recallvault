#!/usr/bin/env python3
"""Transfer recallvault.apk to bigpc privately over SSH (stdin pipe).
Verified Oct 31, 2026: argv/base64 chunking breaks on printf quoting
through fish; piping base64 as stdin to `base64 -d > file` is clean.
SHA-256 is printed on both ends - they must match."""
import base64, subprocess, os, hashlib, sys
path = os.path.expanduser("~/workspace/recallvault/recallvault.apk")
data = open(path, "rb").read()
print("local sha256", hashlib.sha256(data).hexdigest(), len(data), "bytes")
b64 = base64.b64encode(data).decode()
SSH = ["ssh", "-o", "ProxyCommand=python3 " + os.path.expanduser("~/workspace/bin/ssh-tunnel-proxy.py") + " %h %p",
       "-i", os.path.expanduser("~/.ssh/id_ed25519"), "-o", "BatchMode=yes",
       "-o", "UserKnownHostsFile=/home/hatch/.ssh/known_hosts",
       "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15",
       "chris@100.88.174.124"]
r = subprocess.run(SSH + ["base64 -d > /home/chris/Projects/recallvault.apk"],
                   input=b64, capture_output=True, text=True, timeout=60)
if r.returncode != 0:
    print("decode failed", r.stderr[:300]); sys.exit(1)
r = subprocess.run(SSH + ["bash -c \"sha256sum /home/chris/Projects/recallvault.apk\""],
                   capture_output=True, text=True, timeout=40)
print("bigpc", r.stdout.strip())
