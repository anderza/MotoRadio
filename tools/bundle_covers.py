"""Встраивает обложки станций в приложение (запускается при сборке в GitHub Actions).

Для каждой станции из встроенного списка (const DEF в index.html) скачивает обложку,
уменьшает её до 192 px и кладёт в assets/www/covers/. Уже встроенные (covers/…) не трогает. В списке ссылка на сайт меняется
на локальный файл "covers/…", исходная остаётся в поле "o".
Обложки, которые не скачались, остаются ссылками на сайт — приложение загрузит их само.
Сборку скрипт не останавливает никогда.

    python3 tools/bundle_covers.py app/src/main/assets/www/index.html
"""
import hashlib, io, json, os, re, sys, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

SIDE = 192
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "image/*,*/*;q=0.5"})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("слишком большой файл")
    return data


def find_logo(st):
    """Станция без лого: ищем картинку на laut.fm (у его станций она есть всегда) и в каталоге Radio Browser."""
    u = st.get("u", "")
    m = re.match(r"https?://([a-z0-9-]+)\.stream\.laut\.fm/", u) or re.match(r"https?://stream\.laut\.fm/([a-z0-9_-]+)", u)
    if m:
        try:
            j = json.loads(fetch("https://api.laut.fm/station/" + m.group(1)))
            img = (j.get("images") or {}).get("station") or ""
            if img.startswith("http"):
                return img
        except Exception:
            pass
    q = urllib.parse.quote
    for api in ("https://de1.api.radio-browser.info/json/stations/byurl?url=" + q(u, safe=""),
                "https://de1.api.radio-browser.info/json/stations/byname/" + q(st.get("n", "")) + "?limit=10&order=votes&reverse=true"):
        try:
            for r in json.loads(fetch(api)):
                fav = r.get("favicon") or ""
                if fav.startswith("http"):
                    return fav
        except Exception:
            pass
    return ""


def shrink(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    im = im.convert("RGBA")
    im.thumbnail((SIDE, SIDE), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=82, method=6)
    return out.getvalue()


def main(page):
    s = open(page, encoding="utf-8").read()
    m = re.search(r"const DEF=(\[.*?\]);\n", s)
    stations = json.loads(m.group(1))
    covers = os.path.join(os.path.dirname(page), "covers")
    os.makedirs(covers, exist_ok=True)

    def one(st):
        if (st.get("l") or "").startswith("covers/"):
            return st["n"], st["l"][7:], ""  # уже встроена (в том числе лого, присланные Андреем)
        src = st.get("o") or st.get("l") or ""
        if not src.startswith("http"):
            src = find_logo(st)
        if not src.startswith("http"):
            return st["n"], None, "нет обложки"
        name = hashlib.sha1(src.encode()).hexdigest()[:12] + ".webp"
        try:
            with open(os.path.join(covers, name), "wb") as f:
                f.write(shrink(fetch(src)))
        except Exception as e:
            try:
                os.remove(os.path.join(covers, name))
            except OSError:
                pass
            return st["n"], None, str(e)[:120]
        st["o"] = src
        st["l"] = "covers/" + name
        return st["n"], name, ""

    with ThreadPoolExecutor(8) as ex:
        res = list(ex.map(one, stations))
    ok = [r for r in res if r[1]]
    for n, _, err in res:
        if err:
            print(f"  не встроена: {n} — {err}")
    s = s[: m.start(1)] + json.dumps(stations, ensure_ascii=False, separators=(",", ":")) + s[m.end(1):]
    open(page, "w", encoding="utf-8").write(s)
    print(f"Обложки встроены: {len(ok)} из {len(stations)}")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except Exception as e:
        print("Обложки не встроены:", e)
