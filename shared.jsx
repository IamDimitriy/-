/* global React */
const { useState, useEffect, useMemo } = React;

// ======================== DATA ========================
const BREEDS = [
  {
    rank: 1, id: 'british-fold', name: 'Британская вислоухая', en: 'British Fold',
    origin: 'Великобритания', weight: '4–8 кг', life: '14–18 лет', rating: 9.8,
    traits: ['Плюшевая', 'Ленивая', 'Королевская'],
    tag: '№1 в топе',
    short: 'Басик и его родственники захватили трон по праву — самая плюшевая морда в кошачьем царстве.',
    vibe: 'БУМ'
  },
  {
    rank: 2, id: 'maine-coon', name: 'Мейн-кун', en: 'Maine Coon',
    origin: 'США', weight: '6–12 кг', life: '12–15 лет', rating: 9.5,
    traits: ['Гигант', 'Ласковый', 'Болтливый'],
    short: 'Огромный пушистый товарищ размером с маленького рыся. Говорит трелью.',
    vibe: 'БОЛЬШОЙ'
  },
  {
    rank: 3, id: 'ragdoll', name: 'Рэгдолл', en: 'Ragdoll',
    origin: 'США', weight: '4–9 кг', life: '13–18 лет', rating: 9.3,
    traits: ['Тряпочка', 'Спокойный', 'Голубоглазый'],
    short: 'Берёшь на руки — обмякает как тряпочка. Буквально.',
    vibe: 'МЯК'
  },
  {
    rank: 4, id: 'sphynx', name: 'Сфинкс', en: 'Sphynx',
    origin: 'Канада', weight: '3–5 кг', life: '12–15 лет', rating: 9.0,
    traits: ['Лысый', 'Тёплый', 'Экстраверт'],
    short: 'Ходячая грелка без шерсти. Нуждается в свитерах и обнимашках.',
    vibe: 'ГОЛЫШ'
  },
  {
    rank: 5, id: 'bengal', name: 'Бенгальская', en: 'Bengal',
    origin: 'США', weight: '4–7 кг', life: '12–16 лет', rating: 8.9,
    traits: ['Леопардовый', 'Дикий', 'Спортсмен'],
    short: 'Мини-леопард в квартире. Лезет на шкаф, когда вы работаете.',
    vibe: 'ДИКИЙ'
  },
  {
    rank: 6, id: 'persian', name: 'Персидская', en: 'Persian',
    origin: 'Иран', weight: '3–7 кг', life: '12–17 лет', rating: 8.6,
    traits: ['Пуховое облако', 'Гордая', 'Расчёски любит'],
    short: 'Кот из рекламы 80-х. Требует ежедневного груминга и уважения.',
    vibe: 'ПУФФ'
  },
  {
    rank: 7, id: 'siamese', name: 'Сиамская', en: 'Siamese',
    origin: 'Таиланд', weight: '3–5 кг', life: '15–20 лет', rating: 8.4,
    traits: ['Говорливая', 'Стройная', 'Синеглазая'],
    short: 'Если вам нужен кот, который будет комментировать каждый ваш шаг.',
    vibe: 'ГОВОРИТ'
  },
  {
    rank: 8, id: 'russian-blue', name: 'Русская голубая', en: 'Russian Blue',
    origin: 'Россия', weight: '3–5 кг', life: '15–20 лет', rating: 8.3,
    traits: ['Серебристая', 'Интроверт', 'Элегантная'],
    short: 'Шерсть мерцает как дымка. Общается по предварительной записи.',
    vibe: 'ШЁЛК'
  },
  {
    rank: 9, id: 'abyssinian', name: 'Абиссинская', en: 'Abyssinian',
    origin: 'Эфиопия', weight: '3–5 кг', life: '12–15 лет', rating: 8.1,
    traits: ['Рыжая', 'Любопытная', 'Энергичная'],
    short: 'Вечно куда-то лезет. Закрывайте шкафы.',
    vibe: 'ЛЕЗЕТ'
  },
  {
    rank: 10, id: 'savannah', name: 'Саванна', en: 'Savannah',
    origin: 'США', weight: '5–12 кг', life: '12–20 лет', rating: 8.0,
    traits: ['Длинноногая', 'Охотник', 'Прыгун'],
    short: 'Полусервал с родословной. Прыжок до 2.5 метров.',
    vibe: 'ХОП'
  },
];

// ======================== NAV ========================
function Nav({ page, setPage }) {
  const pages = [
    { id: 'home', label: 'Главная' },
    { id: 'basik', label: 'Басик №1' },
    { id: 'ranking', label: 'Рейтинг' },
    { id: 'care', label: 'Уход' },
    { id: 'gallery', label: 'Галерея' },
  ];
  return (
    <nav className="nav">
      <div className="nav-inner">
        <a className="logo" onClick={() => setPage('home')} href="#">
          <span className="logo-dot"></span>
          БАСИК<span style={{ fontStyle: 'italic', fontWeight: 600, opacity: 0.5 }}>.mag</span>
        </a>
        <ul className="nav-links">
          {pages.map(p => (
            <li key={p.id}>
              <a className={page === p.id ? 'active' : ''} onClick={(e) => { e.preventDefault(); setPage(p.id); window.scrollTo({top: 0, behavior: 'instant'}); }} href="#">
                {p.label}
              </a>
            </li>
          ))}
        </ul>
        <div className="nav-meta">ВЫПУСК №24 · АПРЕЛЬ 2026</div>
      </div>
    </nav>
  );
}

// ======================== MARQUEE ========================
function Marquee() {
  const text = [
    'ТОП‑10 ПОРОД', '★', 'БАСИК ДЕРЖИТ ТРОН', '★', 'ПЛЮШЕВЫЙ ВЫПУСК', '★',
    'ЧИТАЙ ДО КОНЦА', '★', 'МУРРРРР', '★', 'КОРМИТЕ КОТОВ', '★',
  ];
  const line = (
    <span>
      {text.map((t, i) => <em key={i} style={{ color: t === '★' ? 'var(--amber-glow)' : 'var(--cream)' }}>{t}</em>)}
    </span>
  );
  return (
    <div className="marquee">
      <div className="marquee-track">
        {line}{line}{line}{line}
      </div>
    </div>
  );
}

// ======================== FOOTER ========================
function Footer({ setPage }) {
  return (
    <footer className="foot">
      <div className="wrap">
        <div>
          <h4>Мурррр до встречи<br/>в следующем номере.</h4>
          <p style={{ opacity: 0.7, maxWidth: 420, fontSize: 14, marginTop: 16 }}>
            Независимый журнал о котах, котиках и их повадках. Делается с любовью и чашкой корма рядом.
          </p>
          <div style={{ display: 'flex', gap: 10, marginTop: 24 }}>
            <span className="tag" style={{ background: 'var(--amber)', color: 'var(--ink)' }}>est. 2021</span>
            <span className="tag" style={{ background: 'transparent', border: '1.5px solid var(--cream)', color: 'var(--cream)' }}>Санкт‑Петербург</span>
          </div>
        </div>
        <div>
          <div className="mono-tag" style={{ opacity: 0.6, marginBottom: 12 }}>Разделы</div>
          <a onClick={(e)=>{e.preventDefault();setPage('home');}} href="#">Главная</a>
          <a onClick={(e)=>{e.preventDefault();setPage('basik');}} href="#">Профиль Басика</a>
          <a onClick={(e)=>{e.preventDefault();setPage('ranking');}} href="#">Полный рейтинг</a>
          <a onClick={(e)=>{e.preventDefault();setPage('care');}} href="#">Уход</a>
          <a onClick={(e)=>{e.preventDefault();setPage('gallery');}} href="#">Галерея</a>
        </div>
        <div>
          <div className="mono-tag" style={{ opacity: 0.6, marginBottom: 12 }}>Связаться</div>
          <a href="#">hello@basik.mag</a>
          <a href="#">Telegram @basikmag</a>
          <a href="#">Подписаться</a>
          <a href="#">Прислать кота</a>
        </div>
      </div>
      <div className="foot-bottom">
        <div>© 2026 Басик Magazine · все права принадлежат котам</div>
        <div>Сделано на подоконнике</div>
      </div>
    </footer>
  );
}

// Export to window for cross-script access
Object.assign(window, { BREEDS, Nav, Marquee, Footer });
