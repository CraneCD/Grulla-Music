/*
 * Score view running inside the app's WebView. It renders MusicXML with Verovio
 * and shows playback guidance. Playback timing lives on the Kotlin side, which
 * calls window.grulla.* when the set of sounding notes changes.
 *
 * Kotlin -> JS (window.grulla):
 *   load(url, layout)        fetch MusicXML from url, render it, report onScoreLoaded
 *   setLayout(layout)        {scale, mode: 'page' | 'continuous'}; re-lays out the score
 *   highlight(ids, follow)   mark sounding notes, move the cursor, keep the system visible
 *   clearHighlight()
 *   showPage(n)              page mode only
 *   countIn(beat, total)     show the count-in beat (beat 0 hides it)
 *
 * JS -> Kotlin (window.GrullaHost, an @JavascriptInterface object):
 *   onReady(verovioVersion)
 *   onScoreLoaded(json)      {"pageCount": n, "timemap": [...], "midi": "<base64>"}
 *   onMeasureTapped(measureId)
 *   onZoomChanged(scale)
 *   onPageChanged(page, pageCount)
 *   onError(message)
 */
'use strict';

(function () {
  const MIN_SCALE = 15;
  const MAX_SCALE = 120;
  const CONTINUOUS_PAGE_HEIGHT = 60000; // Verovio's maximum: one tall page in most cases.

  const host = window.GrullaHost || createLoggingHost();
  const wrap = document.getElementById('wrap');
  const scoreEl = document.getElementById('score');
  const cursorEl = document.getElementById('cursor');
  const countInEl = createCountInBadge();

  const state = {
    tk: null,
    loaded: false,
    scale: 40,
    mode: 'continuous',
    page: 1,
    pageCount: 0,
    highlighted: [],
    lastWidth: 0,
  };

  // --- Verovio start-up ------------------------------------------------------

  function onRuntimeReady() {
    try {
      state.tk = new verovio.toolkit();
      host.onReady(state.tk.getVersion());
    } catch (e) {
      host.onError('Verovio failed to start: ' + messageOf(e));
    }
  }

  if (verovio.module.calledRun) {
    onRuntimeReady();
  } else {
    verovio.module.onRuntimeInitialized = onRuntimeReady;
  }

  // --- Layout ----------------------------------------------------------------

  function layoutOptions() {
    const width = Math.max(240, document.documentElement.clientWidth);
    const height = Math.max(240, window.innerHeight);
    const pageMode = state.mode === 'page';
    return {
      scale: state.scale,
      pageWidth: clamp(Math.round(width * 100 / state.scale), 100, 60000),
      pageHeight: pageMode ? clamp(Math.round(height * 100 / state.scale), 100, 60000) : CONTINUOUS_PAGE_HEIGHT,
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
    document.body.classList.toggle('mode-page', state.mode === 'page');
  }

  function render() {
    const tk = state.tk;
    state.pageCount = tk.getPageCount();
    state.page = clamp(state.page, 1, Math.max(1, state.pageCount));
    let html = '';
    if (state.mode === 'page') {
      html = pageHtml(state.page);
    } else {
      for (let p = 1; p <= state.pageCount; p++) html += pageHtml(p);
    }
    scoreEl.innerHTML = html;
    state.lastWidth = document.documentElement.clientWidth;
    host.onPageChanged(state.page, state.pageCount);
    paintHighlight(false);
  }

  function pageHtml(page) {
    return '<div class="page" data-page="' + page + '">' + state.tk.renderToSVG(page) + '</div>';
  }

  /** Re-lays out the score, keeping the reading position. */
  function relayout() {
    if (!state.loaded) return;
    const doc = document.documentElement;
    const fraction = doc.scrollHeight > 0 ? window.scrollY / doc.scrollHeight : 0;
    state.tk.setOptions(layoutOptions());
    state.tk.redoLayout();
    render();
    if (state.highlighted.length > 0) {
      paintHighlight(true, 'auto');
    } else {
      window.scrollTo(0, fraction * doc.scrollHeight);
    }
  }

  // --- API for Kotlin -------------------------------------------------------

  async function load(url, layout) {
    if (!state.tk) {
      host.onError('Verovio is not ready yet.');
      return;
    }
    try {
      applyLayout(layout);
      const response = await fetch(url);
      if (!response.ok) throw new Error('HTTP ' + response.status + ' while reading the score');
      const data = await response.text();
      const tk = state.tk;
      tk.setOptions(layoutOptions());
      if (!tk.loadData(data)) throw new Error('Verovio could not read this file as MusicXML.');
      state.loaded = true;
      state.page = 1;
      state.highlighted = [];
      const timemap = tk.renderToTimemap({ includeMeasures: true, includeRests: true });
      const midi = tk.renderToMIDI();
      render();
      window.scrollTo(0, 0);
      host.onScoreLoaded(JSON.stringify({ pageCount: state.pageCount, timemap: timemap, midi: midi }));
    } catch (e) {
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

  function highlight(ids, follow) {
    state.highlighted = Array.isArray(ids) ? ids : [];
    paintHighlight(follow !== false, 'smooth');
  }

  function clearHighlight() {
    highlight([], false);
  }

  function showPage(page) {
    if (!state.loaded || state.mode !== 'page') return;
    const target = clamp(page, 1, state.pageCount);
    if (target === state.page) return;
    state.page = target;
    render();
    window.scrollTo(0, 0);
  }

  function countIn(beat, total) {
    if (!beat) {
      countInEl.hidden = true;
      return;
    }
    countInEl.textContent = String(beat);
    countInEl.title = beat + ' / ' + total;
    countInEl.hidden = false;
    countInEl.classList.remove('beat');
    void countInEl.offsetWidth; // Restart the CSS animation.
    countInEl.classList.add('beat');
  }

  // --- Highlight, cursor, following ----------------------------------------

  function paintHighlight(follow, behavior) {
    for (const el of Array.from(scoreEl.querySelectorAll('.playing'))) el.classList.remove('playing');
    const ids = state.highlighted;
    if (!state.loaded || ids.length === 0) {
      cursorEl.hidden = true;
      return;
    }
    if (state.mode === 'page') {
      const page = state.tk.getPageWithElement(ids[0]);
      if (page > 0 && page !== state.page) {
        state.page = page;
        render(); // render() calls back into paintHighlight with follow = false.
        window.scrollTo(0, 0);
      }
    }
    let anchor = null;
    for (const id of ids) {
      const el = document.getElementById(id);
      if (!el) continue;
      el.classList.add('playing');
      if (!anchor) anchor = el;
    }
    if (!anchor) {
      cursorEl.hidden = true;
      return;
    }
    placeCursor(anchor);
    if (follow) keepVisible(anchor, behavior || 'smooth');
  }

  let lastCursorSystem = null;

  function placeCursor(noteEl) {
    const system = noteEl.closest('.system') || noteEl;
    const origin = wrap.getBoundingClientRect();
    const note = noteEl.getBoundingClientRect();
    const sys = system.getBoundingClientRect();
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
    const viewport = window.innerHeight;
    const margin = Math.min(48, viewport * 0.08);
    if (rect.top >= margin && rect.bottom <= viewport - margin) return;
    window.scrollBy({ top: rect.top - Math.max(margin, viewport * 0.12), behavior: behavior });
  }

  // --- Seek by tapping a measure --------------------------------------------

  function measureAt(x, y) {
    let best = null;
    let bestDistance = Infinity;
    for (const system of Array.from(scoreEl.querySelectorAll('g.system'))) {
      const r = system.getBoundingClientRect();
      const distance = y < r.top ? r.top - y : (y > r.bottom ? y - r.bottom : 0);
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

  scoreEl.addEventListener('click', function (event) {
    if (!state.loaded || Date.now() < suppressClickUntil) return;
    const direct = event.target.closest ? event.target.closest('g.measure') : null;
    const measure = direct || measureAt(event.clientX, event.clientY);
    if (measure) host.onMeasureTapped(measure.id);
  });

  // --- Pinch zoom -------------------------------------------------------------
  // While pinching, the rendered page is scaled with a CSS transform for instant
  // feedback; on release the score is re-laid out at the new Verovio scale.

  let pinch = null;

  function touchDistance(touches) {
    return Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY);
  }

  document.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 2 || !state.loaded) return;
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

  // --- Resize (rotation, split screen) ----------------------------------------

  let resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      if (document.documentElement.clientWidth !== state.lastWidth || state.mode === 'page') relayout();
    }, 200);
  });

  // --- Helpers -----------------------------------------------------------------

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function messageOf(error) {
    return (error && error.message) ? error.message : String(error);
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
    return {
      onReady: record('onReady'),
      onScoreLoaded: record('onScoreLoaded'),
      onMeasureTapped: record('onMeasureTapped'),
      onZoomChanged: record('onZoomChanged'),
      onPageChanged: record('onPageChanged'),
      onError: record('onError'),
    };
  }

  window.grulla = {
    load: load,
    setLayout: setLayout,
    highlight: highlight,
    clearHighlight: clearHighlight,
    showPage: showPage,
    countIn: countIn,
  };
})();
