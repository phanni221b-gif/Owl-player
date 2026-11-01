#!/usr/bin/env python3
"""Desktop Mesa GLES smoke only: not an Android/Fold GPU performance certification."""
import json, os, pathlib, subprocess, tempfile
from PIL import Image, ImageChops, ImageStat
ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/neural'
with tempfile.TemporaryDirectory(prefix='owl-gles-') as directory:
    work = pathlib.Path(directory)
    fixture = work / 'fixture.mkv'
    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','smptebars=size=160x90:rate=12','-t','3','-c:v','ffv1',str(fixture)],check=True)
    images = []
    for channels in (0,8,16):
        output = work / f'{channels}.png'
        result = work / f'{channels}.json'
        lua = work / 'inspect.lua'
        marker = f'Owl FSRCNNX {channels} RGB reconstruction' if channels else ''
        lua.write_text('''local mp = require 'mp'
local utils = require 'mp.utils'
local attempts = 0
local function inspect()
    attempts = attempts + 1
    local passes = mp.get_property_native('vo-passes') or {}
    local fresh = passes.fresh or {}
    local descriptions = {}
    local matched = MARKER == '' and #fresh > 0
    for _, pass in ipairs(fresh) do
        table.insert(descriptions, tostring(pass.desc or ''))
        if string.find(pass.desc or '', MARKER, 1, true) then matched = true end
    end
    if matched then
        mp.commandv('screenshot-to-file', OUTPUT, 'window')
        local file = assert(io.open(RESULT, 'w'))
        local encoded, failure = utils.format_json({descriptions=descriptions, version=mp.get_property('mpv-version') or 'unknown', hwdec=mp.get_property('hwdec-current') or 'no'})
        if not encoded then file:close(); mp.msg.error(failure or 'JSON encoding failed'); mp.commandv('quit', '2'); return end
        file:write(encoded)
        file:close()
        mp.commandv('quit', '0')
    elseif attempts > 80 then
        mp.msg.error('Expected CNN reconstruction pass not observed')
        mp.commandv('quit', '2')
    else mp.add_timeout(0.25, inspect) end
end
mp.register_event('file-loaded', function() mp.get_property_native('vo-passes'); mp.add_timeout(0.5, inspect) end)
'''.replace('MARKER',json.dumps(marker)).replace('OUTPUT',json.dumps(str(output))).replace('RESULT',json.dumps(str(result))))
        cmd = ['mpv','--no-config','--vo=gpu-next','--gpu-api=opengl','--gpu-context=x11egl','--opengl-es=yes',
            '--gpu-sw=yes','--term-status-msg=','--osc=no','--osd-level=0','--osd-bar=no','--hwdec=no','--no-audio','--loop-file=inf','--geometry=960x540','--scale=ewa_lanczossharp',
            '--dscale=mitchell','--fbo-format=rgba16f','--script='+str(lua),'--log-file='+str(work/f'{channels}.log')]
        if channels: cmd.append('--glsl-shaders='+str(ASSETS/f'Owl-FSRCNNX-{channels}.glsl'))
        subprocess.run(cmd+[str(fixture)],check=True,timeout=90,env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'1'})
        data = json.loads(result.read_text())
        descriptions = ' '.join(data['descriptions'])
        if channels:
            assert marker in descriptions
            assert f'Owl FSRCNNX {24-channels} RGB reconstruction' not in descriptions
        im = Image.open(output).convert('RGB'); images.append(im)
        assert im.size == (960,540), 'screenshot must capture scaled renderer output'
        colors = ImageStat.Stat(im).mean
        assert min(colors)>20 and max(colors)<240, 'blank/invalid image'
        assert ImageStat.Stat(ImageChops.difference(im.getchannel('R'),im.getchannel('G'))).mean[0]>10, 'RGB reconstruction lost chroma'
        print(f'Mesa GLES model {channels}: actual render confirmed; {data["version"]}; hwdec={data["hwdec"]}')
    baseline_delta = sum(ImageStat.Stat(ImageChops.difference(images[0],images[1])).mean) / 3
    model_delta = sum(ImageStat.Stat(ImageChops.difference(images[1],images[2])).mean) / 3
    print(f'Rendered RGB mean absolute difference: base/neural={baseline_delta}, neural/max={model_delta}')
    assert baseline_delta>0.001
    assert model_delta>0.001
print('Neural/AI Max compile, mutually exclusive execution, colored output, distinct CNN results: PASS')
