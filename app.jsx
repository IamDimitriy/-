/* global React, ReactDOM, Nav, Marquee, Footer, HomePage, BasikPage, RankingPage, CarePage, GalleryPage */
const { useState: useStateApp, useEffect: useEffectApp } = React;

function App() {
  const [page, setPageRaw] = useStateApp(() => {
    const hash = window.location.hash.slice(1);
    return ['home', 'basik', 'ranking', 'care', 'gallery'].includes(hash) ? hash : 'home';
  });

  const setPage = (p) => {
    setPageRaw(p);
    window.location.hash = p;
  };

  useEffectApp(() => {
    const onHash = () => {
      const h = window.location.hash.slice(1);
      if (['home', 'basik', 'ranking', 'care', 'gallery'].includes(h)) setPageRaw(h);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  let Page;
  switch (page) {
    case 'basik': Page = BasikPage; break;
    case 'ranking': Page = RankingPage; break;
    case 'care': Page = CarePage; break;
    case 'gallery': Page = GalleryPage; break;
    default: Page = HomePage;
  }

  return (
    <div key={page} data-screen-label={`0${['home','basik','ranking','care','gallery'].indexOf(page)+1} ${page}`}>
      <Nav page={page} setPage={setPage} />
      <Page setPage={setPage} />
      <Footer setPage={setPage} />
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
