#!/usr/bin/env python3
"""Opens the gateway's WebSocket as a signed-in user and waits for a message containing some text.

Usage: ws-listen.py <ws-url> <curl-cookie-jar> <text> <timeout-seconds>

Prints "open" once the socket is connected, so a caller can wait for that before making something
happen. Exits 0 when a text frame contains <text>, 1 on timeout or close. Python standard library
only, so the smoke test needs nothing installed.
"""
import base64, os, socket, struct, sys, time
from urllib.parse import urlparse

url, jar, want, timeout = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])

# curl's cookie jar: tab-separated; HttpOnly cookies are written with a "#HttpOnly_" prefix.
cookies = []
for line in open(jar):
    line = line.rstrip("\n")
    if line.startswith("#HttpOnly_"):
        line = line[len("#HttpOnly_"):]
    elif line.startswith("#") or not line.strip():
        continue
    parts = line.split("\t")
    if len(parts) == 7:
        cookies.append(f"{parts[5]}={parts[6]}")

u = urlparse(url)
sock = socket.create_connection((u.hostname, u.port or 80), timeout=10)
key = base64.b64encode(os.urandom(16)).decode()
sock.sendall((f"GET {u.path or '/'} HTTP/1.1\r\nHost: {u.hostname}:{u.port or 80}\r\n"
              f"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
              f"Sec-WebSocket-Version: 13\r\nCookie: {'; '.join(cookies)}\r\n\r\n").encode())
buf = b""
while b"\r\n\r\n" not in buf:
    chunk = sock.recv(4096)
    if not chunk:
        sys.exit("ws-listen: connection closed during handshake")
    buf += chunk
head, buf = buf.split(b"\r\n\r\n", 1)
if b" 101 " not in head.split(b"\r\n")[0]:
    sys.exit("ws-listen: handshake refused: " + head.split(b"\r\n")[0].decode())
print("open", flush=True)

def read_exact(n):
    global buf
    while len(buf) < n:
        chunk = sock.recv(4096)
        if not chunk:
            raise EOFError
        buf += chunk
    out, buf = buf[:n], buf[n:]
    return out

def send_frame(opcode, payload=b""):
    mask = os.urandom(4)   # client frames must be masked
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + masked)

deadline = time.time() + timeout
try:
    while time.time() < deadline:
        sock.settimeout(max(0.1, deadline - time.time()))
        try:
            b1, b2 = read_exact(2)
        except socket.timeout:
            break
        opcode, length = b1 & 0x0F, b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", read_exact(8))[0]
        payload = read_exact(length)
        if opcode == 0x1 and want in payload.decode("utf-8", "replace"):
            print("received", flush=True)
            sys.exit(0)
        if opcode == 0x9:
            send_frame(0xA, payload)
        if opcode == 0x8:
            code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else None
            sys.exit(f"ws-listen: server closed the socket (code {code})")
except EOFError:
    sys.exit("ws-listen: connection closed")
sys.exit(f"ws-listen: no message containing {want!r} within {timeout:.0f} s")
