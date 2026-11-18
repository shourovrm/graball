# Match both stages to Debian bookworm; yt-dlp requires Node >=22.
FROM node:22-bookworm-slim AS javascript
FROM python:3.12-slim-bookworm
COPY --from=javascript /usr/local/bin/node /usr/local/bin/node
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates libstdc++6 && rm -rf /var/lib/apt/lists/*
RUN node -e "if (Number(process.versions.node.split('.')[0]) < 22) process.exit(1)"
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade -r requirements.txt && python -m yt_dlp --version
COPY . .
RUN useradd --create-home graball && mkdir -p /data && chown graball:graball /data
USER graball
ENV GRABALL_DATA=/data
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
