#!/usr/bin/env python3
"""Verify pinned shader sources, unchanged CNN functions, notices, and renderer options."""
import hashlib, json, pathlib, re
ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/neural'
manifest = json.loads((ASSETS / 'models.json').read_text())
def functions(text):
    result = []
    for match in re.finditer(r'vec4 hook\(\)\s*\{', text):
        start = match.start(); end = match.end(); depth = 1
        while depth:
            if text[end] == '{': depth += 1
            if text[end] == '}': depth -= 1
            end += 1
        result.append(text[start:end])
    return result
sizes = []
for model in manifest['models']:
    original = (ASSETS / model['upstreamFile']).read_bytes()
    adapted = (ASSETS / model['file']).read_bytes()
    assert hashlib.sha256(original).hexdigest() == model['upstreamSha256']
    assert hashlib.sha256(adapted).hexdigest() == model['sha256']
    orig = original.decode(); new = adapted.decode()
    assert 'GNU Lesser General Public' in orig and '2017-2021 igv' in new
    expected = [f.replace('LUMA', 'OWL_LUMA') for f in functions(orig)]
    assert functions(new)[1:-1] == expected, 'CNN coefficients/topology changed'
    assert new.count('//!HOOK MAIN') == len(expected) + 2
    assert '//!HOOK LUMA' not in new
    assert f'Owl FSRCNNX {model["channels"]} RGB reconstruction' in new
    sizes.append(len(original))
assert sizes[1] > sizes[0]
assert (ASSETS / 'COPYING.LESSER').stat().st_size > 7000
assert (ASSETS / 'COPYING').stat().st_size > 30000
assert 'GPU, not NPU' in (ASSETS / 'ATTRIBUTION.txt').read_text()
print('Pinned FSRCNNX 8/16 models, unchanged CNN functions, RGB hooks and licenses: PASS')
