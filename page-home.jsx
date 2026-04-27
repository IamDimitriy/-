/* global React, BREEDS */
const { useState: useStateHome } = React;

function HomePage({ setPage }) {
  const [hovered, setHovered] = useStateHome(null);
  const top3 = BREEDS.slice(0, 3);
  const rest = BREEDS.slice(3);

  return (
    <div className="page-enter">
      {/* HERO */}
      <section className="hero wrap" style={{ paddingTop: 32, paddingBottom: 48 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 18 }}>
          <div className="mono-tag">Выпуск №24 · Плюшевый номер</div>
          <div className="mono-tag" style={{ opacity: 0.6 }}>10 пород · 1 чемпион · 0 вопросов</div>
        </div>
        <div className="rule-thick" style={{ marginBottom: 28 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1.15fr 1fr', gap: 48, alignItems: 'start' }}>
          <div>
            <div style={{ display: 'flex', gap: 10, marginBottom: 20 }}>
              <span className="tag tag-amber">ГЛАВНОЕ</span>
              <span className="tag tag-outline">топ-10 пород</span>
            </div>
            <h1 style={{ fontSize: 'clamp(72px, 10vw, 148px)', letterSpacing: '-0.04em', lineHeight: 0.88 }}>
              Самые<br/>
              <span style={{ fontStyle: 'italic', fontWeight: 600, color: 'var(--amber-deep)' }}>плюшевые</span><br/>
              коты<br/>
              <span style={{ position: 'relative', display: 'inline-block' }}>
                планеты
                <span className="sticker" style={{ position: 'absolute', top: -10, right: -140, fontSize: 20 }}>мяу&nbsp;2026</span>
              </span>
            </h1>
            <p style={{ marginTop: 28, fontSize: 19, maxWidth: 500, lineHeight: 1.5, color: 'var(--ink-soft)' }}>
              Мы отсмотрели <b>сотни часов видео</b> с котами (тяжёлая работа, кто-то должен был). Составили честный топ. На первом месте — <b>британская вислоухая</b> по имени Басик. Без обсуждений.
            </p>
            <div style={{ display: 'flex', gap: 14, marginTop: 36 }}>
              <button className="btn btn-amber" onClick={() => setPage('basik')}>
                Познакомиться с Басиком →
              </button>
              <button className="btn btn-ghost" onClick={() => setPage('ranking')}>
                Весь рейтинг
              </button>
            </div>
          </div>

          {/* Hero image card */}
          <div style={{ position: 'relative' }}>
            <div style={{
              position: 'relative',
              aspectRatio: '3/4',
              borderRadius: 'var(--r-lg)',
              overflow: 'hidden',
              background: 'var(--ink)',
              border: '2px solid var(--ink)',
              boxShadow: 'var(--shadow-lg)',
              transform: 'rotate(1.5deg)',
            }}>
              <img src="assets/basik-hero.jpg" alt="Басик"
                style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
              <div style={{
                position: 'absolute', top: 18, left: 18,
                background: 'var(--amber)', color: 'var(--ink)',
                padding: '6px 14px', borderRadius: 999,
                fontFamily: 'var(--sans)', fontWeight: 700, fontSize: 11, letterSpacing: '0.14em',
              }}>№1 · БАСИК</div>
              <div style={{
                position: 'absolute', bottom: 18, left: 18, right: 18,
                background: 'var(--cream)', padding: '14px 18px',
                borderRadius: 12,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              }}>
                <div>
                  <div className="mono-tag" style={{ opacity: 0.6, fontSize: 10 }}>Порода</div>
                  <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 20 }}>Брит. вислоухая</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div className="mono-tag" style={{ opacity: 0.6, fontSize: 10 }}>Рейтинг</div>
                  <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 20 }}>9.8<span style={{ opacity: 0.4 }}>/10</span></div>
                </div>
              </div>
            </div>
            <div className="hand" style={{
              position: 'absolute', bottom: -20, left: -40,
              fontSize: 28, color: 'var(--rust)',
              transform: 'rotate(-8deg)',
            }}>← чемпион, посмотри на эти клыки</div>
          </div>
        </div>
      </section>

      <Marquee />

      {/* TOP 3 podium */}
      <section className="wrap" style={{ paddingTop: 80 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 12 }}>
          <h2 style={{ fontSize: 56 }}>Пьедестал<span style={{ fontStyle: 'italic', fontWeight: 600, color: 'var(--amber-deep)' }}>.</span></h2>
          <div className="mono-tag" style={{ opacity: 0.6 }}>стр. 02 из 10</div>
        </div>
        <div className="rule" style={{ marginBottom: 40 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 24 }}>
          {top3.map((b, i) => {
            const medals = ['🥇', '🥈', '🥉'];
            const heights = [460, 420, 400];
            const bgs = ['var(--amber)', 'var(--cream-2)', 'var(--cream-2)'];
            return (
              <div key={b.id} onClick={() => i === 0 && setPage('basik')}
                style={{
                  background: bgs[i],
                  border: '1.5px solid var(--ink)',
                  borderRadius: 'var(--r-lg)',
                  padding: 28,
                  minHeight: heights[i],
                  position: 'relative',
                  cursor: i === 0 ? 'pointer' : 'default',
                  transition: 'transform 0.25s',
                  display: 'flex', flexDirection: 'column',
                }}
                onMouseEnter={(e) => e.currentTarget.style.transform = 'translateY(-6px)'}
                onMouseLeave={(e) => e.currentTarget.style.transform = 'translateY(0)'}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div style={{ fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 80, lineHeight: 1 }}>
                    {i === 0 ? '01' : i === 1 ? '02' : '03'}
                  </div>
                  <div style={{ fontSize: 42 }}>{medals[i]}</div>
                </div>
                <h3 style={{ fontSize: 32, marginTop: 18 }}>{b.name}</h3>
                <div className="mono-tag" style={{ opacity: 0.6, marginTop: 6 }}>{b.en} · {b.origin}</div>
                <p style={{ marginTop: 16, fontSize: 15, flex: 1 }}>{b.short}</p>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 20, paddingTop: 16, borderTop: '1px dashed var(--ink)' }}>
                  <div className="stars">{'★'.repeat(Math.round(b.rating/2))}</div>
                  <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22 }}>{b.rating}</div>
                </div>
                {i === 0 && (
                  <div className="hand" style={{ position: 'absolute', top: -18, right: 20, background: 'var(--ink)', color: 'var(--amber-glow)', padding: '4px 14px', borderRadius: 999, fontSize: 18, transform: 'rotate(4deg)' }}>
                    ← жми!
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {/* 4-10 list */}
      <section className="wrap" style={{ paddingTop: 80 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 12 }}>
          <h2 style={{ fontSize: 56 }}>Догоняющие <span className="serif-italic" style={{ color: 'var(--ink-soft)' }}>места 4—10</span></h2>
          <div className="mono-tag" style={{ opacity: 0.6 }}>стр. 03 из 10</div>
        </div>
        <div className="rule" style={{ marginBottom: 8 }}></div>

        <div style={{ display: 'flex', flexDirection: 'column' }}>
          {rest.map((b) => (
            <div key={b.id}
              onMouseEnter={() => setHovered(b.id)}
              onMouseLeave={() => setHovered(null)}
              style={{
                display: 'grid',
                gridTemplateColumns: '80px 2fr 2fr 1.5fr 100px 80px',
                gap: 24,
                alignItems: 'center',
                padding: '28px 8px',
                borderBottom: '1px solid var(--ink)',
                cursor: 'pointer',
                background: hovered === b.id ? 'var(--paper)' : 'transparent',
                transition: 'background 0.2s',
              }}
              onClick={() => setPage('ranking')}
            >
              <div style={{ fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 64, lineHeight: 1 }}>
                {String(b.rank).padStart(2, '0')}
              </div>
              <div>
                <h3 style={{ fontSize: 28 }}>{b.name}</h3>
                <div className="mono-tag" style={{ opacity: 0.6, marginTop: 4 }}>{b.en}</div>
              </div>
              <div style={{ fontSize: 15, color: 'var(--ink-soft)', maxWidth: 360 }}>
                {b.short}
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {b.traits.slice(0, 2).map(t => (
                  <span key={t} style={{ fontSize: 12, padding: '3px 10px', border: '1px solid var(--ink)', borderRadius: 999 }}>{t}</span>
                ))}
              </div>
              <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 28, textAlign: 'right' }}>
                {b.rating}
              </div>
              <div style={{ textAlign: 'right', fontSize: 28, transition: 'transform 0.2s', transform: hovered === b.id ? 'translateX(8px)' : 'translateX(0)' }}>→</div>
            </div>
          ))}
        </div>
      </section>

      {/* Quote block */}
      <section className="wrap" style={{ paddingTop: 100 }}>
        <div style={{
          background: 'var(--ink)',
          color: 'var(--cream)',
          borderRadius: 'var(--r-lg)',
          padding: '80px 64px',
          position: 'relative',
          overflow: 'hidden',
        }}>
          <div style={{ fontSize: 220, fontFamily: 'var(--serif)', lineHeight: 0.8, position: 'absolute', top: 20, left: 40, opacity: 0.12, color: 'var(--amber)' }}>"</div>
          <div style={{ position: 'relative', maxWidth: 800 }}>
            <div className="mono-tag" style={{ color: 'var(--amber-glow)', marginBottom: 20 }}>Мнение главного редактора</div>
            <blockquote style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', fontWeight: 600, fontSize: 48, lineHeight: 1.15 }}>
              Британская вислоухая — это не порода. Это стиль жизни. Это плюшевая философия. Посмотрите на Басика и попробуйте с этим поспорить.
            </blockquote>
            <div style={{ marginTop: 28, display: 'flex', alignItems: 'center', gap: 14 }}>
              <div style={{ width: 48, height: 48, borderRadius: '50%', background: 'var(--amber)' }}></div>
              <div>
                <div style={{ fontWeight: 700 }}>Алиса Муркина</div>
                <div style={{ opacity: 0.6, fontSize: 13 }}>Шеф-редактор · котолог со стажем</div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}

Object.assign(window, { HomePage });
