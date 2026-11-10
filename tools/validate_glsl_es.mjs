// Standalone syntax/type check of every Owl hook body with the texture macros that the pinned
// libplacebo (c4de47cd src/shaders/custom_mpv.c bind_pass_tex) generates: NAME_tex is
// `mul * vec4(textureLod(raw, pos, 0.0))` and HOOKED_* are object-like aliases. Passes that bind
// HOOKED are also compiled with HOOKED as a samplerExternalOES (direct MediaCodec), where
// GL_OES_EGL_image_external_essl3 provides texture() but not textureLod(). This reproduces the
// Fold8 "no matching overloaded function" failure of diagnostic.5; it is not a driver proof.
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const assets = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/neural');

export function prelude(version, binds, externalHooked) {
  let text = '#version ' + version + ' es\n';
  if (externalHooked) text += '#extension GL_OES_EGL_image_external_essl3 : require\n';
  text += 'precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n';
  binds.forEach((name, i) => {
    const real = name === 'HOOKED' ? 'MAINPRESUB' : name;
    const sampler = name === 'HOOKED' && externalHooked ? 'samplerExternalOES' : 'sampler2D';
    text += `uniform highp ${sampler} t${i};\nin vec2 p${i};\nuniform vec2 pt${i};\nconst float m${i} = float(1);\n`;
    text += `#define ${real}_raw t${i}\n#define ${real}_pos p${i}\n#define ${real}_size vec2(textureSize(t${i}, 0))\n`;
    text += `#define ${real}_pt pt${i}\n#define ${real}_mul m${i}\n`;
    text += `#define ${real}_tex(pos) (m${i} * vec4(textureLod(t${i}, pos, 0.0)))\n`;
    text += `#define ${real}_texOff(off) (${real}_tex(p${i} + pt${i} * vec2(off)))\n`;
    if (name === 'HOOKED')
      for (const s of ['raw', 'pos', 'size', 'pt', 'mul', 'tex', 'texOff']) text += `#define HOOKED_${s} MAINPRESUB_${s}\n`;
  });
  return text;
}

export async function compile(source, compiler = 'glslangValidator') {
  const work = await fs.mkdtemp(path.join(os.tmpdir(), 'owl-glsl-es-'));
  try {
    const file = path.join(work, 'hook.frag');
    await fs.writeFile(file, source);
    return spawnSync(compiler, ['-S', 'frag', file], { encoding: 'utf8', windowsHide: true });
  } finally { await fs.rm(work, { recursive: true, force: true }); }
}

export async function validate(compiler = 'glslangValidator') {
  let count = 0;
  for (const channels of [8, 16]) {
    const source = await fs.readFile(path.join(assets, 'Owl-FSRCNNX-' + channels + '.glsl'), 'utf8');
    const passes = source.split('//!HOOK MAIN').slice(1);
    for (const [index, pass] of passes.entries()) {
      const body = pass.split('\n').filter(line => !line.startsWith('//!')).join('\n');
      const binds = [...pass.matchAll(/^\/\/!BIND (\w+)/gm)].map(m => m[1]);
      for (const version of [300, 310, 320]) {
        for (const external of binds.includes('HOOKED') ? [false, true] : [false]) {
          const run = await compile(prelude(version, binds, external) + body +
            '\nout vec4 outputColor;\nvoid main() { outputColor = hook(); }\n', compiler);
          if (run.status !== 0) throw Error('FSRCNNX ' + channels + ' hook ' + index + ' ES ' + version +
            (external ? ' external HOOKED' : '') + ': ' + (run.stdout || '') + (run.stderr || '') + (run.error || ''));
          count++;
        }
      }
    }
  }
  return count;
}
