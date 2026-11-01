#!/usr/bin/env python3
"""Verify shipped CNN/license bytes, arm64 ELF payload and the required JNI export."""
import hashlib, json, pathlib, struct, subprocess, sys, tempfile, zipfile
apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    prefix = 'assets/neural/'
    manifest = json.loads(archive.read(prefix+'models.json'))
    for model in manifest['models']:
        for name, expected in ((model['file'],model['sha256']),(model['upstreamFile'],model['upstreamSha256'])):
            assert hashlib.sha256(archive.read(prefix+name)).hexdigest() == expected
    assert len(archive.read(prefix+'COPYING.LESSER'))>7000
    assert len(archive.read(prefix+'COPYING'))>30000
    attribution = archive.read(prefix+'ATTRIBUTION.txt').decode()
    assert 'LGPL-3.0-or-later' in attribution and 'GPU, not NPU' in attribution
    libraries = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert libraries and all(name.startswith('lib/arm64-v8a/') for name in libraries)
    for name in libraries:
        data = archive.read(name)
        assert data[:4] == b'\x7fELF' and data[4] == 2 and data[5] == 1
        assert struct.unpack_from('<H',data,18)[0] == 183
    with tempfile.TemporaryDirectory(prefix='owl-jni-') as temporary:
        library = pathlib.Path(temporary)/'libplayer.so'
        library.write_bytes(archive.read('lib/arm64-v8a/libplayer.so'))
        symbols = subprocess.check_output(['readelf','--dyn-syms','--wide',str(library)],text=True)
        assert 'Java_is_xyz_mpv_MPVLib_requestRenderPassSummary' in symbols
print('Release payload: pinned model sources/licenses, arm64 ELF and render-pass JNI export PASS')
