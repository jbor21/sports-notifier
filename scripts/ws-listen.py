#!/usr/bin/env python3
"""Opens the gateway's WebSocket as a signed-in user and waits for messages containing some text.

Usage: ws-listen.py [--count N] <ws-url> <curl-cookie-jar> <text> <timeout-seconds> [message-to-send]

Prints "open" once the socket is connected, so a caller can wait for that before making something
happen, then "frame <text>" for every text frame it gets. If a message is given, it is sent once
"hello" arrives (e.g. a subscribe_games). Sends {"type":"ping"} every 25 s, as docs/api/websocket.md
asks: the server closes a socket that has sent nothing for 60 s. Exits 0 once N text frames (default
1) contain <text>, 1 on timeout or close; a close prints its code. Python standard library only, so
the smoke test needs nothing installed.
"""
import base64, os, socket, struct, sys, time
from urllib.parse import urlparse

args = sys.argv[1:]
count = 1
if args and args[0] == "--count":
    count, args = int(args[1]), args[2:]
url, jar, want, timeout = args[0], args[1], args[2], float(args[3])
to_send = args[4] if len(args) > 4 else None
PING_EVERY = 25.0

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
cookie_header = f"Cookie: {'; '.join(cookies)}\r\n" if cookies else ""
sock.sendall((f"GET {u.path or '/'} HTTP/1.1\r\nHost: {u.hostname}:{u.port or 80}\r\n"
              f"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
              f"Sec-WebSocket-Version: 13\r\n{cookie_header}\r\n").encode())
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
    n = len(payload)
    if n < 126:
        header = bytes([0x80 | opcode, 0x80 | n])
    elif n < 65536:
        header = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack(">H", n)
    else:
        header = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack(">Q", n)
    sock.sendall(header + mask + masked)

deadline = time.time() + timeout
next_ping = time.time() + PING_EVERY
matched = 0
try:
    while time.time() < deadline:
        now = time.time()
        if now >= next_ping:
            send_frame(0x1, b'{"type":"ping"}')
            next_ping = now + PING_EVERY
        sock.settimeout(max(0.1, min(deadline, next_ping) - now))
        try:
            b1, b2 = read_exact(2)
        except socket.timeout:
            continue
        opcode, length = b1 & 0x0F, b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", read_exact(8))[0]
        payload = read_exact(length)
        if opcode == 0x1:
            text = payload.decode("utf-8", "replace")
            print("frame " + text, flush=True)
            if to_send is not None and '"type":"hello"' in text:
                send_frame(0x1, to_send.encode())
                to_send = None
            if want in text:
                matched += 1
                if matched >= count:
                    print("received", flush=True)
                    sys.exit(0)
        if opcode == 0x9:
            send_frame(0xA, payload)
        if opcode == 0x8:
            code = struct.unpack(">H", payload[:2])[0] if len(payload) >= 2 else None
            sys.exit(f"ws-listen: server closed the socket (code {code})")
except EOFError:
    sys.exit("ws-listen: connection closed")
sys.exit(f"ws-listen: {matched} of {count} messages containing {want!r} within {timeout:.0f} s")
