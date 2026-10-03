# Third-party native code and models in the Enterprise bundle

**Scope.** The native libraries and the model file that the Enterprise bundle carries inside
`inspecto-intelligence.jar` (the shaded sidecar, `ASSURE-INTELLIGENCE-BUNDLE-1`, 2026-09-29). Personal and
Professional bundles do not stage that jar, so none of this ships there.

**Why this file exists.** The SBOM (`tools/sbom.mjs`, see [release-verification.md](release-verification.md))
records **one licence per Maven artifact**, read from its POM. Four of the artifacts below embed native
binaries from *other* projects that carry their own licence terms. A per-artifact licence cannot express that,
so those terms are recorded here instead.

| Shipped inside the sidecar | Comes from (Maven artifact) | Licence | How it was established |
|---|---|---|---|
| `ai/onnxruntime/native/*/` — `onnxruntime` + `onnxruntime4j_jni` for win-x64, linux-x64/aarch64, osx-x64/aarch64 | `com.microsoft.onnxruntime:onnxruntime:1.20.0` | MIT | the artifact's POM; the artifact's own `ThirdPartyNotices.txt` (345 KB, covering the libraries statically linked into the natives) is **retained at the sidecar root** |
| `native/lib/*/cpu/tokenizers` (Hugging Face tokenizers, Rust) | `ai.djl.huggingface:tokenizers:0.36.0` | Apache-2.0 | the artifact's POM |
| `native/lib/win-x86_64/cpu/libstdc++-6.dll`, `libgcc_s_seh-1.dll` (GCC runtime, win-x64 only) | the same `tokenizers` artifact | **GPL-3.0-or-later WITH GCC-exception-3.1** (the GCC Runtime Library Exception) | the file names identify them as the MinGW-w64 GCC runtime. The artifact ships no licence text for them. The exception permits distributing them with a program that was compiled by GCC (here, `tokenizers.dll`) without GPL obligations on that program. The GPL-3.0 and exception texts ship in the bundle's `licenses/` dir (see below). |
| `native/lib/win-x86_64/cpu/libwinpthread-1.dll` (win-x64 only) | the same `tokenizers` artifact | MIT-style (mingw-w64 winpthreads `COPYING`) | the file name identifies the library; no licence text ships in the artifact, so the `COPYING` text ships in the bundle's `licenses/` dir |
| `com/sun/jna/*/jnidispatch` (JNA natives) | `net.java.dev.jna:jna:5.17.0` | LGPL-2.1-or-later **or** Apache-2.0 (dual; the recipient may choose) | the artifact's POM and its `META-INF/LICENSE`. ⚠ In the sidecar that file name collides with others (the sidecar keeps one `META-INF/LICENSE`), so JNA's own copy is **not reliably retained** |
| `all-minilm-l6-v2.onnx` (90 MB sentence-embedding model) and its tokenizer | `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.16.3-beta26` | Apache-2.0 (the jar); the model is `sentence-transformers/all-MiniLM-L6-v2`, Apache-2.0 | the jar's POM; the model's licence comes from its upstream model card and **could not be checked offline** |

**Extraction at run time.** None of these libraries is downloaded. Each is extracted from the jar the first time
it is used. In a bundle, the launchers point every extraction directory at `./runtime-natives/`
(owner-only) and set `-Dai.djl.offline=true` (see `inspecto/package.ps1`). Without that pin, onnxruntime
leaves one `onnxruntime-java*` directory in `%TEMP%` per JVM start, and it never removes them on Windows.

**Licence texts (`NATIVE-LICENCE-TEXTS-1`, shipped 2026-10-03).** The texts live in
[`compliance/third-party-licenses/`](../third-party-licenses/natives.json): `GPL-3.0.txt`,
`GCC-exception-3.1.txt` and `mingw-w64-winpthreads-COPYING.txt`. `natives.json` there classifies every DLL
inside the sidecar and pins the sha256 of each text. They were copied byte for byte, offline, from the MSYS2
package licence directories installed with the Windows Git client (`mingw64/share/licenses/`):
- `GPL-3.0.txt` is the current gnu.org `gpl-3.0.txt` (sha256 `3972dc97…6986`, the https revision), from `xz/COPYING.GPLv3`.
- `GCC-exception-3.1.txt` is GCC's `COPYING.RUNTIME`, from `gcc-libs/`.
- `mingw-w64-winpthreads-COPYING.txt` is the winpthreads `COPYING`, from `libwinpthread/`.

`inspecto/package.ps1` copies the directory to `<bundle>/licenses/`, next to `inspecto-intelligence.jar`, when it
stages the sidecar. It then runs `tools/check-native-licences.mjs --bundle`, which fails the package if a DLL in
the jar is not classified, an `inJar` notice is missing, or a required text is absent. CI runs the same guard
without `--bundle`, which checks the text hashes and the package.ps1 wiring.

**Open (owed before an external licence review; tracked as `NATIVE-LICENCE-SHADE-MERGE-1`):**
1. Make the shade step merge `META-INF/LICENSE*` / `NOTICE*` (for example with an
   `ApacheNoticeResourceTransformer` plus per-artifact renames), so JNA's licence and every other
   colliding licence survive shading.
2. Trim the non-target-platform natives if the size matters (macOS and aarch64 are about 90 MB uncompressed).
