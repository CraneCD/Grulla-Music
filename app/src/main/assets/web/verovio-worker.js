/*
 * Verovio calls used by score.js. Normally runs in a Web Worker, so laying out
 * a large score never freezes the page: score.js joins the Verovio toolkit and
 * this file into one Blob and starts it as a worker. If workers are unavailable
 * the same methods run on the page (score.js loads both files as scripts).
 *
 * Messages in:  {id, method, args}
 * Messages out: {type: 'ready', version} once, then {type: 'result', id, result | error}
 */
'use strict';

(function (scope) {
  /** The methods score.js can call, bound to one Verovio toolkit. */
  function createMethods(tk) {
    return {
      version: function () {
        return tk.getVersion();
      },

      /** Sets the options, reads the MusicXML and lays it out; returns the page count. */
      load: function (data, options) {
        tk.resetOptions();
        tk.setOptions(options);
        if (!tk.loadData(data)) throw new Error('Verovio could not read this file as MusicXML.');
        return tk.getPageCount();
      },

      /** Lays out the loaded score again with new options; returns the page count. */
      layout: function (options) {
        tk.setOptions(options);
        tk.redoLayout();
        return tk.getPageCount();
      },

      page: function (n) {
        return tk.renderToSVG(n);
      },

      /** Timemap, MIDI (base64) and the MIDI pitch of every note in the timemap. */
      playbackData: function () {
        const timemap = tk.renderToTimemap({ includeMeasures: true, includeRests: true });
        const midi = tk.renderToMIDI();
        const pitches = {};
        for (const entry of timemap) {
          for (const id of entry.on || []) {
            if (id in pitches) continue;
            const values = tk.getMIDIValuesForElement(id);
            if (values && typeof values.pitch === 'number') pitches[id] = values.pitch;
          }
        }
        return { timemap: timemap, midi: midi, pitches: pitches };
      },
    };
  }

  scope.GrullaVerovio = { createMethods: createMethods };

  const inWorker = typeof WorkerGlobalScope !== 'undefined' && scope instanceof WorkerGlobalScope;
  if (!inWorker) return;

  let methods = null;
  const waiting = [];

  function handle(message) {
    try {
      const fn = methods[message.method];
      if (!fn) throw new Error('Unknown method ' + message.method);
      scope.postMessage({ type: 'result', id: message.id, result: fn.apply(null, message.args || []) });
    } catch (e) {
      scope.postMessage({ type: 'result', id: message.id, error: (e && e.message) ? e.message : String(e) });
    }
  }

  function start() {
    try {
      methods = createMethods(new scope.verovio.toolkit());
      scope.postMessage({ type: 'ready', version: methods.version() });
    } catch (e) {
      scope.postMessage({ type: 'failed', error: (e && e.message) ? e.message : String(e) });
      return;
    }
    waiting.splice(0).forEach(handle);
  }

  scope.onmessage = function (event) {
    if (methods) handle(event.data); else waiting.push(event.data);
  };

  if (scope.verovio.module.calledRun) {
    start();
  } else {
    scope.verovio.module.onRuntimeInitialized = start;
  }
})(self);
