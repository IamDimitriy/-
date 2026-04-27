/* global React */
const { useState: useStateCare } = React;

function CarePage({ setPage }) {
  const [active, setActive] = useStateCare(0);

  const topics = [
    {
      title: 'Еда',
      sub: 'чем кормить британца',
      emoji: '🍗',
      points: [
        'Корм суперпремиум или натуралка (курица/индейка, не жирное)',
        'Никакой человеческой соли, копчёностей и молока взрослым котам',
        'Свежая вода 24/7 — британцы пьют мало, поэтому ставим фонтанчик',
        'Басик любит варёную грудку. Но только остывшую и из рук.',
      ],
    },
    {
      title: 'Шерсть',
      sub: 'как не утонуть в пухе',
      emoji: '🪮',
      points: [
        'Вычёсывать 2 раза в неделю, в линьку — каждый день',
        'Фурминатор — лучший друг хозяина британца',
        'Купать 2–3 раза в год или при особом свинстве',
        'Комки проверяем у ушей, живота и под мышками',
      ],
    },
    {
      title: 'Здоровье',
      sub: 'к врачу — по расписанию',
      emoji: '🩺',
      points: [
        'Прививки ежегодно + обработка от блох каждый месяц',
        'У вислоухих — риск остеохондродисплазии, ПКД',
        'Чистка зубов раз в 2 недели специальной пастой',
        'Вес — главный враг. Британцы склонны к полноте.',
      ],
    },
    {
      title: 'Игры',
      sub: 'чтобы не скучал',
      emoji: '🎾',
      points: [
        'Когтеточка обязательна. Лучше несколько, разной высоты.',
        'Удочка-дразнилка 15 мин в день — и кот доволен',
        'Коробки. Все коробки. Все.',
        'Окно с видом на улицу = кошачий телевизор',
      ],
    },
    {
      title: 'Лоток',
      sub: 'неприятная, но важная тема',
      emoji: '🚽',
      points: [
        'Лоток на 1.5× больше кота. Британец крупный — берём просторный',
        'Наполнитель комкующийся минеральный — самый эко и удобный',
        'Убираем 2 раза в день. Коты чистоплотны и не простят.',
        'Не ставим рядом с миской еды. Это оскорбление.',
      ],
    },
  ];

  return (
    <div className="page-enter">
      <section className="wrap" style={{ paddingTop: 28 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 18 }}>
          <div className="mono-tag">Руководство · уход и содержание</div>
          <div className="mono-tag" style={{ opacity: 0.6 }}>одобрено Басиком</div>
        </div>
        <div className="rule-thick" style={{ marginBottom: 32 }}></div>

        <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr', gap: 48, alignItems: 'end', marginBottom: 56 }}>
          <h1 style={{ fontSize: 'clamp(72px, 10vw, 140px)', lineHeight: 0.88 }}>
            Инструк-<br/>
            ция к <span style={{ fontStyle: 'italic', fontWeight: 600, color: 'var(--amber-deep)' }}>коту</span>
          </h1>
          <p style={{ fontSize: 18, lineHeight: 1.5, color: 'var(--ink-soft)', paddingBottom: 16 }}>
            Маленькое пособие для тех, кто только что завёл британца (или для тех, кто давно завёл и не уверен, что всё делает правильно). Спойлер: вы всё делаете правильно, если кот жив и мурлычит.
          </p>
        </div>

        {/* Tabs */}
        <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 32 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {topics.map((t, i) => (
              <button key={t.title} onClick={() => setActive(i)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 16,
                  padding: '16px 20px',
                  background: active === i ? 'var(--ink)' : 'var(--paper)',
                  color: active === i ? 'var(--cream)' : 'var(--ink)',
                  border: '1.5px solid var(--ink)',
                  borderRadius: 'var(--r-md)',
                  cursor: 'pointer',
                  textAlign: 'left',
                  transition: 'all 0.2s',
                  fontFamily: 'var(--sans)',
                }}>
                <span style={{ fontSize: 28 }}>{t.emoji}</span>
                <div>
                  <div style={{ fontFamily: 'var(--serif)', fontWeight: 800, fontSize: 22 }}>{t.title}</div>
                  <div style={{ fontSize: 12, opacity: 0.7, marginTop: 2 }}>{t.sub}</div>
                </div>
                <div style={{ marginLeft: 'auto', fontSize: 18, opacity: active === i ? 1 : 0.3 }}>→</div>
              </button>
            ))}
          </div>

          <div style={{
            background: 'var(--paper)',
            border: '2px solid var(--ink)',
            borderRadius: 'var(--r-lg)',
            padding: 48,
            minHeight: 500,
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 24 }}>
              <div style={{ fontSize: 72 }}>{topics[active].emoji}</div>
              <div>
                <div className="mono-tag" style={{ opacity: 0.6 }}>Глава {active + 1}</div>
                <h2 style={{ fontSize: 56, lineHeight: 1 }}>{topics[active].title}</h2>
              </div>
            </div>
            <p className="serif-italic" style={{ fontSize: 22, marginBottom: 32, color: 'var(--ink-soft)' }}>
              {topics[active].sub}
            </p>
            <div>
              {topics[active].points.map((p, i) => (
                <div key={i} style={{
                  display: 'grid', gridTemplateColumns: '48px 1fr',
                  gap: 20, padding: '20px 0',
                  borderTop: i === 0 ? 'none' : '1px dashed var(--ink)',
                }}>
                  <div style={{
                    width: 40, height: 40, borderRadius: '50%',
                    background: 'var(--amber)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontFamily: 'var(--serif)', fontWeight: 900, fontSize: 18,
                    border: '1.5px solid var(--ink)',
                  }}>{i + 1}</div>
                  <div style={{ fontSize: 17, lineHeight: 1.45, paddingTop: 6 }}>{p}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Don't-do block */}
        <div style={{ marginTop: 80, background: 'var(--rust)', color: 'var(--cream)', borderRadius: 'var(--r-lg)', padding: 48 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 40, alignItems: 'center' }}>
            <div>
              <div className="mono-tag" style={{ color: 'var(--amber-glow)' }}>Категорически нельзя</div>
              <h2 style={{ fontSize: 64, lineHeight: 0.95, marginTop: 12, color: 'var(--cream)' }}>
                Нет-нет-<span style={{ fontStyle: 'italic', fontWeight: 600 }}>нет!</span>
              </h2>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
              {[
                'Шоколад, лук, чеснок, виноград — ядовиты',
                'Молоко — у взрослых котов расстройство',
                'Кости птицы — щепки и травмы',
                'Валерьянка — это наркотик для кота, не надо',
                'Купать каждую неделю — пересушивает кожу',
                'Ругать за ор — он всё равно не поймёт',
              ].map((d, i) => (
                <div key={i} style={{ display: 'flex', gap: 12, fontSize: 16 }}>
                  <span style={{ color: 'var(--amber-glow)', fontSize: 22, lineHeight: 1 }}>✕</span>
                  <span>{d}</span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* CTA */}
        <div style={{ marginTop: 64, textAlign: 'center' }}>
          <div className="hand" style={{ fontSize: 32, color: 'var(--amber-deep)' }}>всё понятно? теперь смотри фотки →</div>
          <button className="btn btn-amber" style={{ marginTop: 20 }} onClick={() => setPage('gallery')}>
            К галерее
          </button>
        </div>
      </section>
    </div>
  );
}

Object.assign(window, { CarePage });
