/* global React */
const { useState: useStateGal } = React;

function GalleryPage({ setPage }) {
  const [lightbox, setLightbox] = useStateGal(null);

  const items = [
    { src: 'assets/basik-hero.jpg',  cap: 'КОГДА УВИДЕЛ КОРМ',         tilt: -2,   span: 'tall',   tag: '01' },
    { src: 'assets/basik-g2.jpg',    cap: 'Я ВАС ВИДЕЛ',               tilt: 1.5,  span: 'short',  tag: '02' },
    { src: 'assets/basik-stand.jpg', cap: 'СУРИКАТ МОД',               tilt: -1,   span: 'short',  tag: '03' },
    { src: 'assets/basik-g3.jpg',    cap: 'ЭТО МОЙ СТУЛ',              tilt: 2,    span: 'tall',   tag: '04' },
    { src: 'assets/basik-g1.jpg',    cap: 'ПОГЛАДЬ МЕНЯ',              tilt: -1.5, span: 'tall',   tag: '05' },
    { src: 'assets/basik-g5.jpg',    cap: 'В ПРОФИЛЬ Я КРАСИВЕЕ',      tilt: 1,    span: 'medium', tag: '06' },
    { src: 'assets/basik-g6.jpg',    cap: 'ДОН КОРЛЕОНЕ',              tilt: -1.2, span: 'medium', tag: '07' },
    { src: 'assets/basik-g10.jpg',   cap: 'ТЫ ЧЕГО СМЕЁШЬСЯ',          tilt: 2,    span: 'tall',   tag: '08' },
    { src: 'assets/basik-g7.jpg',    cap: 'ДАЙ ШАРИК',                 tilt: -2,   span: 'medium', tag: '09' },
    { src: 'assets/basik-g8.jpg',    cap: 'СЕЛФИ-ЛАПОЙ',               tilt: 1.5,  span: 'tall',   tag: '10' },
    { src: 'assets/basik-g9.jpg',    cap: 'ИЗ-ЗА УГЛА',                tilt: -1,   span: 'medium', tag: '11' },
    { src: 'assets/basik-g4.jpg',    cap: 'ОСТАВЬТЕ МЕНЯ В ПОКОЕ',     tilt: 2,    span: 'short',  tag: '12' },
  ];

  const memes = [
    'Когда вы сказали "нет" третий раз за день',
    'Режим: никого не пускать в комнату',
    '3 часа ночи. Я хочу танцевать.',
    'Это мой стул. Мой. Я сказал.',
  ];

  return (
    <div className="page-enter">
      <section className="wrap" style={{ paddingTop: 28 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 18 }}>
          <div className="mono-tag">Фотоархив · Басик & Со</div>
          <div className="mono-tag" style={{ opacity: 0.6 }}>{items.length} кадров в этом выпуске</div>
        </div>
        <div className="rule-thick" style={{ marginBottom: 32 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1.3fr 1fr', gap: 48, alignItems: 'end', marginBottom: 56 }}>
          <h1 style={{ fontSize: 'clamp(72px, 10vw, 140px)', lineHeight: 0.88 }}>
            Кото<span style={{ fontStyle: 'italic', fontWeight: 600, color: 'var(--amber-deep)' }}>-</span><br/>
            архив
          </h1>
          <p style={{ fontSize: 18, lineHeight: 1.5, color: 'var(--ink-soft)', paddingBottom: 16 }}>
            Мемная галерея. Басик снимается без гонорара, но с условием: курица после каждой сессии. Жмите на фото — увеличится.
          </p>
        </div>

        <div style={{ columnCount: 3, columnGap: 24 }}>
          {items.map((it, i) => {
            const heights = { short: 260, medium: 360, tall: 480 };
            return (
              <div key={i}
                onClick={() => setLightbox(it)}
                style={{
                  breakInside: 'avoid',
                  marginBottom: 24,
                  transform: `rotate(${it.tilt}deg)`,
                  cursor: 'zoom-in',
                  transition: 'transform 0.3s',
                }}
                onMouseEnter={(e) => { e.currentTarget.style.transform = `rotate(0deg) scale(1.02)`; }}
                onMouseLeave={(e) => { e.currentTarget.style.transform = `rotate(${it.tilt}deg)`; }}
              >
                <div style={{
                  position: 'relative',
                  height: heights[it.span],
                  borderRadius: 'var(--r-md)',
                  overflow: 'hidden',
                  border: '2px solid var(--ink)',
                  background: 'var(--ink)',
                  boxShadow: 'var(--shadow-md)',
                }}>
                  <img src={it.src} alt={it.cap} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                  <div style={{
                    position: 'absolute', top: 12, left: 12,
                    background: 'var(--cream)',
                    padding: '3px 10px', borderRadius: 999,
                    fontFamily: 'var(--sans)', fontWeight: 700, fontSize: 10, letterSpacing: '0.14em',
                    border: '1.5px solid var(--ink)',
                  }}>
                    #{it.tag}
                  </div>
                </div>
                <div className="hand" style={{
                  fontSize: 22, color: 'var(--ink)', marginTop: 10, textAlign: 'center',
                }}>
                  «{it.cap}»
                </div>
              </div>
            );
          })}
        </div>

        <div style={{ marginTop: 80, background: 'var(--amber)', border: '2px solid var(--ink)', borderRadius: 'var(--r-lg)', padding: 48 }}>
          <div className="mono-tag">Конкурс подписей</div>
          <h2 style={{ fontSize: 56, marginTop: 6 }}>Что сказал Басик?</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, marginTop: 32 }}>
            {memes.map((m, i) => (
              <div key={i} style={{
                background: 'var(--cream)',
                border: '1.5px solid var(--ink)',
                borderRadius: 'var(--r-md)',
                padding: 24,
                display: 'flex', gap: 16, alignItems: 'flex-start',
                cursor: 'pointer',
                transition: 'transform 0.2s',
              }}
              onMouseEnter={(e) => e.currentTarget.style.transform = 'translateY(-3px)'}
              onMouseLeave={(e) => e.currentTarget.style.transform = 'translateY(0)'}
              >
                <div style={{
                  fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 36,
                  color: 'var(--amber-deep)', lineHeight: 1,
                }}>{String.fromCharCode(65 + i)}</div>
                <div style={{ flex: 1, fontSize: 18, fontFamily: 'var(--serif)', fontStyle: 'italic', fontWeight: 600 }}>
                  {m}
                </div>
                <div style={{ fontSize: 13, color: 'var(--ink-soft)' }}>{120 + i * 47} 🗳</div>
              </div>
            ))}
          </div>
        </div>

        <div style={{ marginTop: 64, display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '32px 0', borderTop: '1.5px solid var(--ink)', borderBottom: '1.5px solid var(--ink)' }}>
          <div>
            <div className="mono-tag" style={{ opacity: 0.6 }}>Конец выпуска</div>
            <div style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', fontWeight: 600, fontSize: 28 }}>
              Спасибо, что долистали.
            </div>
          </div>
          <button className="btn" onClick={() => setPage('home')}>
            На обложку →
          </button>
        </div>
      </section>

      {lightbox && (
        <div onClick={() => setLightbox(null)} style={{
          position: 'fixed', inset: 0, background: 'rgba(26,22,19,0.92)',
          zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: 40, cursor: 'zoom-out',
        }}>
          <div onClick={(e) => e.stopPropagation()} style={{ maxWidth: '90vw', maxHeight: '90vh', position: 'relative' }}>
            <img src={lightbox.src} alt="" style={{ maxWidth: '100%', maxHeight: '90vh', borderRadius: 'var(--r-md)', border: '2px solid var(--cream)', display: 'block' }} />
            <div className="hand" style={{ textAlign: 'center', color: 'var(--amber-glow)', fontSize: 32, marginTop: 14 }}>
              «{lightbox.cap}»
            </div>
            <button onClick={() => setLightbox(null)} style={{
              position: 'absolute', top: -20, right: -20,
              width: 48, height: 48, borderRadius: '50%',
              background: 'var(--amber)', border: '2px solid var(--cream)',
              fontSize: 22, cursor: 'pointer',
            }}>✕</button>
          </div>
        </div>
      )}
    </div>
  );
}

Object.assign(window, { GalleryPage });
