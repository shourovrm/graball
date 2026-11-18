'use strict';
const $ = id => document.getElementById(id);
let video = null, quality = null, busy = false, pollController = null;
function message(text, error = false) {
  $('message').textContent = text;
  $('message').classList.toggle('error', error);
  $('message').hidden = !text;
}
async function api(path, body, signal) {
  const response = await fetch(path, {method: body ? 'POST' : 'GET', credentials: 'same-origin', headers: body ? {'Content-Type': 'application/json'} : {}, body: body ? JSON.stringify(body) : undefined, signal});
  const data = await response.json();
  if (!response.ok) throw new Error(typeof data.detail === 'string' ? data.detail : 'Please check the link and try again.');
  return data;
}
function reset() {
  if (pollController) pollController.abort();
  pollController = null; video = null; quality = null; busy = false;
  $('result').hidden = true; $('save-file').hidden = true; $('progress-area').hidden = true;
  $('download').hidden = false; $('download').disabled = false;
  $('submit').disabled = false; $('submit').textContent = 'Find video →';
  message('');
}
function render(data) {
  video = data; quality = data.qualities[0].id;
  $('video-title').textContent = data.title;
  $('source').textContent = `${data.source} · Video + audio`;
  $('thumbnail').hidden = true; $('thumbnail-fallback').hidden = false;
  $('thumbnail').onload = () => { $('thumbnail').hidden = false; $('thumbnail-fallback').hidden = true; };
  $('thumbnail').onerror = () => { $('thumbnail').hidden = true; $('thumbnail-fallback').hidden = false; };
  if (data.thumbnail) $('thumbnail').src = data.thumbnail; else $('thumbnail').removeAttribute('src');
  const seconds = Math.floor(data.duration || 0);
  $('duration').textContent = seconds ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` : '';
  $('duration').hidden = !seconds;
  $('qualities').replaceChildren();
  data.qualities.forEach((item, index) => {
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'quality'; button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(index === 0)); button.tabIndex = index === 0 ? 0 : -1;
    const strong = document.createElement('strong'); strong.textContent = item.label;
    const small = document.createElement('small'); small.textContent = item.description;
    button.append(strong, small);
    button.onclick = () => {
      if (busy) return;
      quality = item.id;
      [...$('qualities').children].forEach(b => { b.setAttribute('aria-checked', String(b === button)); b.tabIndex = b === button ? 0 : -1; });
    };
    button.onkeydown = event => {
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key) || busy) return;
      event.preventDefault();
      const buttons = [...$('qualities').children];
      const next = buttons[(buttons.indexOf(button) + (['ArrowRight', 'ArrowDown'].includes(event.key) ? 1 : -1) + buttons.length) % buttons.length];
      next.click(); next.focus();
    };
    $('qualities').append(button);
  });
  $('result').hidden = false;
}
$('link-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy) return;
  reset(); busy = true;
  const input = $('video-url').value.trim();
  try { const parsed = new URL(input); if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error(); }
  catch { busy = false; message('Paste a complete http:// or https:// video link.', true); return; }
  $('submit').disabled = true; $('submit').textContent = 'Finding video…';
  message('Looking for your video and available qualities…');
  try { render(await api('/api/resolve', {url: input})); message(''); }
  catch (error) { message(error.message === 'Failed to fetch' ? 'Could not reach Graball. Check your connection and try again.' : error.message, true); }
  finally { busy = false; $('submit').disabled = false; $('submit').textContent = 'Find video →'; }
});
$('clear').onclick = () => { reset(); $('video-url').focus(); };
function pause(ms, signal) {
  return new Promise((resolve, reject) => {
    const onAbort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', onAbort); resolve(); }, ms);
    signal.addEventListener('abort', onAbort, {once: true});
  });
}
$('download').onclick = async () => {
  if (!video || busy) return;
  busy = true; $('download').disabled = true; $('submit').disabled = true;
  $('progress-area').hidden = false; $('progress').value = 0; $('percent').textContent = '';
  $('progress-label').textContent = 'Preparing your video…'; message('');
  const controller = new AbortController(); pollController = controller;
  try {
    const job = await api('/api/downloads', {media_id: video.id, quality}, controller.signal);
    while (!controller.signal.aborted) {
      await pause(1500, controller.signal);
      const status = await api(`/api/downloads/${encodeURIComponent(job.id)}`, null, controller.signal);
      if (status.state === 'error') throw new Error(status.error);
      $('progress').value = status.progress;
      $('percent').textContent = `${Math.round(status.progress)}%`;
      $('progress-label').textContent = status.progress >= 99 ? 'Combining video and audio…' : 'Downloading to prepare your file…';
      if (status.state === 'ready') {
        $('download').hidden = true; $('progress-area').hidden = true;
        $('save-file').href = `/api/downloads/${encodeURIComponent(job.id)}/file`;
        $('save-file').hidden = false;
        message('Your video is ready. Save it below within the next hour.');
        break;
      }
    }
  } catch (error) {
    if (error.name !== 'AbortError') { message(error.message === 'Failed to fetch' ? 'Connection lost. Please try again.' : error.message, true); $('progress-area').hidden = true; }
  } finally {
    if (pollController === controller) { busy = false; $('download').disabled = false; $('submit').disabled = false; pollController = null; }
  }
};
