// Collects microphone samples into ~0.1 s blocks and hands them to the page.
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = Math.round(sampleRate / 10);
    this.buf = new Float32Array(this.size);
    this.fill = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buf[this.fill++] = ch[i];
        if (this.fill === this.size) {
          this.port.postMessage(this.buf, [this.buf.buffer]);
          this.buf = new Float32Array(this.size);
          this.fill = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor("capture", CaptureProcessor);
