#!/usr/bin/env node
/*
 * Prints the paths (from a listing file, one per line) of the MusicXML / MXL files
 * that the app's Verovio build can render and play: they load, have at least one
 * page, and give a non-empty timemap.
 *
 *   node check_scores.js listing.txt > ok.txt
 *
 * Files are checked in child processes, a batch at a time, so that a file that
 * crashes Verovio only costs its batch, which is then retried file by file.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const VEROVIO = path.resolve(__dirname, '../../app/src/main/assets/verovio/verovio-toolkit-wasm.js');
const BATCH = 100;

if (process.argv[2] === '--child') {
  child(process.argv.slice(3));
} else {
  main(process.argv[2]);
}

function main(listing) {
  const files = fs.readFileSync(listing, 'utf8').split('\n').filter(Boolean);
  for (let i = 0; i < files.length; i += BATCH) {
    const batch = files.slice(i, i + BATCH);
    let ok = runChild(batch);
    if (ok === null) {
      ok = [];
      for (const file of batch) {
        const single = runChild([file]);
        if (single) ok.push(...single);
      }
    }
    for (const file of ok) process.stdout.write(file + '\n');
    process.stderr.write(`  checked ${Math.min(i + BATCH, files.length)}/${files.length}\n`);
  }
}

/** Paths that passed, or null if the child crashed. */
function runChild(files) {
  const result = spawnSync(process.execPath, [__filename, '--child', ...files], {
    encoding: 'utf8',
    timeout: 120000 + files.length * 20000,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.status !== 0) return null;
  return result.stdout.split('\n').filter(Boolean);
}

function child(files) {
  const verovio = require(VEROVIO);
  const run = () => {
    const tk = new verovio.toolkit();
    tk.setOptions({ pageWidth: 2100, scale: 40 });
    for (const file of files) {
      try {
        const data = fs.readFileSync(file);
        const loaded = data[0] === 0x50 && data[1] === 0x4b
          ? tk.loadZipDataBuffer(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength))
          : tk.loadData(data.toString('utf8'));
        if (!loaded || tk.getPageCount() < 1) continue;
        const timemap = tk.renderToTimemap({});
        if (!timemap.some((entry) => entry.on && entry.on.length)) continue;
        process.stdout.write(file + '\n');
      } catch (e) {
        // Not renderable: leave it out.
      }
    }
  };
  if (verovio.module.calledRun) run(); else verovio.module.onRuntimeInitialized = run;
}
