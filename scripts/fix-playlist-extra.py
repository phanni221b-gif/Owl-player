from pathlib import Path

p = Path('app/src/main/java/is/xyz/mpv/MPVActivity.kt')
text = p.read_text(encoding='utf-8')
old = 'intent.getStringArrayListExtra(FolderActivity.EXTRA_OWL_PLAYLIST)'
new = 'intent.getStringArrayListExtra("owl_playlist_uris")'
if old not in text:
    raise SystemExit('playlist extra anchor not found')
p.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Replaced cross-class playlist extra reference with stable Owl key')
