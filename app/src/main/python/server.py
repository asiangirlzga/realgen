"""RealGen local server.

Runs inside the Android app (via Chaquopy) on 127.0.0.1 and serves the web UI.
Image generation is done by the Stable Horde community network (free, no GPU
needed on the phone). Uses only the Python standard library.
"""
import base64
import json
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from ui import INDEX_HTML

HORDE = "https://stablehorde.net/api/v2"
ANON_KEY = "0000000000"
CLIENT = "RealGen:1.0:github"

STYLE = ("ultra-realistic, natural skin texture, detailed pores, 85mm lens, f/1.8, "
         "shallow depth of field, soft natural lighting, shot on Canon EOS R5, 8k uhd, "
         "film grain, highly detailed")
DEFAULT_NEG = ("cartoon, anime, illustration, painting, drawing, 3d render, cgi, plastic skin, "
               "airbrushed, deformed, extra fingers, bad hands, bad anatomy, disfigured, mutated, "
               "blurry, lowres, watermark, text, signature, oversaturated")

JOBS = {}
_models_cache = {"t": 0, "v": []}
_server = None


def http(method, url, body=None, key=ANON_KEY, raw=False, timeout=60):
    headers = {"apikey": key, "Client-Agent": CLIENT, "User-Agent": "RealGen/1.0",
               "Content-Type": "application/json"}
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            b = r.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")
        try:
            detail = json.loads(detail).get("message", detail)
        except Exception:
            pass
        raise RuntimeError("Horde error %s: %s" % (e.code, detail[:300]))
    return b if raw else json.loads(b)


def download(url):
    req = urllib.request.Request(url, headers={"User-Agent": "RealGen/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def sniff(b):
    if b[:4] == b"RIFF":
        return "image/webp"
    if b[:4] == b"\x89PNG":
        return "image/png"
    return "image/jpeg"


def fetch_new(job, hid, key):
    st = http("GET", "%s/generate/status/%s" % (HORDE, hid), key=key)
    gens = st.get("generations", [])
    for g in gens[len(job["imgs"]):]:
        img = g.get("img", "")
        if img.startswith("http"):
            job["imgs"].append(download(img))
        else:
            job["imgs"].append(base64.b64decode(img))


def run_job(jid, p):
    job = JOBS[jid]
    key = (p.get("key") or "").strip() or ANON_KEY
    try:
        w, h = [int(x) for x in str(p.get("size", "512x768")).split("x")]
        count = max(1, min(10, int(p.get("count", 10))))
        user = (p.get("prompt") or "").strip() or "portrait photo of a young woman, natural light"
        prompt = "RAW photo, %s, %s" % (user, STYLE)
        neg = (p.get("negative") or "").strip() or DEFAULT_NEG
        params = {"sampler_name": "k_euler_a", "cfg_scale": 6.5, "width": w, "height": h,
                  "steps": 25, "n": count, "karras": True}
        if p.get("face"):
            params["post_processing"] = ["GFPGAN"]
        body = {"prompt": prompt + " ### " + neg, "params": params, "nsfw": False,
                "censor_nsfw": True, "slow_workers": True, "r2": True,
                "replacement_filter": True}
        model = (p.get("model") or "").strip()
        if model and model != "(auto)":
            body["models"] = [model]

        job["message"] = "Submitting to the network..."
        hid = http("POST", HORDE + "/generate/async", body, key)["id"]
        job["horde"] = hid
        start = time.time()

        while True:
            if job["cancel"]:
                try:
                    http("DELETE", "%s/generate/status/%s" % (HORDE, hid), key=key)
                except Exception:
                    pass
                job["state"], job["message"] = "cancelled", "Cancelled."
                return
            c = http("GET", "%s/generate/check/%s" % (HORDE, hid), key=key)
            if c.get("faulted"):
                raise RuntimeError("The job failed on the network. Try again or pick another model.")
            if c.get("is_possible") is False:
                raise RuntimeError("No worker can serve this model/size right now. "
                                   "Try 512x512 or another model.")
            finished = c.get("finished", 0)
            if finished > len(job["imgs"]) or c.get("done"):
                fetch_new(job, hid, key)
            if c.get("done"):
                break
            if time.time() - start > 1200:
                raise RuntimeError("Timed out after 20 minutes.")
            q = c.get("queue_position", 0)
            job["message"] = "%d/%d ready. Queue position: %s, est. wait: %ss" % (
                len(job["imgs"]), count, q, c.get("wait_time", "?"))
            time.sleep(3)

        job["state"] = "done"
        job["message"] = "Done: %d images." % len(job["imgs"])
    except Exception as e:
        job["state"], job["message"] = "error", str(e)


def get_models():
    if time.time() - _models_cache["t"] < 300 and _models_cache["v"]:
        return _models_cache["v"]
    try:
        lst = http("GET", HORDE + "/status/models?type=image", timeout=30)
        lst = [m for m in lst if m.get("count", 0) > 0]
        lst.sort(key=lambda m: ("realistic" not in m["name"].lower(), -m.get("count", 0)))
        names = [m["name"] for m in lst][:40]
    except Exception:
        names = []
    names = ["(auto)"] + names
    _models_cache.update(t=time.time(), v=names)
    return names


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, data, ctype="application/json"):
        if isinstance(data, (dict, list)):
            data = json.dumps(data).encode()
        elif isinstance(data, str):
            data = data.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            return self._send(200, INDEX_HTML, "text/html; charset=utf-8")
        if path == "/api/models":
            return self._send(200, get_models())
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["api", "job"]:
            j = JOBS.get(parts[2])
            if not j:
                return self._send(404, {"error": "no such job"})
            return self._send(200, {"state": j["state"], "message": j["message"],
                                    "images": len(j["imgs"])})
        if len(parts) == 4 and parts[:2] == ["api", "img"]:
            j = JOBS.get(parts[2])
            try:
                b = j["imgs"][int(parts[3])]
            except Exception:
                return self._send(404, {"error": "no such image"})
            return self._send(200, b, sniff(b))
        self._send(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        n = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(n) if n else b"{}"
        if path == "/api/start":
            try:
                p = json.loads(raw or b"{}")
            except Exception:
                return self._send(400, {"error": "bad json"})
            jid = uuid.uuid4().hex[:12]
            JOBS[jid] = {"state": "running", "message": "Starting...", "imgs": [],
                         "cancel": False, "horde": None}
            threading.Thread(target=run_job, args=(jid, p), daemon=True).start()
            return self._send(200, {"id": jid})
        parts = path.strip("/").split("/")
        if len(parts) == 3 and parts[:2] == ["api", "cancel"] and parts[2] in JOBS:
            JOBS[parts[2]]["cancel"] = True
            return self._send(200, {"ok": True})
        self._send(404, {"error": "not found"})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def start(port=8765):
    """Called from Java. Binds the port, then serves in a background thread."""
    global _server
    if _server is not None:
        return
    _server = Server(("127.0.0.1", int(port)), Handler)
    threading.Thread(target=_server.serve_forever, daemon=True).start()


if __name__ == "__main__":  # desktop testing: python server.py
    start(8765)
    print("http://127.0.0.1:8765/")
    while True:
        time.sleep(3600)
