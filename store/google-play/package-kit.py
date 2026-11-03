"""Build a local copy-and-preview page and ZIP of the Google Play materials."""
import html
import json
import zipfile
from pathlib import Path
from validate import BASE, validate


def build():
    result = validate()
    (BASE/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    parts = []
    for lang, label in [('en-US','English'),('ru-RU','Русский')]:
        fields = []
        for name, title in [('title','Название'),('short-description','Краткое описание'),('full-description','Полное описание'),('release-notes','Примечания к выпуску')]:
            value=(BASE/'listing'/lang/(name+'.txt')).read_text(encoding='utf-8').strip()
            key=lang+'-'+name
            fields.append(f'<article><div class="field-title"><h3>{title}</h3><button onclick="copyField(\'{key}\',this)">Копировать</button></div><pre id="{key}">{html.escape(value)}</pre><small>{len(value)} символов</small></article>')
        images=''.join(f'<a href="{p.relative_to(BASE).as_posix()}"><img src="{p.relative_to(BASE).as_posix()}" alt="{html.escape(p.stem)}" loading="lazy"></a>' for p in sorted((BASE/'assets/screenshots'/lang).glob('*.png')))
        banner=f'assets/feature-graphic-{lang}.png'
        parts.append(f'<section id="{lang}"><h2>{label}</h2>{"".join(fields)}<h3>Картинка для описания: 1024 × 500</h3><a href="{banner}"><img class="banner" src="{banner}" alt="Баннер {lang}"></a><h3>Скриншоты телефона: 1920 × 1080</h3><div class="shots">{images}</div></section>')
    document='''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>mLabeler — комплект для Google Play</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f3f5f1;color:#1b2924;font:16px/1.55 system-ui,sans-serif}main{max-width:1080px;margin:auto;padding:48px 24px}header{padding-bottom:28px;border-bottom:1px solid #cbd5cb}h1{font-size:36px;letter-spacing:-1px;margin:4px 0 12px}h2{font-size:27px;margin-top:36px}h3{margin:0;font-size:17px}p{max-width:850px}nav{display:flex;gap:16px;flex-wrap:wrap}a{color:#25663c}article{background:white;border:1px solid #d5ddd4;border-radius:12px;padding:20px;margin:14px 0}.field-title{display:flex;align-items:center;justify-content:space-between;gap:12px}button{border:1px solid #9abb94;border-radius:7px;background:#e2f3d9;padding:8px 14px;color:#244429;cursor:pointer}pre{white-space:pre-wrap;font:16px/1.65 system-ui,sans-serif;margin:14px 0}small{color:#607166}img{display:block;max-width:100%;height:auto}.banner{margin:16px 0;border-radius:8px}.shots{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}.shots img{border-radius:7px}.note{border-left:4px solid #a37920;padding:14px 18px;background:#fff7df;margin:24px 0}.icon{width:128px;height:128px;float:right;margin:0 0 16px 20px}footer{margin-top:36px;padding-top:24px;border-top:1px solid #cbd5cb}@media(max-width:650px){.shots{grid-template-columns:1fr}.icon{width:80px;height:80px}h1{font-size:28px}}</style>
<main><header><a href="assets/icon-512.png"><img class="icon" src="assets/icon-512.png" alt="Значок mLabeler 512 × 512"></a><h1>mLabeler / Google Play</h1><p>Материалы страницы приложения на двух языках. Поддержка: <a href="mailto:m@msan.pw">m@msan.pw</a>. Сайт: <a href="https://github.com/Megageorgio/mlabeler">GitHub</a>.</p><nav><a href="#en-US">English</a><a href="#ru-RU">Русский</a><a href="assets/icon-512.png">Значок 512 × 512</a><a href="privacy/privacy-en.html">Privacy policy</a><a href="privacy/privacy-ru.html">Политика конфиденциальности</a></nav></header>
<div class="note"><strong>Перед отправкой на проверку:</strong> используйте новый офлайн-AAB из ручного workflow Android Play bundle, обновите ответы о данных и опубликуйте обновлённую политику по HTTPS. Материалы подготовлены локально; приложение и политика ещё не отправлены в Google Play. Подробности — в console-answers.ru.md.</div>
''' + ''.join(parts) + '''<footer>Скриншоты сняты в Android-приложении на участке около 54-й секунды предоставленной записи en.wav из mnew. Исходные записи не входят в комплект. Полная инструкция: README.ru.md. Ответы для остальных разделов: console-answers.ru.md.</footer></main>
<script>async function copyField(id,button){const text=document.getElementById(id).innerText;try{await navigator.clipboard.writeText(text);button.textContent='Скопировано'}catch(e){const range=document.createRange();range.selectNodeContents(document.getElementById(id));const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);button.textContent='Текст выделен — Ctrl+C'}setTimeout(()=>button.textContent='Копировать',2200)}</script></html>'''
    (BASE/'START-HERE.html').write_text(document,encoding='utf-8')
    output=BASE.parents[1]/'app/build/outputs/google-play/mLabeler-0.1.3-google-play-kit.zip'
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(BASE.rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:
                archive.write(path,Path('mLabeler-google-play')/path.relative_to(BASE))
    print(f"Validated {len(result['texts'])} text fields and {len(result['images'])} images.")
    print(output)


if __name__=='__main__': build()
