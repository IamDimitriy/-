// Same synthetic scenarios as test_detector.py, for the browser detector.
// Run: node --test tests/detector.test.js
const test = require("node:test");
const assert = require("node:assert");
const { Detector, encodeWav } = require("../web/detector.js");

const SR = 48000;
let seed = 1;
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
const gauss = () => Math.sqrt(-2 * Math.log(rand() || 1e-9)) * Math.cos(2 * Math.PI * rand());

const noise = (s, level = 0.002) => Float32Array.from({ length: SR * s }, () => gauss() * level);
function speech(s, level = 0.1) {
  const out = new Float32Array(SR * s);
  let peak = 0;
  for (let i = 0; i < out.length; i++) {
    const t = i / SR;
    let v = 0;
    for (let k = 2; k < 20; k++) v += Math.sin(2 * Math.PI * 150 * k * t) / (1 + Math.abs(150 * k - 800) / 400);
    out[i] = v * 0.5 * (1 + Math.sin(2 * Math.PI * 4 * t));
    peak = Math.max(peak, Math.abs(v));
  }
  return out.map((v) => (v / peak) * level);
}
const snore = (s, level = 0.2) => Float32Array.from({ length: SR * s }, (_, i) =>
  level * Math.sin(2 * Math.PI * 70 * i / SR) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 30 * i / SR)));
const add = (a, b) => a.map((v, i) => v + b[i]);
const cat = (...parts) => {
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
};
function run(audio) {
  const det = new Detector(SR);
  const events = [];
  for (let i = 0; i < audio.length; i += 4096) events.push(...det.process(audio.subarray(i, i + 4096)));
  return events.concat(det.flush());
}

test("quiet night has no events", () => assert.strictEqual(run(noise(60)).length, 0));

test("speech is captured with pre-roll", () => {
  const ev = run(cat(noise(20), add(noise(3), speech(3)), noise(20)));
  assert.strictEqual(ev.length, 1);
  assert.ok(ev[0].startS >= 18 && ev[0].startS <= 20.5, `start ${ev[0].startS}`);
  assert.ok(ev[0].durationS >= 4 && ev[0].durationS <= 9, `duration ${ev[0].durationS}`);
});

test("two phrases are two events", () => {
  const p = () => add(noise(2), speech(2));
  assert.strictEqual(run(cat(noise(10), p(), noise(15), p(), noise(10))).length, 2);
});

test("snoring and clicks are ignored", () => {
  const click = new Float32Array(SR * 2);
  click.fill(0.8, 300, 420);
  assert.strictEqual(run(cat(noise(10), add(noise(5), snore(5)), noise(5), add(noise(2), click), noise(10))).length, 0);
});

test("wav is 16 kHz 16-bit mono", () => {
  const buf = encodeWav(new Float32Array(SR), SR);
  const v = new DataView(buf);
  assert.strictEqual(v.getUint32(24, true), 16000);
  assert.strictEqual(buf.byteLength, 44 + 16000 * 2);
});
