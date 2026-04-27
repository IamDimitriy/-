/* global React, BREEDS */
const { useState: useStateRank, useMemo: useMemoRank } = React;

function RankingPage({ setPage }) {
  const [sort, setSort] = useStateRank('rank');
  const [dir, setDir] = useStateRank('asc');
  const [filter, setFilter] = useStateRank('all');

  const sorted = useMemoRank(() => {
    let arr = [...BREEDS];
    if (filter === 'big') arr = arr.filter(b => parseFloat(b.weight) >= 5);
    if (filter === 'small') arr = arr.filter(b => parseFloat(b.weight) < 5);
    if (filter === 'fluffy') arr = arr.filter(b => /пуш|плюш|длин/i.test(b.traits.join(' ')) || ['persian','maine-coon','ragdoll'].includes(b.id));
    arr.sort((a, b) => {
      const m = dir === 'asc' ? 1 : -1;
      if (sort === 'rank') return m * (a.rank - b.rank);
      if (sort === 'rating') return -m * (a.rating - b.rating);
      if (sort === 'name') return m * a.name.localeCompare(b.name);
      return 0;
    });
    return arr;
  }, [sort, dir, filter]);

  const setSortKey = (key) => {
    if (sort === key) setDir(dir === 'asc' ? 'desc' : 'asc');
    else { setSort(key); setDir(key === 'rating' ? 'desc' : 'asc'); }
  };

  return (
    <div className="page-enter">
      <section className="wrap" style={{ paddingTop: 28 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 18 }}>
          <div className="mono-tag">Полный рейтинг · 10 пород</div>
          <div className="mono-tag" style={{ opacity: 0.6 }}>обновлено · апр 2026</div>
        </div>
        <div className="rule-thick" style={{ marginBottom: 32 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1.3fr 1fr', gap: 48, alignItems: 'end', marginBottom: 48 }}>
          <h1 style={{ fontSize: 'clamp(72px, 10vw, 140px)', lineHeight: 0.88 }}>
            Честный <span style={{ fontStyle: 'italic', fontWeight: 600, color: 'var(--amber-deep)' }}>рейтинг</span>
          </h1>
          <p style={{ fontSize: 18, lineHeight: 1.5, color: 'var(--ink-soft)', paddingBottom: 16 }}>
            Баллы выставлены по шкале «плюшевости», «общительности», «фотогеничности» и «количеству мемов, в которые уже попала порода». Басик вне конкуренции.
          </p>
        </div>

        {/* Filter bar */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            {[{id:'all',l:'Все'},{id:'big',l:'Крупные'},{id:'small',l:'Мелкие'},{id:'fluffy',l:'Пушистые'}].map(f => (
              <button key={f.id}
                onClick={() => setFilter(f.id)}
                style={{
                  padding: '8px 18px',
                  background: filter === f.id ? 'var(--ink)' : 'transparent',
                  color: filter === f.id ? 'var(--cream)' : 'var(--ink)',
                  border: '1.5px solid var(--ink)',
                  borderRadius: 999,
                  fontFamily: 'var(--sans)', fontWeight: 600, fontSize: 13,
                  cursor: 'pointer',
                }}>
                {f.l}
              </button>
            ))}
          </div>
          <div style={{ fontSize: 13, color: 'var(--ink-soft)' }}>
            Показано: <b>{sorted.length}</b> из {BREEDS.length}
          </div>
        </div>

        {/* Table */}
        <div style={{ border: '2px solid var(--ink)', borderRadius: 'var(--r-lg)', overflow: 'hidden' }}>
          <div style={{
            display: 'grid',
            gridTemplateColumns: '70px 2fr 1.2fr 1fr 1fr 120px 80px',
            gap: 16,
            padding: '16px 24px',
            background: 'var(--ink)',
            color: 'var(--cream)',
            fontSize: 11,
            letterSpacing: '0.14em',
            textTransform: 'uppercase',
            fontWeight: 600,
          }}>
            <div onClick={() => setSortKey('rank')} style={{ cursor: 'pointer' }}>#{sort === 'rank' && (dir === 'asc' ? '↑' : '↓')}</div>
            <div onClick={() => setSortKey('name')} style={{ cursor: 'pointer' }}>Порода {sort === 'name' && (dir === 'asc' ? '↑' : '↓')}</div>
            <div>Характер</div>
            <div>Страна</div>
            <div>Вес</div>
            <div onClick={() => setSortKey('rating')} style={{ cursor: 'pointer', textAlign: 'right' }}>Рейтинг {sort === 'rating' && (dir === 'asc' ? '↑' : '↓')}</div>
            <div></div>
          </div>
          {sorted.map((b, i) => (
            <div key={b.id}
              onClick={() => b.id === 'british-fold' && setPage('basik')}
              style={{
                display: 'grid',
                gridTemplateColumns: '70px 2fr 1.2fr 1fr 1fr 120px 80px',
                gap: 16,
                padding: '22px 24px',
                alignItems: 'center',
                borderTop: i === 0 ? 'none' : '1px solid var(--ink)',
                background: b.rank === 1 ? 'var(--amber)' : (i % 2 ? 'var(--paper)' : 'var(--cream)'),
                cursor: b.id === 'british-fold' ? 'pointer' : 'default',
                transition: 'background 0.15s',
              }}
            >
              <div style={{ fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 32, lineHeight: 1 }}>
                {String(b.rank).padStart(2, '0')}
              </div>
              <div>
                <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22 }}>{b.name}</div>
                <div className="mono-tag" style={{ opacity: 0.55, fontSize: 10, marginTop: 2 }}>{b.en}</div>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {b.traits.slice(0, 2).map(t => (
                  <span key={t} style={{ fontSize: 11, padding: '2px 8px', border: '1px solid var(--ink)', borderRadius: 999 }}>{t}</span>
                ))}
              </div>
              <div style={{ fontSize: 13 }}>{b.origin}</div>
              <div style={{ fontSize: 13 }}>{b.weight}</div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22 }}>{b.rating}</div>
                <div style={{
                  height: 4, borderRadius: 4, background: 'rgba(26,22,19,0.15)', marginTop: 4, position: 'relative', overflow: 'hidden',
                }}>
                  <div style={{ position: 'absolute', inset: 0, width: `${b.rating * 10}%`, background: 'var(--ink)' }}></div>
                </div>
              </div>
              <div style={{ textAlign: 'right', opacity: b.id === 'british-fold' ? 1 : 0.3, fontSize: 20 }}>→</div>
            </div>
          ))}
        </div>

        {/* Methodology */}
        <div style={{ marginTop: 64, display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 24 }}>
          {[
            { n: '🏆', t: 'Как считали', d: 'Каждая порода получает баллы по 7 параметрам: плюшевость, характер, фотогеничность, дружелюбие, ор, мем-потенциал, и «хочется обнять».' },
            { n: '🐾', t: 'Кто судил', d: 'Редакция из трёх человек и одного кота (Басика). Голоса Басика считались дважды — он главный редактор.' },
            { n: '📣', t: 'Можно поспорить', d: 'Конечно можно. Но мы всё равно останемся при своём мнении. Британская вислоухая — чемпион.' },
          ].map(c => (
            <div key={c.t} style={{
              background: 'var(--paper)',
              border: '1.5px solid var(--ink)',
              borderRadius: 'var(--r-md)',
              padding: 28,
            }}>
              <div style={{ fontSize: 36 }}>{c.n}</div>
              <h3 style={{ fontSize: 24, marginTop: 10 }}>{c.t}</h3>
              <p style={{ marginTop: 10, fontSize: 14, color: 'var(--ink-soft)' }}>{c.d}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

Object.assign(window, { RankingPage });
