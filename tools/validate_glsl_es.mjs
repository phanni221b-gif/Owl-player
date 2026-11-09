// Standalone syntax/type check of every unchanged hook body, not a driver or libplacebo integration proof.
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const assets = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/neural');
export async function validate(compiler = 'glslangValidator') {
  const work = await fs.mkdtemp(path.join(os.tmpdir(),'owl-glsl-es-'));
  let count = 0;
  try {
    for (const channels of [8,16]) {
      const source = await fs.readFile(path.join(assets,'Owl-FSRCNNX-'+channels+'.glsl'),'utf8');
      const passes = source.split('//!HOOK MAIN').slice(1);
      for (const [index,pass] of passes.entries()) {
        const body = pass.split('\n').filter(line=>!line.startsWith('//!')).join('\n');
        const binds = [...pass.matchAll(/^\/\/!BIND (\w+)/gm)].map(m=>m[1]);
        for (const version of [300,310,320]) {
          let prefix = '#version '+version+' es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n';
          for (const [i,name] of binds.entries()) {
            prefix += 'uniform sampler2D t'+i+';\n';
            prefix += '#define '+name+'_pos vec2(0.5)\n#define '+name+'_size vec2(128.0)\n#define '+name+'_pt vec2(0.0078125)\n';
            prefix += '#define '+name+'_tex(pos) texture(t'+i+', pos)\n';
            prefix += '#define '+name+'_texOff(off) '+name+'_tex('+name+'_pos + vec2(off) * '+name+'_pt)\n';
          }
          const file = path.join(work,'hook.frag');
          await fs.writeFile(file,prefix+body+'\nlayout(location=0) out vec4 outputColor;\nvoid main() { outputColor = hook(); }\n');
          const run = spawnSync(compiler,['-S','frag',file],{encoding:'utf8',windowsHide:true});
          if (run.status !== 0) throw Error('FSRCNNX '+channels+' hook '+index+' ES '+version+': '+(run.stdout||'')+(run.stderr||'')+(run.error||''));
          count++;
        }
      }
    }
  } finally { await fs.rm(work,{recursive:true,force:true}); }
  return count;
}
