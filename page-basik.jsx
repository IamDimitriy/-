/* global React */
function BasikPage({ setPage }) {
  const facts = [
    { k: 'Порода', v: 'Британская вислоухая' },
    { k: 'Возраст', v: '3 года' },
    { k: 'Вес', v: '5.4 кг' },
    { k: 'Окрас', v: 'Серый (blue)' },
    { k: 'Глаза', v: 'Янтарные' },
    { k: 'Любимая еда', v: 'Курица + сметана' },
    { k: 'Настроение', v: 'Варьируется' },
    { k: 'Трон', v: 'Занят' },
  ];

  const timeline = [
    { time: '07:00', act: 'Будит хозяина. Не по будильнику. Просто.' },
    { time: '07:15', act: 'Требует завтрак. Орёт до получения.' },
    { time: '08:00', act: 'Первый сон дня. На клавиатуре.' },
    { time: '12:00', act: 'Обеденный обход квартиры. Инспекция.' },
    { time: '14:00', act: 'Танцы на ковре (см. фото №1).' },
    { time: '18:00', act: 'Ужин. Снова ор.' },
    { time: '22:00', act: 'Зумеры. Носится по коридору.' },
    { time: '02:00', act: 'Приходит спать на лицо.' },
  ];

  return (
    <div className="page-enter">
      {/* Big header */}
      <section className="wrap" style={{ paddingTop: 28 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 18 }}>
          <div className="mono-tag">Персона · №01 в рейтинге</div>
          <a onClick={(e) => { e.preventDefault(); setPage('home'); }} href="#" style={{ color: 'var(--ink)', fontSize: 14, textDecoration: 'none' }}>← назад к топу</a>
        </div>
        <div className="rule-thick" style={{ marginBottom: 32 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 56, alignItems: 'end' }}>
          <div>
            <div className="sticker" style={{ marginBottom: 24 }}>плюшевая легенда</div>
            <h1 style={{ fontSize: 'clamp(80px, 11vw, 170px)', lineHeight: 0.85, letterSpacing: '-0.045em' }}>
              Знакомь<span style={{ color: 'var(--amber-deep)' }}>-</span><br/>
              тесь<span style={{ fontStyle: 'italic', fontWeight: 600 }}>,</span><br/>
              Басик.
            </h1>
          </div>
          <div style={{ paddingBottom: 20 }}>
            <p style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', fontWeight: 600, fontSize: 28, lineHeight: 1.25 }}>
              Серый джентельмен с клыками. Британец в первом поколении. Официально признан редакцией лучшим котом выпуска.
            </p>
            <div style={{ display: 'flex', gap: 10, marginTop: 24, flexWrap: 'wrap' }}>
              <span className="tag tag-amber">Чемпион</span>
              <span className="tag">Крик-звезда</span>
              <span className="tag tag-outline">Диванный житель</span>
              <span className="tag tag-outline">Янтарные глаза</span>
            </div>
          </div>
        </div>
      </section>

      {/* Two-photo collage */}
      <section className="wrap" style={{ paddingTop: 48 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 24 }}>
          <div style={{
            position: 'relative',
            aspectRatio: '4/5',
            borderRadius: 'var(--r-lg)',
            overflow: 'hidden',
            border: '2px solid var(--ink)',
            background: 'var(--ink)',
            boxShadow: 'var(--shadow-lg)',
          }}>
            <img src="assets/basik-hero.jpg" alt="Басик орёт" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            <div style={{ position: 'absolute', bottom: 20, left: 20, right: 20, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
              <div style={{ background: 'var(--amber)', padding: '8px 16px', borderRadius: 999, fontFamily: 'var(--sans)', fontWeight: 700, fontSize: 12, letterSpacing: '0.14em' }}>
                ФОТО #01 · «АТАКА»
              </div>
              <div className="hand" style={{ background: 'var(--cream)', padding: '6px 14px', borderRadius: 999, fontSize: 20, transform: 'rotate(-4deg)', border: '1.5px solid var(--ink)' }}>
                клыки!!
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
            <div style={{
              flex: 1,
              position: 'relative',
              borderRadius: 'var(--r-lg)',
              overflow: 'hidden',
              border: '2px solid var(--ink)',
              background: 'var(--ink)',
              minHeight: 260,
            }}>
              <img src="assets/basik-stand.jpg" alt="Басик стоит" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              <div style={{ position: 'absolute', bottom: 20, left: 20, background: 'var(--cream)', padding: '8px 16px', borderRadius: 999, fontSize: 12, fontWeight: 700, letterSpacing: '0.14em' }}>
                ФОТО #02 · «СТОЙКА СУРИКАТА»
              </div>
            </div>
            <div style={{
              background: 'var(--amber)',
              borderRadius: 'var(--r-lg)',
              padding: 28,
              border: '2px solid var(--ink)',
              flex: 0.7,
              display: 'flex', flexDirection: 'column', justifyContent: 'center',
            }}>
              <div className="mono-tag">Вердикт редакции</div>
              <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 54, lineHeight: 1, marginTop: 8 }}>9.8<span style={{ opacity: 0.4 }}>/10</span></div>
              <p style={{ marginTop: 10, fontSize: 14 }}>Снято 0.2 балла за попытку укусить фотографа. Но мы всё ещё влюблены.</p>
            </div>
          </div>
        </div>
      </section>

      {/* Fact sheet */}
      <section className="wrap" style={{ paddingTop: 80 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 48 }}>
          <div>
            <div className="mono-tag" style={{ opacity: 0.6 }}>Досье</div>
            <h2 style={{ fontSize: 56, marginTop: 6 }}>Карточка <span className="serif-italic">героя</span></h2>
            <p style={{ marginTop: 16, color: 'var(--ink-soft)', fontSize: 16 }}>
              Всё, что нужно знать о Басике, прежде чем он решит прийти к вам спать на лицо.
            </p>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 0, border: '1.5px solid var(--ink)', borderRadius: 'var(--r-md)', overflow: 'hidden' }}>
            {facts.map((f, i) => (
              <div key={f.k} style={{
                padding: '20px 24px',
                borderBottom: i < facts.length - 2 ? '1px solid var(--ink)' : 'none',
                borderRight: i % 2 === 0 ? '1px solid var(--ink)' : 'none',
                background: i % 3 === 0 ? 'var(--paper)' : 'transparent',
              }}>
                <div className="mono-tag" style={{ opacity: 0.55, fontSize: 10 }}>{f.k}</div>
                <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22, marginTop: 4 }}>{f.v}</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Daily timeline */}
      <section className="wrap" style={{ paddingTop: 100 }}>
        <h2 style={{ fontSize: 56 }}>День из жизни <span className="serif-italic" style={{ color: 'var(--amber-deep)' }}>Басика</span></h2>
        <div className="rule" style={{ marginTop: 14, marginBottom: 32 }}></div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 48 }}>
          <div>
            {timeline.map((t, i) => (
              <div key={t.time} style={{ display: 'grid', gridTemplateColumns: '80px 1fr', gap: 24, padding: '18px 0', borderTop: i === 0 ? 'none' : '1px dashed var(--ink)' }}>
                <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22, color: 'var(--amber-deep)' }}>{t.time}</div>
                <div style={{ fontSize: 16 }}>{t.act}</div>
              </div>
            ))}
          </div>
          <div style={{
            background: 'var(--cream-2)',
            border: '1.5px solid var(--ink)',
            borderRadius: 'var(--r-lg)',
            padding: 32,
            position: 'sticky',
            top: 100,
            height: 'fit-content',
          }}>
            <div className="mono-tag">Личный рекорд</div>
            <div style={{ fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 100, lineHeight: 1, marginTop: 12, letterSpacing: '-0.04em' }}>
              17ч
            </div>
            <div style={{ fontSize: 14, marginTop: 4 }}>подряд сна на подоконнике (без еды, без вопросов)</div>
            <div className="rule" style={{ margin: '28px 0' }}></div>
            <div className="mono-tag">Второй рекорд</div>
            <div style={{ fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 100, lineHeight: 1, marginTop: 12, letterSpacing: '-0.04em' }}>
              42<span style={{ fontSize: 40 }}>дБ</span>
            </div>
            <div style={{ fontSize: 14, marginTop: 4 }}>громкость мурчания в режиме «гладь меня ещё»</div>
          </div>
        </div>
      </section>

      {/* CTA to gallery */}
      <section className="wrap" style={{ paddingTop: 100 }}>
        <div onClick={() => setPage('gallery')} style={{
          background: 'var(--ink)',
          color: 'var(--cream)',
          borderRadius: 'var(--r-lg)',
          padding: 56,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          cursor: 'pointer',
          transition: 'transform 0.25s',
        }}
        onMouseEnter={(e) => e.currentTarget.style.transform = 'translateY(-4px)'}
        onMouseLeave={(e) => e.currentTarget.style.transform = 'translateY(0)'}
        >
          <div>
            <div className="mono-tag" style={{ color: 'var(--amber-glow)' }}>Дальше</div>
            <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 64, lineHeight: 1, marginTop: 8 }}>
              Больше Басика <span style={{ fontStyle: 'italic', fontWeight: 600 }}>→ галерея</span>
            </div>
          </div>
          <div style={{ fontSize: 72 }}>→</div>
        </div>
      </section>
    </div>
  );
}

Object.assign(window, { BasikPage });
