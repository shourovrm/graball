"""Graball web: single-process, same-origin UI and bounded yt-dlp jobs."""
import concurrent.futures
import ipaddress
import importlib.metadata
import importlib.util
from functools import lru_cache
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

ROOT = Path(__file__).parent
DATA = Path(os.environ.get('GRABALL_DATA', tempfile.gettempdir() + '/graball-web'))
DATA.mkdir(parents=True, exist_ok=True)
MAX_BYTES = 500 * 1024 * 1024
TTL = 3600
app = FastAPI(title='Graball Web', docs_url=None, redoc_url=None)
app.mount('/static', StaticFiles(directory=ROOT / 'static'), name='static')
pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
slots = threading.BoundedSemaphore(2)
lock = threading.RLock()
media = {}
jobs = {}
rate = {}

class Link(BaseModel):
    url: str = Field(min_length=8, max_length=4096)

class Download(BaseModel):
    media_id: str
    quality: str

def validate_url(value):
    try:
        parsed = urlsplit(value.strip())
        if parsed.scheme not in ('https', 'http') or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError()
        if parsed.port not in (None, 80, 443):
            raise ValueError()
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
            raise ValueError()
    except (ValueError, OSError):
        raise HTTPException(400, 'Enter a public http:// or https:// video link.')
    return value.strip()

def cleanup():
    now = time.time()
    with lock:
        for key, item in list(jobs.items()):
            if item['state'] in ('ready', 'error') and now - item['updated'] > TTL:
                shutil.rmtree(DATA / key, ignore_errors=True)
                del jobs[key]
        for key, item in list(media.items()):
            if now - item['created'] > TTL:
                del media[key]
        for key, values in list(rate.items()):
            if not values or now - values[-1] > 60:
                del rate[key]

def owner(request):
    return request.state.owner

@app.middleware('http')
async def session(request: Request, call_next):
    token = request.cookies.get('graball_session', '')
    fresh = len(token) != 64 or any(c not in '0123456789abcdef' for c in token)
    request.state.owner = secrets.token_hex(32) if fresh else token
    if request.method == 'POST':
        origin = request.headers.get('origin')
        if origin and origin != str(request.base_url).rstrip('/'):
            return JSONResponse({'detail': 'Cross-origin requests are not allowed.'}, status_code=403)
    response = await call_next(request)
    if fresh:
        response.set_cookie('graball_session', request.state.owner, httponly=True, samesite='strict', secure=request.url.scheme == 'https', max_age=TTL)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Referrer-Policy'] = 'no-referrer'
    response.headers['Content-Security-Policy'] = "default-src 'self'; img-src 'self' https: data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    return response

def engine_args():
    return [sys.executable, str(ROOT / 'engine.py'), '--ignore-config', '--no-playlist', '--no-color', '--js-runtimes', 'node', '--socket-timeout', '15', '--retries', '2', '--max-filesize', str(MAX_BYTES)]

def error_kind(stderr):
    lower = stderr.lower()
    if any(text in lower for text in ("not a bot", "confirm you're not", "confirm you’re not", 'unusual traffic', 'too many requests', 'http error 429')):
        return 'HOST_BLOCKED', 'The video website blocked requests from this hosting server. Retrying another link may not help; the server operator must review the hosting restriction.'
    if any(text in lower for text in ('javascript runtime', 'js runtime', 'challenge solving failed', 'n challenge solving failed', 'signature solving failed', 'ejs script', 'no supported javascript')):
        return 'JS_RUNTIME', 'The server could not run the video website’s JavaScript challenge. Rebuild with the updated Dockerfile (Node.js 22 or newer).'
    if 'no module named yt_dlp' in lower:
        return 'ENGINE_MISSING', 'The download engine is missing. Rebuild the server image.'
    if 'drm' in lower:
        return 'DRM', 'This video is DRM-protected and cannot be downloaded.'
    if any(text in lower for text in ('geo-restricted', 'not available in your country', 'not available in your region')):
        return 'REGION', 'This video is unavailable in the hosting server’s region.'
    if any(text in lower for text in ('private video', 'members-only', 'age-restricted', 'sign in', 'login', 'cookies')):
        return 'LOGIN_REQUIRED', 'This video requires sign-in or is private. This website supports public videos only.'
    if any(text in lower for text in ('unsupported url', 'no video', 'not a valid url')):
        return 'UNSUPPORTED', 'No supported video was found at this link.'
    if 'larger than max' in lower or 'maximum size' in lower:
        return 'FILE_TOO_LARGE', 'This video exceeds the 500 MB limit.'
    if 'requested format is not available' in lower:
        return 'NO_FORMAT', 'The source did not provide a usable video format. Its player requirements or access restrictions may have changed.'
    if 'http error 403' in lower or 'forbidden' in lower:
        return 'SOURCE_FORBIDDEN', 'The video website refused access from this server (HTTP 403).'
    if any(text in lower for text in ('timed out', 'name resolution', 'resolve host', 'network is unreachable', 'connection refused')):
        return 'NETWORK', 'The server could not reach the video website. Try again shortly.'
    return 'EXTRACTOR_ERROR', 'The video engine could not read this link. The server logs contain the failure details.'


def error_message(stderr):
    return error_kind(stderr)[1]


VIDEO_EXTENSIONS = {'mp4', 'm4v', 'webm', 'mkv', 'mov', 'avi', 'flv', 'ts', 'ogv', '3gp'}


def video_formats(info):
    """Generic direct-file extractors may omit vcodec until the file is probed."""
    def eligible(item):
        if item.get('has_drm') or item.get('vcodec') == 'none':
            return False
        if item.get('vcodec'):
            return True
        return bool(item.get('url')) and (item.get('ext') or '').lower() in VIDEO_EXTENSIONS
    formats = [item for item in (info.get('formats') or []) if eligible(item)]
    if not formats and eligible(info):
        formats = [info]
    return formats


@lru_cache(maxsize=1)
def runtime_health():
    engine = importlib.util.find_spec('yt_dlp') is not None
    ffmpeg = bool(shutil.which('ffmpeg'))
    try:
        result = subprocess.run(['node', '--version'], capture_output=True, text=True, timeout=5)
        node_version = result.stdout.strip() if result.returncode == 0 else None
        node_supported = bool(node_version) and int(node_version.lstrip('v').split('.')[0]) >= 22
    except (OSError, ValueError, subprocess.TimeoutExpired):
        node_version, node_supported = None, False
    try:
        engine_version = importlib.metadata.version('yt-dlp') if engine else None
    except importlib.metadata.PackageNotFoundError:
        engine_version = None
    return {'ok': engine and ffmpeg and node_supported, 'engine': engine, 'ffmpeg': ffmpeg,
            'node': node_version, 'node_supported': node_supported, 'yt_dlp': engine_version,
            'build': 'web-fix-2'}


@app.get('/')
def index():
    return FileResponse(ROOT / 'static/index.html')

@app.get('/api/health')
def health():
    return runtime_health()

@app.post('/api/resolve')
def resolve(body: Link, request: Request):
    cleanup()
    who = owner(request)
    # IP limits supplement session limits, so clearing cookies cannot bypass them.
    identity = request.client.host if request.client else who
    with lock:
        recent = [t for t in rate.get(identity, []) if time.time() - t < 60]
        if len(recent) >= 10:
            raise HTTPException(429, 'Too many requests. Please wait a minute.')
        rate[identity] = recent + [time.time()]
    url = validate_url(body.url)
    if not slots.acquire(blocking=False):
        raise HTTPException(429, 'The download engine is busy. Try again shortly.')
    try:
        result = subprocess.run(engine_args() + ['--dump-single-json', '--skip-download', '--', url], capture_output=True, text=True, timeout=60)
        if result.returncode:
            code, message = error_kind(result.stderr)
            reference = secrets.token_hex(4)
            print(f"Video lookup failed [{reference}] ({code}, exit={result.returncode}): {result.stderr[-4000:]}", flush=True)
            raise HTTPException(422, f"{message} [{code}; reference {reference}]")
        info = json.loads(result.stdout)
        if info.get('entries') is not None:
            raise HTTPException(422, 'Paste a single video link rather than a playlist.')
        if info.get('is_live'):
            raise HTTPException(422, 'Live streams are not supported. Try a completed video.')
        formats = video_formats(info)
        if not formats:
            raise HTTPException(422, 'No downloadable video was found at this link.')
        heights = sorted({int(f['height']) for f in formats if f.get('height')}, reverse=True)
        choices = [{'id': str(h), 'label': f'{h}p', 'description': 'Video + audio'} for h in heights]
        if not choices:
            choices = [{'id': 'best', 'label': 'Best available', 'description': 'Video + audio'}]
        key = secrets.token_urlsafe(24)
        with lock:
            media[key] = {'owner': who, 'url': url, 'qualities': [c['id'] for c in choices], 'created': time.time()}
        thumb = info.get('thumbnail')
        if thumb and urlsplit(thumb).scheme != 'https':
            thumb = None
        return {'id': key, 'title': info.get('title', 'Untitled video'), 'thumbnail': thumb, 'duration': info.get('duration'), 'source': info.get('extractor_key', 'Video'), 'qualities': choices}
    except subprocess.TimeoutExpired:
        raise HTTPException(504, 'Video lookup timed out. Try again or use another link.')
    except (json.JSONDecodeError, FileNotFoundError):
        raise HTTPException(503, 'The download engine is unavailable. Check the server installation.')
    finally:
        slots.release()

def run_download(key, source, quality):
    directory = DATA / key
    directory.mkdir(parents=True, exist_ok=True)
    process = None
    try:
        validate_url(source)
        fmt = 'bv*+ba/b' if quality == 'best' else f'bv*[height<={int(quality)}]+ba/b[height<={int(quality)}]'
        args = engine_args() + ['--format', fmt, '--merge-output-format', 'mp4', '--hls-prefer-native', '--no-simulate', '--progress', '--newline', '--progress-template', 'download:PROGRESS %(progress._percent_str)s', '--print', 'after_move:FILE %(filepath)s', '-o', str(directory / '%(title).120B.%(ext)s'), '--', source]
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        def watchdog():
            deadline = time.monotonic() + 900
            while process.poll() is None:
                if time.monotonic() > deadline or sum(f.stat().st_size for f in directory.glob('*') if f.is_file()) > MAX_BYTES * 2:
                    import signal
                    os.killpg(process.pid, signal.SIGKILL)
                    return
                time.sleep(1)
        threading.Thread(target=watchdog, daemon=True).start()
        tail = ''
        file = None
        for line in process.stdout:
            tail = (tail + line)[-4000:]
            with lock:
                if line.startswith('PROGRESS '):
                    try:
                        jobs[key]['progress'] = min(99, float(line.split()[1].strip('%')))
                    except (ValueError, IndexError):
                        pass
                if line.startswith('FILE '):
                    file = Path(line[5:].strip()).resolve()
                jobs[key]['updated'] = time.time()
        code = process.wait()
        if code or not file or file.parent != directory.resolve() or not file.is_file():
            raise RuntimeError(error_message(tail))
        if file.stat().st_size > MAX_BYTES:
            raise RuntimeError('This video exceeds the 500 MB limit.')
        with lock:
            jobs[key].update(state='ready', progress=100, file=str(file), updated=time.time())
    except Exception as exc:
        if process and process.poll() is None:
            import signal
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        shutil.rmtree(directory, ignore_errors=True)
        message = str(exc) if isinstance(exc, RuntimeError) else 'The download could not finish. Please try another video.'
        with lock:
            jobs[key].update(state='error', error=message, updated=time.time())
    finally:
        slots.release()

@app.post('/api/downloads', status_code=202)
def start_download(body: Download, request: Request):
    cleanup()
    with lock:
        item = media.get(body.media_id)
        if not item or item['owner'] != owner(request):
            raise HTTPException(404, 'This video selection expired. Paste the link again.')
        if body.quality not in item['qualities']:
            raise HTTPException(400, 'Choose one of the available qualities.')
        if not slots.acquire(blocking=False):
            raise HTTPException(429, 'The download engine is busy. Try again shortly.')
        key = secrets.token_urlsafe(24)
        jobs[key] = {'owner': owner(request), 'state': 'downloading', 'progress': 0, 'updated': time.time()}
        pool.submit(run_download, key, item['url'], body.quality)
    return {'id': key}

def get_job(key, request):
    cleanup()
    with lock:
        job = jobs.get(key)
        if not job or job['owner'] != owner(request):
            raise HTTPException(404, 'Download not found or expired.')
        return dict(job)

@app.get('/api/downloads/{key}')
def status(key: str, request: Request):
    job = get_job(key, request)
    return {k: job[k] for k in ('state', 'progress', 'error') if k in job}

@app.get('/api/downloads/{key}/file')
def file_download(key: str, request: Request):
    job = get_job(key, request)
    if job['state'] != 'ready':
        raise HTTPException(409, 'Your video is still being prepared.')
    path = Path(job['file'])
    if not path.is_file():
        raise HTTPException(410, 'This file has expired. Download the video again.')
    return FileResponse(path, filename=path.name, media_type='application/octet-stream')
