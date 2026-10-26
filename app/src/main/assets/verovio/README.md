# Verovio (bundled, unmodified)

| | |
|---|---|
| Library | Verovio JavaScript toolkit (WebAssembly build) |
| Version | **6.3.0** (`getVersion()` reports `6.3.0-425dd7b`) |
| File | `verovio-toolkit-wasm.js`: the WASM binary is embedded, no separate `.wasm` file |
| Source of this file | npm package `verovio@6.3.0`, `dist/verovio-toolkit-wasm.js` |
| npm tarball SHA-1 | `a11cce5a4126a5ed1e11418f1a28614b36705c94` |
| File SHA-256 | `d794119cd5ea83a3e835849945f0289c91ac2bac427f4aa312c7010a800b46bd` |
| License | GNU LGPL v3.0 or later (`COPYING.LESSER`, which builds on the GPL v3 in `COPYING`) |
| Corresponding source | https://github.com/rism-digital/verovio/tree/version-6.3.0 |
| Authors | Laurent Pugin and the Verovio contributors, https://www.verovio.org |

The app loads this file as a separate asset and does not modify it. You can swap in
another Verovio build (for example a newer release) by replacing it. The app's own
code talks to Verovio only through its public JavaScript API, from `web/score.js`.

To update it:

```sh
npm pack verovio@<version>
tar xzf verovio-<version>.tgz package/dist/verovio-toolkit-wasm.js
cp package/dist/verovio-toolkit-wasm.js app/src/main/assets/verovio/
```

Then update the version and checksums in this file, in `THIRD_PARTY_NOTICES.md`,
and in the test fixtures (see `README.md`).
