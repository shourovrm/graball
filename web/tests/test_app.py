import json
import subprocess
import unittest
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
import app

DNS = [(2, 1, 6, '', ('93.184.216.34', 443))]
VIDEO = {'title': '<script>safe title</script>', 'formats': [
    {'height': 1080, 'vcodec': 'avc1'}, {'height': 720, 'vcodec': 'avc1'},
    {'height': 720, 'vcodec': 'avc1'}, {'height': 2160, 'vcodec': 'avc1', 'has_drm': True}]}

class WebTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app.app)
        self.client.get('/')
        app.media.clear(); app.jobs.clear(); app.rate.clear()

    def resolve(self):
        with patch('app.socket.getaddrinfo', return_value=DNS), patch('app.subprocess.run', return_value=subprocess.CompletedProcess([], 0, json.dumps(VIDEO), '')):
            return self.client.post('/api/resolve', json={'url': 'https://example.com/video'})

    def test_page_and_headers(self):
        response = TestClient(app.app).get('/')
        self.assertEqual(response.status_code, 200)
        self.assertIn('Good videos.', response.text)
        self.assertIn('HttpOnly', response.headers['set-cookie'])
        self.assertIn("frame-ancestors 'none'", response.headers['content-security-policy'])

    def test_private_and_invalid_urls(self):
        for url in ['file:///etc/passwd', 'http://user:pass@example.com/', 'http://example.com:9000/']:
            self.assertEqual(self.client.post('/api/resolve', json={'url': url}).status_code, 400)
        with patch('app.socket.getaddrinfo', return_value=[(2, 1, 6, '', ('127.0.0.1', 80))]):
            self.assertEqual(self.client.post('/api/resolve', json={'url': 'http://localhost/video'}).status_code, 400)

    def test_resolve_qualities(self):
        response = self.resolve()
        self.assertEqual(response.status_code, 200)
        self.assertEqual([q['id'] for q in response.json()['qualities']], ['1080', '720'])
        self.assertEqual(response.json()['title'], VIDEO['title'])

    def test_selection_privacy_and_invalid_quality(self):
        key = self.resolve().json()['id']
        body = {'media_id': key, 'quality': '720'}
        self.assertEqual(TestClient(app.app).post('/api/downloads', json=body).status_code, 404)
        body['quality'] = '--exec bad'
        self.assertEqual(self.client.post('/api/downloads', json=body).status_code, 400)

    def test_cross_origin(self):
        self.assertEqual(self.client.post('/api/resolve', json={'url': 'https://example.com/video'}, headers={'Origin': 'https://other.example'}).status_code, 403)

    def test_engine_error_and_timeout(self):
        with patch('app.socket.getaddrinfo', return_value=DNS), patch('app.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', 'Unsupported URL')):
            self.assertEqual(self.client.post('/api/resolve', json={'url': 'https://example.com/video'}).status_code, 422)
        with patch('app.socket.getaddrinfo', return_value=DNS), patch('app.subprocess.run', side_effect=subprocess.TimeoutExpired([], 60)):
            self.assertEqual(self.client.post('/api/resolve', json={'url': 'https://example.com/video'}).status_code, 504)

    def test_complete_job_and_file_privacy(self):
        key = self.resolve().json()['id']
        with patch('app.pool.submit') as submit:
            response = self.client.post('/api/downloads', json={'media_id': key, 'quality': '720'})
            self.assertEqual(response.status_code, 202)
            job = response.json()['id']
            submit.assert_called_once()
        directory = app.DATA / job
        directory.mkdir(exist_ok=True)
        output = directory / 'example.mp4'
        output.write_bytes(b'test-media')
        process = Mock()
        process.stdout = iter(['PROGRESS 50.0%\n', f'FILE {output}\n'])
        process.poll.return_value = 0
        process.wait.return_value = 0
        with patch('app.socket.getaddrinfo', return_value=DNS), patch('app.subprocess.Popen', return_value=process):
            app.run_download(job, 'https://example.com/video', '720')
        self.assertEqual(self.client.get(f'/api/downloads/{job}').json()['state'], 'ready')
        response = self.client.get(f'/api/downloads/{job}/file')
        self.assertEqual(response.content, b'test-media')
        self.assertIn('attachment', response.headers['content-disposition'])
        self.assertEqual(TestClient(app.app).get(f'/api/downloads/{job}/file').status_code, 404)
        output.unlink(); directory.rmdir()

    def test_busy_engine(self):
        with patch('app.socket.getaddrinfo', return_value=DNS), patch('app.slots.acquire', return_value=False):
            self.assertEqual(self.client.post('/api/resolve', json={'url': 'https://example.com/video'}).status_code, 429)

    def test_expired_selection(self):
        key = self.resolve().json()['id']
        app.media[key]['created'] = 0
        self.assertEqual(self.client.post('/api/downloads', json={'media_id': key, 'quality': '720'}).status_code, 404)

if __name__ == '__main__':
    unittest.main()
