# Graball Web

A responsive website: paste a public video URL, choose a resolution, prepare a file, then save it to your device. The Python server uses yt-dlp and FFmpeg, following the original Android app's engine approach. No Android SDK is needed.

## Run with Docker (recommended)

From the repository root, install Docker Desktop and run:

```sh
docker compose up --build
```

Open **http://localhost:8000**. First build downloads the Python dependencies and FFmpeg. Stop with `docker compose down`.

## Run without Docker

Install Python 3.11+, FFmpeg, and Node.js (a JS runtime used by some extractors). Make sure `ffmpeg` is on PATH.

```sh
cd web
python -m venv .venv
# macOS/Linux:
source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000 --workers 1
```

Open http://localhost:8000. Check `/api/health` to confirm yt-dlp and FFmpeg are installed. Run `python -m unittest discover -s tests -v` to test the API; API tests additionally require `pip install httpx`.

## Hosting

This is a Python container application, not a GitHub Pages/static site. Deploy `web/Dockerfile` to a host that supports long-running Docker containers and FFmpeg, binding port 8000. Use **one process/replica**: selection tokens and job state are in memory. Configure HTTPS in front of the app and preserve the external Host and scheme for same-origin checking (trust forwarded headers only from your own proxy). The Compose file binds only to localhost for local development.

Before exposing a shared/public instance, add access control and per-user quotas at the reverse proxy, and enforce network egress rules that block private, loopback, link-local, reserved, and cloud-metadata addresses for IPv4 and IPv6. The Python network guard checks DNS results and socket destinations (including redirects); native FFmpeg subprocess networking still requires host-level isolation. Disable inherited outbound HTTP proxy environment variables unless they are an explicitly configured, equally restricted egress proxy. Do not give the download container access to internal services or credentials. The included Docker configuration drops privileges, runs with a read-only filesystem, and limits CPU, RAM, processes, and temporary storage.

The first version supports public, completed, individual videos, with two concurrent engine operations, 60-second lookups, 15-minute downloads and a 500 MB final-file limit. The actual container temporary storage limit bounds aggregate disk use. Streaming/muxing can temporarily need twice the final size. Files and tokens expire after an hour and are removed on subsequent API activity. Files are temporary and disappear on container replacement. Clearing the UI does not cancel a server download. Restarting loses jobs. Downloads are bound to a browser session; there is no history database or analytics.

Quality labels represent a maximum resolution. yt-dlp chooses available video/audio at or below that height and merges when needed; MP4 is preferred for merging but some source codecs can produce other containers. Size is not shown before download because sites often omit it.

Website support varies with yt-dlp and site restrictions. Some sites require additional JS runtimes, tokens, login or other configuration. This version does not accept cookies, bypass DRM, download live streams or expand playlists. Updating yt-dlp and rebuilding may restore broken extractors. Download only content you own or have permission to save.

## Files

- `app.py`: same-origin API, sessions, metadata, jobs, temporary file delivery.
- `engine.py`: isolated yt-dlp entry point and public-address network checks.
- `static/`: accessible responsive HTML, CSS and JavaScript, without build tools.
- `tests/`: isolated tests using a mocked extractor; no external video downloads.

GPLv3, consistent with the parent repository. The Android app remains in `app/`.

## Validation of this version

Nine isolated API tests passed, including lookup normalization, error/timeout handling, unsupported inputs, same-origin enforcement, session isolation, job completion, file delivery, and expired selections. JavaScript syntax and Python compilation checks passed. The engine and FFmpeg were detected successfully. External video lookup could not be verified in the build workspace because external DNS resolution was unavailable. Browser checks were blocked by the environment's socket restrictions, and Docker was unavailable, so the container build and visual layout remain unverified.
