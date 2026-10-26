/*
 * Score view running inside the app's WebView. It renders MusicXML with Verovio
 * and shows playback guidance. Playback timing lives on the Kotlin side, which
 * calls window.grulla.* when the set of sounding notes changes.
 *
 * Kotlin -> JS (window.grulla):
 *   load(url, layout)        fetch MusicXML from url, render it, report onScoreLoaded
 *   setLayout(layout)        {scale, mode: 'page' | 'continuous'}; re-lays out the score
 *   highlight(ids, follow, kind)  mark notes ('play': sounding now, 'listen': to play next),
 *                            move the cursor, keep the system visible
 *   clearHighlight()
 *   showPage(n)              page mode only
 *   countIn(beat, total)     show the count-in beat (beat 0 hides it)
 *   setLoop(firstId, lastId) shade the measures of the A–B loop (null clears it)
 *   thumbnail(width)         render the top of page 1 to a PNG, reported via onThumbnail
 *   setTheme(name)           'day' or 'night'
 *
 * JS -> Kotlin (window.GrullaHost, an @JavascriptInterface object):
 *   onReady(verovioVersion)
 *   onScoreLoaded(json)      {"pageCount": n, "timemap": [...], "midi": "<base64>", "source": url,
 *                             "pitches": {noteId: midiPitch}}
 *   onMeasureTapped(measureId)
 *   onMeasureLongPressed(measureId)
 *   onZoomChanged(scale)
 *   onPageChanged(page, pageCount)
 *   onThumbnail(pngDataUrl)
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
  const loopLayer = document.createElement('div');
  loopLayer.id = 'loop-layer';
  wrap.insertBefore(loopLayer, cursorEl); // Above the score, below the cursor.

  const state = {
    tk: null,
    loaded: false,
    scale: 40,
    mode: 'continuous',
    page: 1,
    pageCount: 0,
    highlighted: [],
    highlightKind: 'play',
    loop: null, // {first, last} measure ids
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
    paintLoop();
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
      state.loop = null;
      const timemap = tk.renderToTimemap({ includeMeasures: true, includeRests: true });
      const midi = tk.renderToMIDI();
      const pitches = notePitches(tk, timemap);
      render();
      window.scrollTo(0, 0);
      host.onScoreLoaded(JSON.stringify({
        pageCount: state.pageCount, timemap: timemap, midi: midi, source: url, pitches: pitches,
      }));
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

  function highlight(ids, follow, kind) {
    state.highlighted = Array.isArray(ids) ? ids : [];
    state.highlightKind = kind === 'listen' ? 'listen' : 'play';
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
    const target = clamp(page, 1, state.pageCount);
    if (target === state.page) return;
    state.page = target;
    render();
    window.scrollTo(0, 0);
  }

  function setLoop(firstId, lastId) {
    state.loop = firstId ? { first: firstId, last: lastId || firstId } : null;
    paintLoop();
  }

  /** MIDI pitch of every note in the timemap (for "follow me" listening). */
  function notePitches(tk, timemap) {
    const pitches = {};
    for (const entry of timemap) {
      for (const id of entry.on || []) {
        if (id in pitches) continue;
        const values = tk.getMIDIValuesForElement(id);
        if (values && typeof values.pitch === 'number') pitches[id] = values.pitch;
      }
    }
    return pitches;
  }

  /** Draws the top of the first page (at most 1.4 × width tall) and reports it as a PNG data URL. */
  function thumbnail(width) {
    const svg = scoreEl.querySelector('.page svg');
    if (!state.loaded || !svg) return;
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
      el.classList.add(cssClass);
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
    const viewport = window.innerHeight;
    const margin = Math.min(48, viewport * 0.08);
    if (rect.top >= margin && rect.bottom <= viewport - margin) return;
    window.scrollBy({ top: rect.top - Math.max(margin, viewport * 0.12), behavior: behavior });
  }

  // --- A–B loop shading ---------------------------------------------------------
  // One band per system, spanning the loop's measures on it (in notation order).

  function paintLoop() {
    loopLayer.innerHTML = '';
    if (!state.loaded || !state.loop) return;
    const measures = Array.from(scoreEl.querySelectorAll('g.measure'));
    let a = measures.findIndex((m) => m.id === state.loop.first);
    let b = measures.findIndex((m) => m.id === state.loop.last);
    if (a < 0 && b < 0) return; // Not on this page.
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
      el.className = 'loop-band' + (index === 0 ? ' loop-start' : '') + (index === list.length - 1 ? ' loop-end' : '');
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

  function measureFor(target, x, y) {
    const direct = target && target.closest ? target.closest('g.measure') : null;
    return direct || measureAt(x, y);
  }

  scoreEl.addEventListener('click', function (event) {
    if (!state.loaded || Date.now() < suppressClickUntil) return;
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
    if (event.touches.length !== 1 || !state.loaded) {
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
    if (!state.loaded || event.pointerType === 'touch' || Date.now() < suppressClickUntil) return;
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
    if (event.touches.length !== 2 || !state.loaded) return;
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
      onMeasureLongPressed: record('onMeasureLongPressed'),
      onZoomChanged: record('onZoomChanged'),
      onPageChanged: record('onPageChanged'),
      onThumbnail: record('onThumbnail'),
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
    setLoop: setLoop,
    thumbnail: thumbnail,
    setTheme: setTheme,
  };
})();
