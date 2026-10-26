/*
 * Pencil marks, fingerings and breath marks drawn over the score (see score.js).
 *
 * Marks are anchored to the notation, not to the screen, so they stay in place
 * when the score is zoomed, re-flowed or turned: each mark keeps
 *   m  the measure's index in the score (Verovio's ids change on every load),
 *   s  the staff's index in that measure,
 *   pts points as [u, v]: u runs from the first note of the measure (0) to its
 *      barline (1); v is in staff spaces below the staff's top line.
 *
 * Kotlin -> JS (window.grulla):
 *   setAnnotations(json)   the saved marks ({"marks": [...]}), drawn at once
 *   annotate(tool, text)   'pen' | 'text' | 'breath' | 'erase' to draw, null to stop
 *   undoAnnotation()
 * JS -> Kotlin: onAnnotationsChanged(json) after every change.
 */
'use strict';

(function () {
  const score = window.grullaScore;
  const host = score.host;
  const SVG_NS = 'http://www.w3.org/2000/svg';

  const layer = document.createElementNS(SVG_NS, 'svg');
  layer.id = 'annotations';
  score.wrap.appendChild(layer);

  const capture = document.createElement('div');
  capture.id = 'annotate-capture';
  capture.hidden = true;
  score.wrap.appendChild(capture);

  const state = {
    marks: [],
    tool: null,
    text: '1',
    /** Measure index of the first measure of each page (from the page SVGs). */
    pageOffsets: [],
    offsetsFor: null,
  };

  // --- Anchors ---------------------------------------------------------------------

  /** Index of the first measure on each page, counted from the stored SVGs. */
  function pageOffsets() {
    if (state.offsetsFor === score.state.svgs) return state.pageOffsets;
    const offsets = [];
    let total = 0;
    for (const svg of score.state.svgs) {
      offsets.push(total);
      total += (svg.match(/class="measure"/g) || []).length;
    }
    state.pageOffsets = offsets;
    state.offsetsFor = score.state.svgs;
    return offsets;
  }

  /** The measures on screen, keyed by their index in the score. */
  function measuresOnScreen() {
    const offsets = pageOffsets();
    const result = new Map();
    for (const page of Array.from(score.scoreEl.querySelectorAll('.page'))) {
      const offset = offsets[Number(page.dataset.page) - 1] || 0;
      Array.from(page.querySelectorAll('g.measure')).forEach(function (m, i) { result.set(offset + i, m); });
    }
    return result;
  }

  /** Screen frame of staff [s] in measure element [measure]: origin, width and staff space. */
  function frameOf(measure, s) {
    const staves = Array.from(measure.children).filter(function (el) { return el.classList && el.classList.contains('staff'); });
    const staff = staves[s];
    if (!staff) return null;
    const lines = Array.from(staff.children).filter(function (el) { return el.tagName === 'path'; });
    if (lines.length < 2) return null;
    const top = lines[0].getBoundingClientRect();
    const bottom = lines[lines.length - 1].getBoundingClientRect();
    const space = (bottom.top - top.top) / (lines.length - 1);
    const right = top.right;
    let left = Infinity;
    for (const el of Array.from(measure.querySelectorAll('.note, .rest, .mRest, .chord'))) {
      left = Math.min(left, el.getBoundingClientRect().left);
    }
    if (!isFinite(left) || left >= right - 4) left = top.left;
    const origin = score.wrap.getBoundingClientRect();
    return { x: left - origin.left, y: top.top - origin.top, width: Math.max(8, right - left), space: Math.max(1, space) };
  }

  function toScreen(frame, p) {
    return [frame.x + p[0] * frame.width, frame.y + p[1] * frame.space];
  }

  function fromScreen(frame, x, y) {
    return [round((x - frame.x) / frame.width), round((y - frame.y) / frame.space)];
  }

  function round(n) {
    return Math.round(n * 1000) / 1000;
  }

  /** The measure and staff nearest to a point (wrap coordinates). */
  function anchorAt(x, y) {
    const origin = score.wrap.getBoundingClientRect();
    let best = null;
    let bestDistance = Infinity;
    measuresOnScreen().forEach(function (measure, index) {
      const r = measure.getBoundingClientRect();
      const left = r.left - origin.left;
      const right = r.right - origin.left;
      if (x < left - 24 || x > right + 24) return;
      const staves = Array.from(measure.children).filter(function (el) { return el.classList && el.classList.contains('staff'); });
      staves.forEach(function (_, s) {
        const frame = frameOf(measure, s);
        if (!frame) return;
        const middle = frame.y + 2 * frame.space;
        const dx = x < left ? left - x : (x > right ? x - right : 0);
        const distance = Math.abs(y - middle) + dx * 4;
        if (distance < bestDistance) {
          bestDistance = distance;
          best = { m: index, s: s, frame: frame };
        }
      });
    });
    return best;
  }

  // --- Drawing ---------------------------------------------------------------------------

  function draw() {
    while (layer.firstChild) layer.removeChild(layer.firstChild);
    if (!score.state.loaded || state.marks.length === 0) return;
    const measures = measuresOnScreen();
    for (const mark of state.marks) {
      const measure = measures.get(mark.m);
      if (!measure) continue;
      const frame = frameOf(measure, mark.s);
      if (!frame) continue;
      const el = mark.kind === 'pen' ? penPath(mark, frame) : textMark(mark, frame);
      if (el) layer.appendChild(el);
    }
  }

  function penPath(mark, frame) {
    if (!mark.pts || mark.pts.length === 0) return null;
    const pts = mark.pts.map(function (p) { return toScreen(frame, p); });
    let d = 'M' + pts[0][0].toFixed(1) + ' ' + pts[0][1].toFixed(1);
    if (pts.length === 1) d += 'l0.1 0';
    for (let i = 1; i < pts.length; i++) d += 'L' + pts[i][0].toFixed(1) + ' ' + pts[i][1].toFixed(1);
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'mark-pen');
    path.setAttribute('stroke-width', String(Math.max(1.5, frame.space * 0.28)));
    return path;
  }

  function textMark(mark, frame) {
    const p = toScreen(frame, mark.pts[0]);
    const text = document.createElementNS(SVG_NS, 'text');
    text.setAttribute('x', p[0].toFixed(1));
    text.setAttribute('y', p[1].toFixed(1));
    text.setAttribute('class', mark.kind === 'breath' ? 'mark-breath' : 'mark-text');
    text.setAttribute('font-size', String(frame.space * (mark.kind === 'breath' ? 3.2 : 2.0)));
    text.textContent = mark.kind === 'breath' ? '’' : (mark.text || '');
    return text;
  }

  score.afterRender.push(draw);

  // --- Editing ----------------------------------------------------------------------------

  const pointers = new Map();
  let stroke = null; // {anchor, points (wrap coordinates), el}
  let pan = null;

  function wrapPoint(event) {
    const origin = score.wrap.getBoundingClientRect();
    return [event.clientX - origin.left, event.clientY - origin.top];
  }

  capture.addEventListener('pointerdown', function (event) {
    if (!state.tool) return;
    capture.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.size === 2) {
      // Two fingers scroll the score; the stroke begun by the first finger is dropped.
      cancelStroke();
      pan = averageY();
      return;
    }
    if (pointers.size > 2) return;
    const p = wrapPoint(event);
    if (state.tool === 'pen') {
      const anchor = anchorAt(p[0], p[1]);
      if (!anchor) return;
      const el = document.createElementNS(SVG_NS, 'path');
      el.setAttribute('class', 'mark-pen');
      el.setAttribute('stroke-width', String(Math.max(1.5, anchor.frame.space * 0.28)));
      layer.appendChild(el);
      stroke = { anchor: anchor, points: [p], el: el };
      updateStroke();
    }
    event.preventDefault();
  });

  capture.addEventListener('pointermove', function (event) {
    if (!pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pan !== null && pointers.size >= 2) {
      const y = averageY();
      window.scrollBy(0, pan - y);
      pan = y;
      return;
    }
    if (stroke) {
      const p = wrapPoint(event);
      const last = stroke.points[stroke.points.length - 1];
      if (Math.hypot(p[0] - last[0], p[1] - last[1]) >= 1.5) {
        stroke.points.push(p);
        updateStroke();
      }
    }
    event.preventDefault();
  });

  function endPointer(event) {
    if (!pointers.has(event.pointerId)) return;
    const wasPanning = pan !== null;
    pointers.delete(event.pointerId);
    if (pointers.size < 2) pan = null;
    if (wasPanning || pointers.size > 0) return;
    const p = wrapPoint(event);
    if (stroke) {
      finishStroke();
    } else if (event.type === 'pointerup') {
      tap(p[0], p[1]);
    }
  }

  capture.addEventListener('pointerup', endPointer);
  capture.addEventListener('pointercancel', function (event) {
    cancelStroke();
    endPointer(event);
  });

  function averageY() {
    let sum = 0;
    pointers.forEach(function (p) { sum += p.y; });
    return sum / pointers.size;
  }

  function updateStroke() {
    const pts = stroke.points;
    let d = 'M' + pts[0][0].toFixed(1) + ' ' + pts[0][1].toFixed(1) + (pts.length === 1 ? 'l0.1 0' : '');
    for (let i = 1; i < pts.length; i++) d += 'L' + pts[i][0].toFixed(1) + ' ' + pts[i][1].toFixed(1);
    stroke.el.setAttribute('d', d);
  }

  function cancelStroke() {
    if (stroke && stroke.el.parentNode) stroke.el.parentNode.removeChild(stroke.el);
    stroke = null;
  }

  function finishStroke() {
    const s = stroke;
    stroke = null;
    const frame = s.anchor.frame;
    const pts = simplify(s.points).map(function (p) { return fromScreen(frame, p[0], p[1]); });
    add({ kind: 'pen', m: s.anchor.m, s: s.anchor.s, pts: pts });
  }

  /** Keeps every point at least 2px from the previous one. */
  function simplify(points) {
    const out = [points[0]];
    for (let i = 1; i < points.length; i++) {
      const last = out[out.length - 1];
      if (Math.hypot(points[i][0] - last[0], points[i][1] - last[1]) >= 2 || i === points.length - 1) out.push(points[i]);
    }
    return out;
  }

  function tap(x, y) {
    if (state.tool === 'erase') {
      erase(x, y);
      return;
    }
    if (state.tool !== 'text' && state.tool !== 'breath') return;
    const anchor = anchorAt(x, y);
    if (!anchor) return;
    const mark = { kind: state.tool, m: anchor.m, s: anchor.s, pts: [fromScreen(anchor.frame, x, y)] };
    if (state.tool === 'text') mark.text = state.text;
    add(mark);
  }

  /** Removes the mark closest to the point, within about a finger's width. */
  function erase(x, y) {
    const measures = measuresOnScreen();
    let best = -1;
    let bestDistance = 24;
    state.marks.forEach(function (mark, i) {
      const measure = measures.get(mark.m);
      const frame = measure && frameOf(measure, mark.s);
      if (!frame) return;
      for (const p of mark.pts) {
        const q = toScreen(frame, p);
        const distance = Math.hypot(q[0] - x, q[1] - y) - (mark.kind === 'pen' ? 0 : frame.space);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = i;
        }
      }
    });
    if (best < 0) return;
    history.push(state.marks.slice());
    state.marks.splice(best, 1);
    changed();
  }

  const history = [];

  function add(mark) {
    history.push(state.marks.slice());
    state.marks.push(mark);
    changed();
  }

  function changed() {
    if (history.length > 100) history.shift();
    draw();
    host.onAnnotationsChanged(JSON.stringify({ marks: state.marks }));
  }

  // --- API -------------------------------------------------------------------------------

  window.grulla.setAnnotations = function (json) {
    let marks = [];
    try {
      const parsed = typeof json === 'string' ? JSON.parse(json) : json;
      marks = parsed && Array.isArray(parsed.marks) ? parsed.marks.filter(valid) : [];
    } catch (e) {
      marks = [];
    }
    state.marks = marks;
    history.length = 0;
    draw();
  };

  window.grulla.annotate = function (tool, text) {
    state.tool = ['pen', 'text', 'breath', 'erase'].indexOf(tool) >= 0 ? tool : null;
    if (typeof text === 'string' && text) state.text = text;
    capture.hidden = !state.tool;
    capture.dataset.tool = state.tool || '';
    score.gestures.enabled = !state.tool;
    if (!state.tool) {
      cancelStroke();
      pointers.clear();
      pan = null;
    }
  };

  window.grulla.undoAnnotation = function () {
    if (history.length === 0) return;
    state.marks = history.pop();
    draw();
    host.onAnnotationsChanged(JSON.stringify({ marks: state.marks }));
  };

  function valid(mark) {
    return mark && typeof mark.m === 'number' && typeof mark.s === 'number' && Array.isArray(mark.pts) && mark.pts.length > 0 &&
      ['pen', 'text', 'breath'].indexOf(mark.kind) >= 0;
  }
})();
