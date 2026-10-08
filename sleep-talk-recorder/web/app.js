"use strict";

const $ = (id) => document.getElementById(id);
const SETTINGS_KEY = "sonnik.settings";

// ---------- storage (IndexedDB) ----------

const db = (() => {
  let ready;
  function open() {
    ready ??= new Promise((resolve, reject) => {
      const req = indexedDB.open("sonnik", 1);
      req.onupgradeneeded = () => {
        const d = req.result;
        d.createObjectStore("nights", { keyPath: "id" });
        d.createObjectStore("clips", { keyPath: "id", autoIncrement: true }).createIndex("night", "nightId");
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return ready;
  }
  async function tx(stores, mode, fn) {
    const d = await open();
    return new Promise((resolve, reject) => {
      const t = d.transaction(stores, mode);
      let result;
      Promise.resolve(fn(t)).then((r) => { result = r; });
      t.oncomplete = () => resolve(result);
      t.onerror = () => reject(t.error);
    });
  }
  const req2p = (r) => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  return {
    putNight: (n) => tx(["nights"], "readwrite", (t) => { t.objectStore("nights").put(n); }),
    addClip: (c) => tx(["clips"], "readwrite", (t) => { t.objectStore("clips").add(c); }),
    nights: () => tx(["nights"], "readonly", (t) => req2p(t.objectStore("nights").getAll())),
    clips: (nightId) => tx(["clips"], "readonly", (t) => req2p(t.objectStore("clips").index("night").getAll(nightId))),
    deleteClip: (id) => tx(["clips"], "readwrite", (t) => { t.objectStore("clips").delete(id); }),
    deleteNight: (id) => tx(["nights", "clips"], "readwrite", async (t) => {
      t.objectStore("nights").delete(id);
      const keys = await req2p(t.objectStore("clips").index("night").getAllKeys(id));
      keys.forEach((k) => t.objectStore("clips").delete(k));
    }),
  };
})();

// ---------- settings ----------

function loadSettings() {
  try { return Object.assign({ threshold: 10, anySound: false, stopAt: "07:30" }, JSON.parse(localStorage.getItem(SETTINGS_KEY))); }
  catch { return { threshold: 10, anySound: false, stopAt: "07:30" }; }
}
function saveSettings(s) { try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(s)); } catch {} }

const settings = loadSettings();
$("threshold").value = settings.threshold;
$("thresholdOut").textContent = `${settings.threshold} dB`;
$("anySound").checked = settings.anySound;
$("stopAt").value = settings.stopAt;
$("threshold").addEventListener("input", (e) => {
  settings.threshold = +e.target.value;
  $("thresholdOut").textContent = `${settings.threshold} dB`;
  saveSettings(settings);
});
$("anySound").addEventListener("change", (e) => { settings.anySound = e.target.checked; saveSettings(settings); });
$("stopAt").addEventListener("change", (e) => { settings.stopAt = e.target.value; saveSettings(settings); });

// ---------- formatting ----------

const pad = (n) => String(n).padStart(2, "0");
const hhmm = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;
const hhmmss = (d) => `${hhmm(d)}:${pad(d.getSeconds())}`;
function nightTitle(ts) {
  const d = new Date(ts);
  const next = new Date(ts + 12 * 3600e3);
  const fmt = (x) => x.toLocaleDateString("ru-RU", { day: "numeric", month: "long" });
  return d.getHours() >= 12 ? `Ночь на ${fmt(next)}` : `Ночь на ${fmt(d)}`;
}
function plural(n, one, few, many) {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

// ---------- recording session ----------

let session = null;

async function startNight() {
  $("error").hidden = true;
  $("start").disabled = true;
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error("no-media");
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
    });
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    await ctx.resume();
    const source = ctx.createMediaStreamSource(stream);
    const detector = new SleepDetector.Detector(ctx.sampleRate, {
      thresholdDb: settings.threshold,
      minSpeechRatio: settings.anySound ? 0 : 0.45,
    });
    const night = { id: Date.now(), startedAt: Date.now(), endedAt: null, clipCount: 0 };
    await db.putNight(night);

    session = { stream, ctx, source, detector, night, node: null, wakeLock: null, count: 0, startTime: null, stopAt: stopTime() };

    const onAudio = (chunk) => {
      if (session.startTime === null) session.startTime = Date.now() - (chunk.length / ctx.sampleRate) * 1000;
      for (const ev of detector.process(chunk)) saveEvent(ev);
      updateMeter();
    };

    if (ctx.audioWorklet) {
      await ctx.audioWorklet.addModule("capture-worklet.js");
      const node = new AudioWorkletNode(ctx, "capture");
      node.port.onmessage = (e) => onAudio(e.data);
      source.connect(node);
      session.node = node;
    } else {
      const node = ctx.createScriptProcessor(4096, 1, 1);
      node.onaudioprocess = (e) => onAudio(new Float32Array(e.inputBuffer.getChannelData(0)));
      source.connect(node);
      node.connect(ctx.destination);
      session.node = node;
    }

    stream.getAudioTracks()[0].addEventListener("ended", () => {
      $("status").textContent = "Микрофон отключился. Нажмите «Остановить» и начните заново.";
    });

    navigator.storage?.persist?.();
    showNight();
    await keepAwake();
  } catch (err) {
    $("start").disabled = false;
    stopEverything();
    $("error").hidden = false;
    $("error").textContent =
      err?.name === "NotAllowedError" ? "Нет доступа к микрофону. Разрешите его в настройках браузера для этого сайта и попробуйте снова." :
      err?.message === "no-media" ? "Этот браузер не даёт доступ к микрофону. Откройте страницу в Safari или Chrome по адресу https://." :
      `Не получилось запустить запись: ${err?.message || err}`;
  }
}

async function saveEvent(ev) {
  const at = session.startTime + ev.startS * 1000;
  const wav = new Blob([SleepDetector.encodeWav(ev.audio, ev.samplerate)], { type: "audio/wav" });
  session.count++;
  session.night.clipCount = session.count;
  $("count").textContent = `${session.count} ${plural(session.count, "фраза", "фразы", "фраз")}, последняя в ${hhmm(new Date(at))}`;
  try {
    await db.addClip({ nightId: session.night.id, at, duration: ev.durationS, peakDb: ev.peakDb, wav });
    await db.putNight(session.night);
  } catch (e) {
    $("status").textContent = "Не хватает места для записи. Освободите память на телефоне.";
  }
}

function stopTime() {
  if (!settings.stopAt) return null;
  const [h, m] = settings.stopAt.split(":").map(Number);
  const d = new Date();
  d.setHours(h, m, 0, 0);
  if (d <= new Date()) d.setDate(d.getDate() + 1);
  return d.getTime();
}

function stopEverything() {
  if (!session) return;
  try { session.node && (session.node.port ? session.node.port.onmessage = null : session.node.onaudioprocess = null); } catch {}
  try { session.source.disconnect(); } catch {}
  session.stream?.getTracks().forEach((t) => t.stop());
  session.ctx?.close().catch(() => {});
  session.wakeLock?.release().catch(() => {});
}

async function stopNight() {
  if (!session || session.stopping) return;
  const s = session;
  s.stopping = true;
  for (const ev of s.detector.flush()) await saveEvent(ev);
  stopEverything();
  s.night.endedAt = Date.now();
  s.night.clipCount = s.count;
  await db.putNight(s.night);
  session = null;
  showHome();
}

// ---------- keeping the screen on ----------

async function keepAwake() {
  if (!session) return;
  try {
    session.wakeLock = await navigator.wakeLock.request("screen");
    $("hint").textContent = "Экран не погаснет сам и скоро станет почти чёрным. Не блокируйте телефон.";
  } catch {
    $("hint").textContent = "Отключите автоблокировку экрана в настройках телефона на эту ночь: при блокировке запись остановится.";
  }
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && session) keepAwake();
});

// ---------- night screen ----------

let dimTimer, tickTimer;

function showNight() {
  $("home").hidden = true;
  $("night").hidden = false;
  document.body.classList.add("is-night");
  $("count").textContent = "Фраз пока нет";
  $("status").textContent = "Запоминаю тишину комнаты…";
  wake();
  tick();
  tickTimer = setInterval(tick, 1000);
}

function tick() {
  if (!session) return;
  $("clock").textContent = hhmm(new Date());
  const d = session.detector;
  if (d.floor !== null && !session.noted) {
    session.noted = true;
    $("status").textContent = "Слушаю";
  }
  if (session.stopAt && Date.now() >= session.stopAt) stopNight();
}

function updateMeter() {
  const d = session.detector;
  if (d.floor === null) return;
  const over = d.lastDb - d.floor;
  const pct = Math.max(0, Math.min(100, (over / (d.cfg.thresholdDb * 2)) * 100));
  $("meterBar").style.width = `${pct}%`;
}

function wake() {
  $("night").classList.remove("dim");
  clearTimeout(dimTimer);
  dimTimer = setTimeout(() => $("night").classList.add("dim"), 15000);
}
$("night").addEventListener("pointerdown", wake);

function showHome() {
  clearInterval(tickTimer);
  clearTimeout(dimTimer);
  document.body.classList.remove("is-night");
  $("night").hidden = true;
  $("home").hidden = false;
  $("start").disabled = false;
  renderNights();
}

// ---------- list of nights ----------

const objectUrls = [];

async function renderNights() {
  objectUrls.splice(0).forEach(URL.revokeObjectURL);
  const box = $("nights");
  let nights;
  try { nights = (await db.nights()).sort((a, b) => b.startedAt - a.startedAt); }
  catch { box.innerHTML = '<p class="empty">Браузер не даёт сохранять записи. Выйдите из приватного режима.</p>'; return; }
  if (!nights.length) {
    box.innerHTML = '<p class="empty">Здесь появятся записи. Каждая ночь — отдельный список фраз со временем.</p>';
    return;
  }
  box.replaceChildren();
  for (const [i, n] of nights.entries()) {
    const card = document.createElement("details");
    card.className = "nightcard";
    card.open = i === 0;
    const sum = document.createElement("summary");
    const span = n.endedAt ? `${hhmm(new Date(n.startedAt))}–${hhmm(new Date(n.endedAt))}` : `с ${hhmm(new Date(n.startedAt))}`;
    const c = n.clipCount || 0;
    sum.innerHTML = `<span class="date"></span><span class="sum"></span>`;
    sum.firstChild.textContent = nightTitle(n.startedAt);
    sum.lastChild.textContent = `${span} · ${c ? `${c} ${plural(c, "фраза", "фразы", "фраз")}` : "тихо"}`;
    card.append(sum);
    const body = document.createElement("div");
    card.append(body);
    const fill = async () => {
      if (body.dataset.filled) return;
      body.dataset.filled = "1";
      await renderClips(n, body);
    };
    card.addEventListener("toggle", () => card.open && fill());
    if (card.open) fill();
    box.append(card);
  }
}

async function renderClips(night, body) {
  const clips = (await db.clips(night.id)).sort((a, b) => a.at - b.at);
  const list = document.createElement("div");
  list.className = "clips";
  if (!clips.length) list.innerHTML = '<p class="empty">Ни одной фразы. Если вы точно говорили, попробуйте чувствительность пониже.</p>';
  for (const c of clips) {
    const row = document.createElement("div");
    row.className = "clip";
    const t = document.createElement("time");
    t.textContent = hhmm(new Date(c.at));
    t.title = hhmmss(new Date(c.at));
    const audio = document.createElement("audio");
    audio.controls = true;
    audio.preload = "none";
    const url = URL.createObjectURL(c.wav);
    objectUrls.push(url);
    audio.src = url;
    const actions = document.createElement("div");
    actions.className = "actions";
    actions.append(
      button("Сохранить", "link", () => shareClip(c)),
      confirmButton("Удалить", async () => { await db.deleteClip(c.id); night.clipCount = Math.max(0, (night.clipCount || 1) - 1); await db.putNight(night); row.remove(); }),
      Object.assign(document.createElement("span"), { textContent: `${c.duration.toFixed(0)} с` }),
    );
    row.append(t, audio, actions);
    list.append(row);
  }
  const nightActions = document.createElement("div");
  nightActions.className = "actions-night";
  nightActions.append(confirmButton("Удалить ночь", async () => { await db.deleteNight(night.id); renderNights(); }));
  body.append(list, nightActions);
}

function button(text, cls, onClick) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = cls;
  b.textContent = text;
  b.addEventListener("click", onClick);
  return b;
}

// Two-tap delete: the first tap asks, the second within 3 s deletes.
function confirmButton(text, onConfirm) {
  let armed = null;
  const b = button(text, "link danger", async () => {
    if (armed) { clearTimeout(armed); armed = null; await onConfirm(); return; }
    b.textContent = "Точно удалить?";
    armed = setTimeout(() => { armed = null; b.textContent = text; }, 3000);
  });
  return b;
}

async function shareClip(c) {
  const d = new Date(c.at);
  const name = `сон_${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}.wav`;
  const file = new File([c.wav], name, { type: "audio/wav" });
  if (navigator.canShare?.({ files: [file] })) {
    try { await navigator.share({ files: [file], title: name }); return; }
    catch (e) { if (e.name === "AbortError") return; }
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(file);
  a.download = name;
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

// ---------- boot ----------

$("start").addEventListener("click", startNight);
$("stop").addEventListener("click", stopNight);
window.addEventListener("pagehide", () => { if (session) { session.night.endedAt = Date.now(); db.putNight(session.night); } });
renderNights();

if ("serviceWorker" in navigator && location.protocol === "https:") {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
