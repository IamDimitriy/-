// Streaming speech-episode detector, a port of sleeptalk/detector.py.
// Works on mono Float32Array audio at any sample rate. Instead of an FFT it
// measures the speech-band share with two cheap biquad filter chains.
(function (root) {
  "use strict";

  const DEFAULTS = {
    frameMs: 30,
    thresholdDb: 10,
    speechBand: [300, 3400],
    minSpeechRatio: 0.45,
    startActive: 5,
    startWindow: 10,
    prerollS: 2,
    hangoverS: 2.5,
    minActiveS: 0.4,
    maxEventS: 120,
    // Room silence = 20th percentile of frame levels over 30 s: a snore cannot mask the next words.
    floorWindowS: 30,
    floorPercentile: 0.2,
    calibrationS: 3,
    minFloorDb: -70,
  };

  // RBJ cookbook biquad, transposed direct form II.
  function biquad(type, f0, sr, q = Math.SQRT1_2) {
    const w = (2 * Math.PI * Math.min(f0, sr * 0.45)) / sr;
    const cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
    let b0, b1, b2;
    if (type === "lowpass") { b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = b0; }
    else { b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = b0; }
    const a0 = 1 + alpha, a1 = -2 * cos, a2 = 1 - alpha;
    const c = [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
    let z1 = 0, z2 = 0;
    return (x) => {
      const y = c[0] * x + z1;
      z1 = c[1] * x - c[3] * y + z2;
      z2 = c[2] * x - c[4] * y;
      return y;
    };
  }

  class Detector {
    constructor(samplerate, config = {}) {
      this.sr = samplerate;
      this.cfg = Object.assign({}, DEFAULTS, config);
      const c = this.cfg;
      this.frameLen = Math.max(1, Math.round((samplerate * c.frameMs) / 1000));
      const fps = samplerate / this.frameLen;
      this.prerollMax = Math.max(1, Math.round(c.prerollS * fps));
      this.hangFrames = Math.max(1, Math.round(c.hangoverS * fps));
      this.minActive = Math.max(1, Math.round(c.minActiveS * fps));
      this.maxFrames = Math.max(1, Math.round(c.maxEventS * fps));
      this.historyMax = Math.max(1, Math.round(c.floorWindowS * fps));
      this.history = [];
      this.calibFrames = Math.max(1, Math.round(c.calibrationS * fps));
      this.fps = fps;

      this.hp60 = biquad("highpass", 60, samplerate);
      this.bandHp = biquad("highpass", c.speechBand[0], samplerate);
      this.bandHp2 = biquad("highpass", c.speechBand[0], samplerate);
      this.bandLp = biquad("lowpass", c.speechBand[1], samplerate);
      this.bandLp2 = biquad("lowpass", c.speechBand[1], samplerate);

      this.calib = [];
      this.floor = null;
      this.preroll = [];
      this.recent = [];
      this.cur = null;
      this.frameIdx = 0;
      this.buf = new Float32Array(this.frameLen);
      this.bufFill = 0;
      this.lastDb = -120;
    }

    get inEvent() { return this.cur !== null; }

    process(chunk) {
      const events = [];
      for (let i = 0; i < chunk.length; i++) {
        this.buf[this.bufFill++] = chunk[i];
        if (this.bufFill === this.frameLen) {
          const ev = this._step(this.buf);
          this.buf = new Float32Array(this.frameLen);
          this.bufFill = 0;
          if (ev) events.push(ev);
        }
      }
      return events;
    }

    flush() {
      if (!this.cur) return [];
      const ev = this._finish();
      return ev ? [ev] : [];
    }

    _measure(frame) {
      let sum = 0, all = 0, band = 0;
      for (let i = 0; i < frame.length; i++) {
        const x = frame[i];
        sum += x * x;
        const h = this.hp60(x);
        all += h * h;
        const b = this.bandLp2(this.bandLp(this.bandHp2(this.bandHp(x))));
        band += b * b;
      }
      const rms = Math.sqrt(sum / frame.length);
      return { db: 20 * Math.log10(Math.max(rms, 1e-10)), ratio: all > 0 ? band / all : 0 };
    }

    _step(frame) {
      const c = this.cfg;
      const idx = this.frameIdx++;
      const { db, ratio } = this._measure(frame);
      this.lastDb = db;

      if (this.floor === null) {
        this.calib.push(db);
        this._remember(db);
        this._pushPreroll(frame);
        if (this.calib.length >= this.calibFrames) {
          const s = this.calib.slice().sort((a, b) => a - b);
          this.floor = Math.max(s[Math.floor(s.length / 2)], c.minFloorDb);
        }
        return null;
      }

      const active = db >= this.floor + c.thresholdDb &&
        (c.minSpeechRatio <= 0 || ratio >= c.minSpeechRatio);
      this._remember(db);
      if (idx % 10 === 0) this._updateFloor();

      if (!this.cur) {
        this.recent.push(active);
        if (this.recent.length > c.startWindow) this.recent.shift();
        const count = this.recent.reduce((n, a) => n + (a ? 1 : 0), 0);
        if (count >= c.startActive) {
          this.cur = {
            startFrame: idx - this.preroll.length,
            frames: this.preroll.concat([frame]),
            active: count, silentRun: 0, peakDb: db,
          };
          this.preroll = [];
          this.recent = [];
        } else {
          this._pushPreroll(frame);
        }
        return null;
      }

      const cur = this.cur;
      cur.frames.push(frame);
      cur.peakDb = Math.max(cur.peakDb, db);
      if (active) { cur.active++; cur.silentRun = 0; } else { cur.silentRun++; }
      if (cur.silentRun >= this.hangFrames || cur.frames.length >= this.maxFrames) {
        return this._finish();
      }
      return null;
    }

    _remember(db) {
      this.history.push(db);
      if (this.history.length > this.historyMax) this.history.shift();
    }

    _updateFloor() {
      if (this.history.length < this.calibFrames) return;
      const s = this.history.slice().sort((a, b) => a - b);
      const p = s[Math.round((s.length - 1) * this.cfg.floorPercentile)];
      this.floor = Math.max(p, this.cfg.minFloorDb);
    }

    _pushPreroll(frame) {
      this.preroll.push(frame);
      if (this.preroll.length > this.prerollMax) this.preroll.shift();
    }

    _finish() {
      const cur = this.cur;
      this.cur = null;
      this.recent = [];
      this.preroll = cur.frames.slice(-this.prerollMax);
      if (cur.active < this.minActive) return null;
      const audio = new Float32Array(cur.frames.length * this.frameLen);
      cur.frames.forEach((f, i) => audio.set(f, i * this.frameLen));
      return {
        startS: (Math.max(0, cur.startFrame) * this.frameLen) / this.sr,
        samplerate: this.sr,
        audio,
        peakDb: cur.peakDb,
        activeS: cur.active / this.fps,
        floorDb: this.floor,
        get durationS() { return this.audio.length / this.samplerate; },
      };
    }
  }

  // Encode mono float audio as 16-bit WAV, downsampling to about 16 kHz.
  function encodeWav(audio, samplerate) {
    const factor = Math.max(1, Math.round(samplerate / 16000));
    const n = Math.floor(audio.length / factor);
    const rate = Math.round(samplerate / factor);
    const view = new DataView(new ArrayBuffer(44 + n * 2));
    const str = (o, s) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };
    str(0, "RIFF"); view.setUint32(4, 36 + n * 2, true); str(8, "WAVE");
    str(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, 1, true); view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true);
    view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    str(36, "data"); view.setUint32(40, n * 2, true);
    for (let i = 0; i < n; i++) {
      let s = 0;
      for (let k = 0; k < factor; k++) s += audio[i * factor + k];
      s = Math.max(-1, Math.min(1, s / factor));
      view.setInt16(44 + i * 2, Math.round(s * 32767), true);
    }
    return view.buffer;
  }

  const api = { Detector, encodeWav, DEFAULTS };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.SleepDetector = api;
})(typeof self !== "undefined" ? self : this);
