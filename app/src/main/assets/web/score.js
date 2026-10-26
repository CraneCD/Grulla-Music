/*
 * Score view running inside the app's WebView. It renders MusicXML with Verovio
 * (in a Web Worker, see verovio-worker.js) and shows playback guidance. Playback
 * timing lives on the Kotlin side, which calls window.grulla.* when the set of
 * sounding notes changes.
 *
 * Kotlin -> JS (window.grulla):
 *   load(url, layout, options)  fetch MusicXML from url, render it, report onScoreLoaded.
 *                            options: {transpose: '' | '+2' | '-m3' ..., concertPitch: bool}
 *   setLayout(layout)        {scale, mode: 'page' | 'continuous'}; re-lays out the score
 *   setOptions(options)      {earlyTurn: bool}: in page view, turn on the last bar of the page
 *   highlight(ids, follow, kind)  mark notes ('play': sounding now, 'listen': to play next),
 *                            move the cursor, keep the system visible
 *   clearHighlight()
 *   showPage(n)              page view only
 *   turn(direction)          +1 / -1: next or previous page (page view) or screenful (continuous)
 *   countIn(beat, total)     show the count-in beat (beat 0 hides it)
 *   setLoop(firstId, lastId) shade the measures of the A–B loop (null clears it)
 *   thumbnail(width)         render the top of page 1 to a PNG, reported via onThumbnail
 *   setTheme(name)           'day' or 'night'
 *
 * JS -> Kotlin (window.GrullaHost, an @JavascriptInterface object):
 *   onReady(verovioVersion)
 *   onFirstPage()            the first page is on screen; the rest is still being prepared
 *   onScoreLoaded(json)      {"pageCount": n, "timemap": [...], "midi": "<base64>", "source": url,
 *                             "pitches": {noteId: midiPitch}}
 *   onMeasureTapped(measureId)
 *   onMeasureLongPressed(measureId)
 *   onZoomChanged(scale)
 *   onPageChanged(page, pageCount, spread)   spread: two pages side by side (page is the left one)
 *   onThumbnail(pngDataUrl)
 *   onError(message)
 */
'use strict';

(function () {
  const MIN_SCALE = 15;
  const MAX_SCALE = 120;
  const CONTINUOUS_PAGE_HEIGHT = 60000; // Verovio's maximum: one tall page in most cases.
  const SPREAD_MIN_WIDTH = 800; // CSS px; page view shows two pages side by side when wider (and landscape).
  const VEROVIO_URL = '../verovio/verovio-toolkit-wasm.js';
  const WORKER_URL = 'verovio-worker.js';

  const host = window.GrullaHost || createLoggingHost();
  const wrap = document.getElementById('wrap');
  const scoreEl = document.getElementById('score');
  const cursorEl = document.getElementById('cursor');
  const countInEl = createCountInBadge();
  const loopLayer = document.createElement('div');
  loopLayer.id = 'loop-layer';
  wrap.insertBefore(loopLayer, cursorEl); // Above the score, below the cursor.

  const state = {
    engine: null,
    loaded: false,
    /** Bumped by every load(); work for an older score stops at its next await. */
    generation: 0,
    scale: 40,
    mode: 'continuous',
    spread: false,
    earlyTurn: false,
    /** Page view: the (left) page on screen. */
    page: 1,
    pageCount: 0,
    /** SVG of every page, and the page each element id is on. */
    svgs: [],
    elementPage: new Map(),
    highlighted: [],
    highlightKind: 'play',
    /** Pages left early (turned on their last bar) while their notes still sound. */
    earlyFrom: null,
    loop: null, // {first, last} measure ids
    lastWidth: 0,
  };

  // --- Verovio engine ----------------------------------------------------------
  // call(method, ...args) returns a Promise, whether Verovio runs in a worker or here.

  function startWorkerEngine() {
    return Promise.all([fetchText(VEROVIO_URL), fetchText(WORKER_URL)]).then(function (sources) {
      const blob = new Blob([sources[0], '\n;\n', sources[1]], { type: 'text/javascript' });
      const url = URL.createObjectURL(blob);
      const worker = new Worker(url);
      const pending = new Map();
      let nextId = 1;
      return new Promise(function (resolve, reject) {
        worker.onmessage = function (event) {
          const message = event.data;
          if (message.type === 'ready') {
            URL.revokeObjectURL(url);
            resolve({
              kind: 'worker',
              version: message.version,
              call: function (method) {
                const args = Array.prototype.slice.call(arguments, 1);
                const id = nextId++;
                return new Promise(function (done, fail) {
                  pending.set(id, { done: done, fail: fail });
                  worker.postMessage({ id: id, method: method, args: args });
                });
              },
            });
          } else if (message.type === 'failed') {
            reject(new Error(message.error));
          } else if (message.type === 'result') {
            const p = pending.get(message.id);
            pending.delete(message.id);
            if (!p) return;
            if ('error' in message) p.fail(new Error(message.error)); else p.done(message.result);
          }
        };
        worker.onerror = function (event) {
          event.preventDefault();
          reject(new Error(event.message || 'worker failed'));
        };
      });
    });
  }

  /** Fallback: Verovio on the page itself (slower to start, can block while laying out). */
  function startLocalEngine() {
    return loadScript(VEROVIO_URL).then(function () { return loadScript(WORKER_URL); }).then(function () {
      return new Promise(function (resolve) {
        const ready = function () {
          const methods = window.GrullaVerovio.createMethods(new verovio.toolkit());
          resolve({
            kind: 'page',
            version: methods.version(),
            call: function (method) {
              const args = Array.prototype.slice.call(arguments, 1);
              return new Promise(function (done) { done(methods[method].apply(null, args)); });
            },
          });
        };
        if (verovio.module.calledRun) ready(); else verovio.module.onRuntimeInitialized = ready;
      });
    });
  }

  startWorkerEngine()
    .catch(function (e) {
      console.warn('[grulla] Verovio worker unavailable, running on the page: ' + messageOf(e));
      return startLocalEngine();
    })
    .then(function (engine) {
      state.engine = engine;
      host.onReady(engine.version);
    })
    .catch(function (e) {
      host.onError('Verovio failed to start: ' + messageOf(e));
    });

  // --- Layout ----------------------------------------------------------------

  function viewport() {
    return { width: Math.max(240, document.documentElement.clientWidth), height: Math.max(240, window.innerHeight) };
  }

  function wantsSpread() {
    const v = viewport();
    return state.mode === 'page' && v.width >= SPREAD_MIN_WIDTH && v.width > v.height;
  }

  function layoutOptions() {
    const v = viewport();
    const pageMode = state.mode === 'page';
    const width = state.spread ? Math.floor((v.width - 8) / 2) : v.width;
    return {
      scale: state.scale,
      pageWidth: clamp(Math.round(width * 100 / state.scale), 100, 60000),
      pageHeight: pageMode ? clamp(Math.round(v.height * 100 / state.scale), 100, 60000) : CONTINUOUS_PAGE_HEIGHT,
      adjustPageHeight: !pageMode,
      breaks: 'auto',
      header: 'auto',
      footer: 'none',
      pageMarginTop: 40,
      pageMarginBottom: 40,
      pageMarginLeft: 40,
      pageMarginRight: 40,
    };
  }

  function applyLayout(layout) {
    if (!layout) return;
    if (typeof layout.scale === 'number') state.scale = clamp(Math.round(layout.scale), MIN_SCALE, MAX_SCALE);
    if (layout.mode === 'page' || layout.mode === 'continuous') state.mode = layout.mode;
    state.spread = wantsSpread();
    document.body.classList.toggle('mode-page', state.mode === 'page');
    document.body.classList.toggle('spread', state.spread);
  }

  /** Records a page's SVG and the ids of the elements on it. */
  function storePage(page, svg) {
    state.svgs[page - 1] = svg;
    const ids = /\sid="([^"]+)"/g;
    let match;
    while ((match = ids.exec(svg)) !== null) {
      if (!state.elementPage.has(match[1])) state.elementPage.set(match[1], page);
    }
  }

  function pageStep() {
    return state.spread ? 2 : 1;
  }

  /** In a spread the left page is always odd. */
  function normalPage(page) {
    const p = clamp(page, 1, Math.max(1, state.pageCount));
    return state.spread ? p - ((p - 1) % 2) : p;
  }

  function visiblePages() {
    if (state.mode !== 'page') return state.svgs.map(function (_, i) { return i + 1; });
    const pages = [state.page];
    if (state.spread && state.page + 1 <= state.pageCount) pages.push(state.page + 1);
    return pages;
  }

  function pageHtml(page) {
    return '<div class="page" data-page="' + page + '">' + (state.svgs[page - 1] || '') + '</div>';
  }

  /** Puts the visible pages in the document and repaints the overlays. */
  function render() {
    state.page = normalPage(state.page);
    scoreEl.innerHTML = visiblePages().map(pageHtml).join('');
    state.lastWidth = document.documentElement.clientWidth;
    host.onPageChanged(state.page, state.pageCount, state.spread);
    paintLoop();
    paintHighlight(false);
    window.grullaScore.afterRender.forEach(function (hook) { hook(); });
  }

  /** Lays out the score in the engine and fetches every page; false if a newer load took over. */
  async function layoutAndFetch(generation, method, args, onPage) {
    const count = await state.engine.call.apply(null, [method].concat(args));
    if (generation !== state.generation) return false;
    const svgs = [];
    for (let p = 1; p <= count; p++) {
      const svg = await state.engine.call('page', p);
      if (generation !== state.generation) return false;
      svgs.push(svg);
      if (onPage) onPage(p, count, svg);
    }
    state.pageCount = count;
    state.svgs = [];
    state.elementPage = new Map();
    svgs.forEach(function (svg, i) { storePage(i + 1, svg); });
    return true;
  }

  /** Re-lays out the score (zoom, layout or size changed), keeping the reading position. */
  let layoutRunning = false;
  let layoutAgain = false;

  async function relayout() {
    if (!state.loaded) return;
    if (layoutRunning) {
      layoutAgain = true;
      return;
    }
    layoutRunning = true;
    try {
      do {
        layoutAgain = false;
        const generation = state.generation;
        const doc = document.documentElement;
        const fraction = doc.scrollHeight > 0 ? window.scrollY / doc.scrollHeight : 0;
        const anchorPage = state.mode === 'page' ? state.elementPage.get(firstMeasureId()) : null;
        state.spread = wantsSpread();
        document.body.classList.toggle('spread', state.spread);
        const ok = await layoutAndFetch(generation, 'layout', [layoutOptions()]);
        if (!ok) return;
        if (layoutAgain) continue; // Changed again meanwhile: lay out once more before showing it.
        if (anchorPage) state.page = anchorPage; // Approximately where we were.
        state.earlyFrom = null;
        render();
        if (state.highlighted.length > 0) {
          paintHighlight(true, 'auto');
        } else {
          window.scrollTo(0, fraction * doc.scrollHeight);
        }
      } while (layoutAgain);
    } catch (e) {
      host.onError(messageOf(e));
    } finally {
      layoutRunning = false;
    }
  }

  function firstMeasureId() {
    const m = scoreEl.querySelector('g.measure');
    return m ? m.id : null;
  }

  // --- API for Kotlin -------------------------------------------------------

  /** Verovio options for transposition, set when the score is read. */
  function scoreOptions(options) {
    const result = {};
    if (options && options.transpose) result.transpose = String(options.transpose);
    if (options && options.concertPitch) result.transposeToSoundingPitch = true;
    return result;
  }

  async function load(url, layout, options) {
    if (!state.engine) {
      host.onError('Verovio is not ready yet.');
      return;
    }
    const generation = ++state.generation;
    try {
      applyLayout(layout);
      const response = await fetch(url);
      if (!response.ok) throw new Error('HTTP ' + response.status + ' while reading the score');
      const data = await response.text();
      if (generation !== state.generation) return;
      state.loaded = false;
      state.page = 1;
      state.highlighted = [];
      state.loop = null;
      state.earlyFrom = null;
      scoreEl.innerHTML = '';
      cursorEl.hidden = true;
      loopLayer.innerHTML = '';
      window.scrollTo(0, 0);
      let shown = false;
      const loadOptions = Object.assign(layoutOptions(), scoreOptions(options));
      const ok = await layoutAndFetch(generation, 'load', [data, loadOptions], function (page, count, svg) {
        // Show the first page as soon as it is ready; the rest follows when all are done.
        if (page === 1) {
          scoreEl.innerHTML = '<div class="page" data-page="1">' + svg + '</div>';
          shown = true;
          host.onFirstPage();
        }
      });
      if (!ok) return;
      state.loaded = true;
      if (!shown) host.onFirstPage();
      render();
      const extra = await state.engine.call('playbackData');
      if (generation !== state.generation) return;
      host.onScoreLoaded(JSON.stringify({
        pageCount: state.pageCount, timemap: extra.timemap, midi: extra.midi, source: url, pitches: extra.pitches,
      }));
    } catch (e) {
      if (generation !== state.generation) return;
      state.loaded = false;
      scoreEl.innerHTML = '';
      host.onError(messageOf(e));
    }
  }

  function setLayout(layout) {
    const before = state.scale + '/' + state.mode;
    applyLayout(layout);
    if (state.scale + '/' + state.mode !== before) relayout();
  }

  function setOptions(options) {
    if (options && typeof options.earlyTurn === 'boolean') state.earlyTurn = options.earlyTurn;
  }

  function highlight(ids, follow, kind) {
    state.highlighted = Array.isArray(ids) ? ids : [];
    state.highlightKind = kind === 'listen' ? 'listen' : 'play';
    if (state.highlighted.length === 0) state.earlyFrom = null;
    paintHighlight(follow !== false, 'smooth');
  }

  function setTheme(name) {
    document.documentElement.dataset.theme = name === 'night' ? 'night' : 'day';
  }

  function clearHighlight() {
    highlight([], false);
  }

  function showPage(page) {
    if (!state.loaded || state.mode !== 'page') return;
    const target = normalPage(page);
    if (target === state.page) return;
    state.page = target;
    state.earlyFrom = null;
    render();
    window.scrollTo(0, 0);
  }

  /** Page-turn pedals and the page buttons: one page (or spread) or one screenful. */
  function turn(direction) {
    if (!state.loaded) return;
    const dir = direction < 0 ? -1 : 1;
    if (state.mode === 'page') {
      showPage(state.page + dir * pageStep());
      return;
    }
    const height = window.innerHeight;
    if (dir > 0) {
      // Bring the first system that is cut off at the bottom to the top.
      const systems = Array.from(scoreEl.querySelectorAll('g.system'));
      const next = systems.find(function (s) { return s.getBoundingClientRect().bottom > height - 4; });
      const top = next ? next.getBoundingClientRect().top : height * 0.85;
      window.scrollBy({ top: Math.max(height * 0.25, top - 12), behavior: 'smooth' });
    } else {
      window.scrollBy({ top: -height * 0.8, behavior: 'smooth' });
    }
  }

  function setLoop(firstId, lastId) {
    state.loop = firstId ? { first: firstId, last: lastId || firstId } : null;
    paintLoop();
  }

  /** Draws the top of the first page (at most 1.4 × width tall) and reports it as a PNG data URL. */
  function thumbnail(width) {
    const svgText = state.svgs[0];
    if (!state.loaded || !svgText) return;
    const holder = document.createElement('div');
    holder.innerHTML = svgText;
    const svg = holder.querySelector('svg');
    if (!svg) return;
    svg.setAttribute('color', '#000000'); // The page's theme colours do not apply outside it.
    svg.setAttribute('fill', '#000000');
    const xml = new XMLSerializer().serializeToString(svg);
    const url = URL.createObjectURL(new Blob([xml], { type: 'image/svg+xml' }));
    const image = new Image();
    image.onload = function () {
      const scale = width / image.width;
      const height = Math.min(Math.round(image.height * scale), Math.round(width * 1.4));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(image, 0, 0, width, image.height * scale);
      URL.revokeObjectURL(url);
      try {
        host.onThumbnail(canvas.toDataURL('image/png'));
      } catch (e) {
        host.onError('Thumbnail failed: ' + messageOf(e));
      }
    };
    image.onerror = function () {
      URL.revokeObjectURL(url);
    };
    image.src = url;
  }

  function countIn(beat, total) {
    if (!beat) {
      countInEl.hidden = true;
      return;
    }
    countInEl.textContent = String(beat);
    countInEl.title = beat + ' / ' + total;
    countInEl.hidden = false;
    countInEl.classList.toggle('downbeat', beat === 1);
    countInEl.classList.remove('beat');
    void countInEl.offsetWidth; // Restart the CSS animation.
    countInEl.classList.add('beat');
  }

  // --- Highlight, cursor, following ----------------------------------------

  function paintHighlight(follow, behavior) {
    for (const el of Array.from(scoreEl.querySelectorAll('.playing, .expected'))) el.classList.remove('playing', 'expected');
    const cssClass = state.highlightKind === 'listen' ? 'expected' : 'playing';
    cursorEl.classList.toggle('listen', state.highlightKind === 'listen');
    const ids = state.highlighted;
    if (!state.loaded || ids.length === 0) {
      cursorEl.hidden = true;
      return;
    }
    if (state.mode === 'page' && follow) {
      const page = state.elementPage.get(ids[0]);
      if (page && state.earlyFrom && state.earlyFrom.indexOf(page) >= 0) {
        // Turned early: stay on the next page while the last bar of the previous one sounds.
        cursorEl.hidden = true;
        return;
      }
      state.earlyFrom = null;
      if (page && visiblePages().indexOf(page) < 0) {
        state.page = normalPage(page);
        render(); // render() calls back into paintHighlight with follow = false.
        window.scrollTo(0, 0);
      }
    }
    let anchor = null;
    for (const id of ids) {
      const el = document.getElementById(id);
      if (!el) continue;
      el.classList.add(cssClass);
      if (!anchor) anchor = el;
    }
    if (!anchor) {
      cursorEl.hidden = true;
      return;
    }
    placeCursor(anchor);
    if (follow) {
      keepVisible(anchor, behavior || 'smooth');
      if (state.mode === 'page' && state.earlyTurn && isOnLastBar(anchor) && state.page + pageStep() <= state.pageCount) {
        state.earlyFrom = visiblePages();
        state.page = normalPage(state.page + pageStep());
        render();
        window.scrollTo(0, 0);
      }
    }
  }

  /** Whether a note is in the last bar of the last system on screen (page view). */
  function isOnLastBar(noteEl) {
    const measure = noteEl.closest('g.measure');
    const pages = scoreEl.querySelectorAll('.page');
    if (!measure || pages.length === 0) return false;
    const measures = pages[pages.length - 1].querySelectorAll('g.measure');
    return measures.length > 0 && measures[measures.length - 1] === measure;
  }

  let lastCursorSystem = null;

  /**
   * Vertical extent of a system's staves. The <g class="system"> box also covers
   * titles and tempo marks above the first system, so union the staves instead.
   */
  function staffBox(system) {
    const staves = system.querySelectorAll('g.staff');
    if (staves.length === 0) return system.getBoundingClientRect();
    let top = Infinity;
    let bottom = -Infinity;
    for (const staff of Array.from(staves)) {
      const r = staff.getBoundingClientRect();
      if (r.height === 0) continue;
      top = Math.min(top, r.top);
      bottom = Math.max(bottom, r.bottom);
    }
    return top < bottom ? { top: top, bottom: bottom, height: bottom - top } : system.getBoundingClientRect();
  }

  function placeCursor(noteEl) {
    const system = noteEl.closest('.system') || noteEl;
    const origin = wrap.getBoundingClientRect();
    const note = noteEl.getBoundingClientRect();
    const sys = staffBox(system);
    const pad = 4;
    // Glide along a system, but jump (no transition) to a new system or page.
    const jump = cursorEl.hidden || system !== lastCursorSystem;
    lastCursorSystem = system;
    cursorEl.classList.toggle('jump', jump);
    cursorEl.style.left = (note.left - origin.left - pad) + 'px';
    cursorEl.style.top = (sys.top - origin.top - pad) + 'px';
    cursorEl.style.width = (Math.max(note.width, 8) + 2 * pad) + 'px';
    cursorEl.style.height = (sys.height + 2 * pad) + 'px';
    cursorEl.hidden = false;
    if (jump) void cursorEl.offsetWidth; // Apply the new position before transitions return.
  }

  /** Scrolls so the whole current system is on screen, with some look-ahead room below. */
  function keepVisible(noteEl, behavior) {
    const system = noteEl.closest('.system') || noteEl;
    const rect = system.getBoundingClientRect();
    const height = window.innerHeight;
    const margin = Math.min(48, height * 0.08);
    if (rect.top >= margin && rect.bottom <= height - margin) return;
    window.scrollBy({ top: rect.top - Math.max(margin, height * 0.12), behavior: behavior });
  }

  // --- A–B loop shading ---------------------------------------------------------
  // One band per system, spanning the loop's measures on it (in notation order).

  function paintLoop() {
    loopLayer.innerHTML = '';
    if (!state.loaded || !state.loop) return;
    const measures = Array.from(scoreEl.querySelectorAll('g.measure'));
    let a = measures.findIndex((m) => m.id === state.loop.first);
    let b = measures.findIndex((m) => m.id === state.loop.last);
    if (a < 0 && b < 0) {
      // Neither end is on screen: shade everything if the screen lies inside the loop.
      const pa = state.elementPage.get(state.loop.first);
      const pb = state.elementPage.get(state.loop.last);
      const shown = visiblePages();
      if (!pa || !pb || shown[0] <= Math.min(pa, pb) || shown[0] >= Math.max(pa, pb)) return;
      a = 0;
      b = measures.length - 1;
    }
    if (a < 0) a = 0;
    if (b < 0) b = measures.length - 1;
    if (a > b) { const t = a; a = b; b = t; }
    const origin = wrap.getBoundingClientRect();
    const bands = new Map();
    for (let i = a; i <= b; i++) {
      const measure = measures[i];
      const system = measure.closest('.system') || measure;
      const r = measure.getBoundingClientRect();
      const band = bands.get(system) || { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity };
      band.left = Math.min(band.left, r.left);
      band.right = Math.max(band.right, r.right);
      const s = staffBox(system);
      band.top = Math.min(band.top, s.top);
      band.bottom = Math.max(band.bottom, s.bottom);
      bands.set(system, band);
    }
    const list = Array.from(bands.values());
    list.forEach(function (band, index) {
      const el = document.createElement('div');
      el.className = 'loop-band' + (index === 0 && measures[a].id === state.loop.first ? ' loop-start' : '') +
        (index === list.length - 1 && measures[b].id === state.loop.last ? ' loop-end' : '');
      el.style.left = (band.left - origin.left) + 'px';
      el.style.top = (band.top - origin.top - 6) + 'px';
      el.style.width = (band.right - band.left) + 'px';
      el.style.height = (band.bottom - band.top + 12) + 'px';
      loopLayer.appendChild(el);
    });
  }

  // --- Seek by tapping a measure --------------------------------------------

  function measureAt(x, y) {
    let best = null;
    let bestDistance = Infinity;
    for (const system of Array.from(scoreEl.querySelectorAll('g.system'))) {
      const r = system.getBoundingClientRect();
      const dy = y < r.top ? r.top - y : (y > r.bottom ? y - r.bottom : 0);
      const dx = x < r.left ? r.left - x : (x > r.right ? x - r.right : 0);
      const distance = dy + dx;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = system;
      }
    }
    if (!best || bestDistance > 40) return null;
    let nearest = null;
    let nearestDistance = Infinity;
    for (const measure of Array.from(best.querySelectorAll('g.measure'))) {
      const r = measure.getBoundingClientRect();
      if (x >= r.left && x <= r.right) return measure;
      const distance = x < r.left ? r.left - x : x - r.right;
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = measure;
      }
    }
    return nearestDistance < 24 ? nearest : null;
  }

  let suppressClickUntil = 0;

  function measureFor(target, x, y) {
    const direct = target && target.closest ? target.closest('g.measure') : null;
    return direct || measureAt(x, y);
  }

  /** Other modules (annotations) can take over touches on the score. */
  const gestures = { enabled: true };

  scoreEl.addEventListener('click', function (event) {
    if (!state.loaded || !gestures.enabled || Date.now() < suppressClickUntil) return;
    const measure = measureFor(event.target, event.clientX, event.clientY);
    if (measure) host.onMeasureTapped(measure.id);
  });

  // --- Long press on a measure: set the loop's A / B ------------------------------

  const LONG_PRESS_MS = 550;
  let press = null;

  function longPress(target, x, y) {
    const measure = measureFor(target, x, y);
    if (!measure) return;
    suppressClickUntil = Date.now() + 700;
    if (navigator.vibrate) navigator.vibrate(15);
    host.onMeasureLongPressed(measure.id);
  }

  scoreEl.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 1 || !state.loaded || !gestures.enabled) {
      cancelPress();
      return;
    }
    const t = event.touches[0];
    press = { x: t.clientX, y: t.clientY, target: event.target };
    press.timer = setTimeout(function () {
      const p = press;
      press = null;
      if (p) longPress(p.target, p.x, p.y);
    }, LONG_PRESS_MS);
  }, { passive: true });

  scoreEl.addEventListener('touchmove', function (event) {
    if (!press) return;
    const t = event.touches[0];
    if (Math.hypot(t.clientX - press.x, t.clientY - press.y) > 12) cancelPress();
  }, { passive: true });

  scoreEl.addEventListener('touchend', cancelPress);
  scoreEl.addEventListener('touchcancel', cancelPress);

  function cancelPress() {
    if (press) clearTimeout(press.timer);
    press = null;
  }

  // Mouse right-click (desktop browsers, emulators) does the same as a long press.
  scoreEl.addEventListener('contextmenu', function (event) {
    event.preventDefault();
    if (!state.loaded || !gestures.enabled || event.pointerType === 'touch' || Date.now() < suppressClickUntil) return;
    longPress(event.target, event.clientX, event.clientY);
  });

  // --- Pinch zoom -------------------------------------------------------------
  // While pinching, the rendered page is scaled with a CSS transform for instant
  // feedback; on release the score is re-laid out at the new Verovio scale.

  let pinch = null;

  function touchDistance(touches) {
    return Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY);
  }

  document.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 2 || !state.loaded || !gestures.enabled) return;
    cancelPress();
    const origin = wrap.getBoundingClientRect();
    const cx = (event.touches[0].clientX + event.touches[1].clientX) / 2;
    const cy = (event.touches[0].clientY + event.touches[1].clientY) / 2;
    pinch = { start: touchDistance(event.touches), ratio: 1 };
    wrap.style.transformOrigin = (cx - origin.left) + 'px ' + (cy - origin.top) + 'px';
    event.preventDefault();
  }, { passive: false });

  document.addEventListener('touchmove', function (event) {
    if (!pinch || event.touches.length !== 2) return;
    pinch.ratio = clamp(touchDistance(event.touches) / pinch.start, MIN_SCALE / state.scale, MAX_SCALE / state.scale);
    wrap.style.transform = 'scale(' + pinch.ratio + ')';
    event.preventDefault();
  }, { passive: false });

  function endPinch() {
    if (!pinch) return;
    const ratio = pinch.ratio;
    pinch = null;
    wrap.style.transform = '';
    suppressClickUntil = Date.now() + 400;
    const scale = clamp(Math.round(state.scale * ratio), MIN_SCALE, MAX_SCALE);
    if (scale !== state.scale) {
      state.scale = scale;
      relayout();
      host.onZoomChanged(scale);
    }
  }

  document.addEventListener('touchend', function (event) {
    if (event.touches.length < 2) endPinch();
  });
  document.addEventListener('touchcancel', endPinch);

  // --- Keys (page-turn pedals when the page has focus, desktop browsers) --------

  document.addEventListener('keydown', function (event) {
    const next = ['PageDown', 'ArrowRight', 'ArrowDown', ' '];
    const previous = ['PageUp', 'ArrowLeft', 'ArrowUp'];
    if (next.indexOf(event.key) >= 0) turn(1);
    else if (previous.indexOf(event.key) >= 0) turn(-1);
    else return;
    event.preventDefault();
  });

  // --- Resize (rotation, split screen) ----------------------------------------

  let resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      const width = document.documentElement.clientWidth;
      if (width !== state.lastWidth || state.mode === 'page' || wantsSpread() !== state.spread) relayout();
    }, 200);
  });

  // --- Helpers -----------------------------------------------------------------

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function messageOf(error) {
    return (error && error.message) ? error.message : String(error);
  }

  function fetchText(url) {
    return fetch(url).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
      return response.text();
    });
  }

  function loadScript(url) {
    return new Promise(function (resolve, reject) {
      const script = document.createElement('script');
      script.src = url;
      script.onload = resolve;
      script.onerror = function () { reject(new Error('Could not load ' + url)); };
      document.head.appendChild(script);
    });
  }

  function createCountInBadge() {
    const badge = document.createElement('div');
    badge.id = 'count-in';
    badge.hidden = true;
    document.body.appendChild(badge);
    return badge;
  }

  /** Stand-in used when the page is opened outside the app (e.g. in a desktop browser). */
  function createLoggingHost() {
    const events = [];
    window.grullaEvents = events;
    const record = function (name) {
      return function () {
        const args = Array.prototype.slice.call(arguments);
        events.push({ name: name, args: args });
        if (name === 'onError') console.error('[grulla]', args[0]);
      };
    };
    const names = ['onReady', 'onFirstPage', 'onScoreLoaded', 'onMeasureTapped', 'onMeasureLongPressed', 'onZoomChanged',
      'onPageChanged', 'onThumbnail', 'onError', 'onAnnotationsChanged'];
    const stand = {};
    names.forEach(function (n) { stand[n] = record(n); });
    return stand;
  }

  window.grulla = {
    load: load,
    setLayout: setLayout,
    setOptions: setOptions,
    highlight: highlight,
    clearHighlight: clearHighlight,
    showPage: showPage,
    turn: turn,
    countIn: countIn,
    setLoop: setLoop,
    thumbnail: thumbnail,
    setTheme: setTheme,
    /** For tests and diagnostics: 'worker' or 'page'. */
    engineKind: function () { return state.engine ? state.engine.kind : null; },
  };

  /** Shared with annotate.js. */
  window.grullaScore = {
    host: host,
    state: state,
    wrap: wrap,
    scoreEl: scoreEl,
    gestures: gestures,
    staffBox: staffBox,
    afterRender: [],
  };
})();
