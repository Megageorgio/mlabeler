"""Package a standalone bilingual policy for upload to the msan.pw web root."""
from pathlib import Path
import zipfile

BASE = Path(__file__).resolve().parent
OUTPUT = BASE.parents[1] / 'app/build/outputs/google-play'
FOLDER = OUTPUT / 'privacy-ftp'
PAGES = FOLDER / 'mlabeler/privacy'


def build():
    PAGES.mkdir(parents=True, exist_ok=True)
    for lang, name in [('en', 'index.html'), ('ru', 'ru.html')]:
        content = (BASE / 'privacy' / f'privacy-{lang}.html').read_text(encoding='utf-8')
        content = content.replace('href="privacy-en.html"', 'href="index.html"')
        content = content.replace('href="privacy-ru.html"', 'href="ru.html"')
        (PAGES / name).write_text(content, encoding='utf-8')
    instructions = '''Загрузка политики конфиденциальности mLabeler

1. Откройте по FTP корневую папку сайта msan.pw — ту, из которой сервер
   отдаёт страницы сайта (на хостинге она может называться public_html,
   www, httpdocs или иначе).
2. Загрузите в неё папку mlabeler из этого комплекта, сохраняя структуру:
   mlabeler/privacy/index.html
   mlabeler/privacy/ru.html
   Если папка mlabeler уже существует, добавьте в неё папку privacy.
   Не заменяйте корневой index.html сайта. Этот файл инструкции загружать не нужно.
3. Проверьте в приватном окне браузера без авторизации:
   English: https://msan.pw/mlabeler/privacy/index.html
   Русский: https://msan.pw/mlabeler/privacy/ru.html
4. В Google Play Console → Политика конфиденциальности укажите:
   https://msan.pw/mlabeler/privacy/index.html

Это планируемые адреса: комплект не загружен на сервер автоматически.
Хостинг должен отдавать HTML по HTTPS без пароля, геоблокировок
и запрета доступа для роботов. Не требуется PHP, база данных или сборка.
Стили встроены в HTML; внешних скриптов, шрифтов, аналитики и cookies нет.
Русская и английская страницы переключаются ссылками сверху.
Если сервер выдаёт 404, проверьте корень сайта и наличие правила,
которое перенаправляет все пути на другую страницу приложения.

Публикация страницы сама по себе не добавит ссылку на политику в приложение:
её также нужно включить в следующую Play-сборку.
'''
    (FOLDER / 'FTP-INSTRUCTIONS.ru.txt').write_text(instructions, encoding='utf-8')
    archive_path = OUTPUT / 'mLabeler-privacy-msan.pw.zip'
    files = [FOLDER / 'FTP-INSTRUCTIONS.ru.txt', PAGES / 'index.html', PAGES / 'ru.html']
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(FOLDER))
    print(archive_path)
    print(PAGES)


if __name__ == '__main__':
    build()
