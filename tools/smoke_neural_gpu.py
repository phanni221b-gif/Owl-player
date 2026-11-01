#!/usr/bin/env python3
"""Desktop Mesa GLES smoke only: not an Android/Fold GPU performance certification."""
import json, os, pathlib, subprocess, time
from PIL import Image, ImageChops, ImageStat
ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/neural'
def main():
    work = ROOT / 'gpu-smoke-results'
    work.mkdir(exist_ok=True)
    fixture = work / 'fixture.mkv'
    seed = work / 'seed.png'
    subprocess.run(['ffmpeg','-y','-v','error','-f','lavfi','-i','testsrc2=size=160x90:rate=1','-frames:v','1',str(seed)],check=True)
    subprocess.run(['ffmpeg','-y','-v','error','-loop','1','-i',str(seed),'-r','12','-t','3','-c:v','ffv1','-pix_fmt','yuv420p',str(fixture)],check=True)
    images = []
    for channels in (0,8,16,-1):
        model_channels = 8 if channels == -1 else channels
        output = work / f'{channels}.png'
        result = work / f'{channels}.json'
        lua = work / 'inspect.lua'
        marker = f'Owl FSRCNNX {model_channels} RGB reconstruction' if channels else ''
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
        local file = assert(io.open(RESULT .. '.tmp', 'w'))
        local encoded, failure = utils.format_json({descriptions=descriptions, version=mp.get_property('mpv-version') or 'unknown', hwdec=mp.get_property('hwdec-current') or 'no'})
        if not encoded then file:close(); mp.msg.error(failure or 'JSON encoding failed'); mp.commandv('quit', '2'); return end
        file:write(encoded)
        file:close()
        mp.set_property_bool('pause', true)
        os.rename(RESULT .. '.tmp', RESULT)
    elseif attempts > 80 then
        mp.msg.error('Expected CNN reconstruction pass not observed')
        mp.commandv('quit', '2')
    else mp.add_timeout(0.25, inspect) end
end
mp.register_event('file-loaded', function() mp.get_property_native('vo-passes'); mp.add_timeout(0.5, inspect) end)
'''.replace('MARKER',json.dumps(marker)).replace('RESULT',json.dumps(str(result))))
        cmd = ['mpv','--no-config','--vo=gpu-next','--gpu-api=opengl','--gpu-context=x11egl','--opengl-es=yes',
            '--gpu-sw=yes','--term-status-msg=','--osc=no','--osd-level=0','--osd-bar=no','--hwdec=no','--no-audio','--loop-file=inf','--geometry=960x540+0+0','--border=no','--scale=ewa_lanczossharp',
            '--dscale=mitchell','--fbo-format=rgba16f','--script='+str(lua),'--log-file='+str(work/f'{channels}.log')]
        if channels:
            shader = ASSETS/f'Owl-FSRCNNX-{model_channels}.glsl'
            if channels == -1:
                control = work / 'red-control.glsl'
                source = shader.read_text().replace('return vec4(color.rgb + vec3(neural - luma), color.a);','return vec4(1.0, 0.0, 0.0, 1.0);')
                assert source != shader.read_text()
                control.write_text(source); shader = control
            cmd.append('--glsl-shaders='+str(shader))
        result.unlink(missing_ok=True)
        with (work/f'console-{channels}.txt').open('w') as console:
            process = subprocess.Popen(cmd+[str(fixture)],stdout=console,stderr=subprocess.STDOUT,
                env={**os.environ,'LIBGL_ALWAYS_SOFTWARE':'1'})
            try:
                deadline = time.monotonic()+90
                while not result.exists():
                    if process.poll() is not None: raise RuntimeError(f'mpv exited before render confirmation: {process.returncode}')
                    if time.monotonic()>deadline: raise TimeoutError('render confirmation timeout')
                    time.sleep(0.1)
                time.sleep(0.3)  # let the confirmed render reach the X11 swapchain
                subprocess.run(['ffmpeg','-y','-v','error','-f','x11grab','-draw_mouse','0','-video_size','960x540',
                    '-i',os.environ['DISPLAY']+'+0,0','-frames:v','1',str(output)],check=True,timeout=15)
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()

        data = json.loads(result.read_text())
        descriptions = ' '.join(data['descriptions'])
        if channels:
            assert marker in descriptions
            assert f'Owl FSRCNNX {24-model_channels} RGB reconstruction' not in descriptions
        im = Image.open(output).convert('RGB')
        assert im.size == (960,540), 'screenshot must capture scaled renderer output'
        colors = ImageStat.Stat(im).mean
        print(f'Output {channels}: size={im.size}, RGB means={colors}')
        if channels == -1:
            assert colors[0]>220 and colors[1]<20 and colors[2]<20, 'capture is not observing the actual post-hook renderer output'
            continue
        images.append(im)
        assert min(colors)>20 and max(colors)<240, 'blank/invalid image'
        assert ImageStat.Stat(ImageChops.difference(im.getchannel('R'),im.getchannel('G'))).mean[0]>10, 'RGB reconstruction lost chroma'
        print(f'Mesa GLES model {channels}: actual render confirmed; {data["version"]}; hwdec={data["hwdec"]}')
    baseline_delta = sum(ImageStat.Stat(ImageChops.difference(images[0],images[1])).mean) / 3
    model_delta = sum(ImageStat.Stat(ImageChops.difference(images[1],images[2])).mean) / 3
    print(f'Rendered RGB mean absolute difference: base/neural={baseline_delta}, neural/max={model_delta}')
    assert baseline_delta>0.001
    assert model_delta>0.001
if __name__ == '__main__':
    main()
    print('Neural/AI Max compile, mutually exclusive execution, colored output, distinct CNN results: PASS')
