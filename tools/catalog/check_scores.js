#!/usr/bin/env node
/*
 * Prints the paths (from a listing file, one per line) of the MusicXML / MXL files
 * that the app's Verovio build can render and play: they load, have at least one
 * page, and give a non-empty timemap.
 *
 *   node check_scores.js listing.txt > ok.txt
 *
 * Files are checked in child processes (one per CPU), a batch at a time, so that a
 * file that crashes Verovio only costs its batch, which is then retried file by file.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

const VEROVIO = path.resolve(__dirname, '../../app/src/main/assets/verovio/verovio-toolkit-wasm.js');
const BATCH = 100;

if (process.argv[2] === '--child') {
  child(process.argv.slice(3));
} else {
  main(process.argv[2]);
}

async function main(listing) {
  const files = fs.readFileSync(listing, 'utf8').split('\n').filter(Boolean);
  const batches = [];
  for (let i = 0; i < files.length; i += BATCH) batches.push(files.slice(i, i + BATCH));
  let next = 0;
  let done = 0;
  const worker = async () => {
    while (next < batches.length) {
      const batch = batches[next++];
      let ok = await runChild(batch);
      if (ok === null) {
        ok = [];
        for (const file of batch) {
          const single = await runChild([file]);
          if (single) ok.push(...single);
        }
      }
      for (const file of ok) process.stdout.write(file + '\n');
      done += batch.length;
      process.stderr.write(`  checked ${done}/${files.length}\n`);
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, os.cpus().length) }, worker));
}

/** Paths that passed, or null if the child crashed or timed out. */
function runChild(files) {
  return new Promise((resolve) => {
    const proc = spawn(process.execPath, [__filename, '--child', ...files], { stdio: ['ignore', 'pipe', 'ignore'] });
    let out = '';
    proc.stdout.on('data', (chunk) => { out += chunk; });
    const timer = setTimeout(() => proc.kill('SIGKILL'), 60000 + files.length * 15000);
    proc.on('close', (code) => {
      clearTimeout(timer);
      resolve(code === 0 ? out.split('\n').filter(Boolean) : null);
    });
  });
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
