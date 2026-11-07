// A set of open SSE responses: the web UI's /api/events and the phone's
// /api/agents/stream. One ping timer for all of them, and a cap on what may
// wait unsent for one client: a phone on a bad link must not make the
// server hold frames in memory without end. A client over the cap is cut
// off; it reconnects and gets a fresh snapshot, so it loses nothing.

export const SSE_HEADERS = { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' };

export class Hub {
  constructor({ pingMs = 25000, maxBuffered = 1024 * 1024 } = {}) {
    this.clients = new Set();
    this.maxBuffered = maxBuffered;
    this.dropped = 0;
    this.timer = setInterval(() => this.send(': ping\n\n'), pingMs);
    this.timer.unref?.();
  }

  get size() { return this.clients.size; }

  // Start the stream on res with its first frame, and keep it until the
  // request closes.
  open(req, res, first = '') {
    res.writeHead(200, SSE_HEADERS);
    if (first) res.write(first);
    this.clients.add(res);
    req.on('close', () => this.clients.delete(res));
  }

  send(frame) {
    for (const res of this.clients) this.write(res, frame);
  }

  write(res, frame) {
    if (res.destroyed || res.writableEnded) { this.clients.delete(res); return; }
    // writableLength: bytes the socket has not taken yet.
    if ((res.writableLength || 0) > this.maxBuffered) {
      this.clients.delete(res);
      this.dropped++;
      res.destroy();
      return;
    }
    res.write(frame);
  }

  // End every stream (shutdown): clients see a clean end and reconnect.
  close() {
    clearInterval(this.timer);
    for (const res of this.clients) res.end();
    this.clients.clear();
  }
}
