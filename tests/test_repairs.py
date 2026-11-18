"""Regression coverage for the deployed direct-link and runtime failures."""
import json
import subprocess
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
import app

PUBLIC_DNS = [(2, 1, 6, '', ('93.184.216.34', 443))]

class RepairTests(unittest.TestCase):
    def setUp(self):
        app.media.clear()
        app.rate.clear()
        app.runtime_health.cache_clear()

    def tearDown(self):
        app.runtime_health.cache_clear()

    def test_direct_mp4_without_codec_or_formats(self):
        info = {'title': 'Big Buck Bunny', 'url': 'https://example.com/movie.mp4', 'ext': 'mp4'}
        with TestClient(app.app) as client:
            client.get('/')
            with patch('app.socket.getaddrinfo', return_value=PUBLIC_DNS), patch('app.subprocess.run', return_value=subprocess.CompletedProcess([], 0, json.dumps(info), '')):
                result = client.post('/api/resolve', json={'url': info['url']})
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.json()['qualities'][0]['id'], 'best')
            self.assertEqual(result.json()['title'], info['title'])

    def test_unknown_codec_in_formats_is_supported(self):
        item = {'url': 'https://example.com/movie.webm', 'ext': 'webm'}
        self.assertEqual(app.video_formats({'formats': [item]}), [item])

    def test_audio_drm_and_unknown_type_not_misclassified_as_video(self):
        for item in [
            {'url': 'https://example.com/music.mp3', 'ext': 'mp3'},
            {'url': 'https://example.com/movie.mp4', 'ext': 'mp4', 'vcodec': 'none'},
            {'url': 'https://example.com/movie.mp4', 'ext': 'mp4', 'has_drm': True},
            {'url': 'https://example.com/file', 'ext': None},
        ]:
            with self.subTest(item=item):
                self.assertEqual(app.video_formats(item), [])

    def test_bot_error_not_presented_as_private_video(self):
        code, text = app.error_kind("Sign in to confirm you’re not a bot. Use --cookies")
        self.assertEqual(code, 'HOST_BLOCKED')
        self.assertIn('hosting server', text)

    def test_challenge_and_format_errors_are_specific(self):
        self.assertEqual(app.error_kind('No supported JavaScript runtime could be found')[0], 'JS_RUNTIME')
        self.assertEqual(app.error_kind('n challenge solving failed')[0], 'JS_RUNTIME')
        self.assertEqual(app.error_kind('Requested format is not available')[0], 'NO_FORMAT')
        self.assertEqual(app.error_kind('HTTP Error 403: Forbidden')[0], 'SOURCE_FORBIDDEN')
        self.assertEqual(app.error_kind('Temporary failure in name resolution')[0], 'NETWORK')

    def test_http_failure_has_safe_diagnostic_code(self):
        stderr = 'ERROR: Sign in to confirm you’re not a bot. https://example.com/secret'
        with TestClient(app.app) as client, patch('app.socket.getaddrinfo', return_value=PUBLIC_DNS), patch('app.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', stderr)):
            response = client.post('/api/resolve', json={'url': 'https://example.com/video'})
        self.assertEqual(response.status_code, 422)
        self.assertIn('HOST_BLOCKED', response.json()['detail'])
        self.assertNotIn('/secret', response.text)

    def test_health_rejects_old_node_and_accepts_node22(self):
        for version, supported in [('v20.19.2', False), ('v22.16.0', True)]:
            app.runtime_health.cache_clear()
            with patch('app.subprocess.run', return_value=subprocess.CompletedProcess([], 0, version, '')), patch('app.shutil.which', return_value='/bin/ffmpeg'), patch('app.importlib.util.find_spec', return_value=object()), patch('app.importlib.metadata.version', return_value='2026.10.26'):
                result = app.runtime_health()
            self.assertEqual(result['node_supported'], supported)
            self.assertEqual(result['ok'], supported)
            self.assertEqual(result['build'], 'web-fix-2')

    def test_missing_runtime_health_does_not_crash(self):
        with patch('app.subprocess.run', side_effect=FileNotFoundError), patch('app.shutil.which', return_value='/bin/ffmpeg'), patch('app.importlib.util.find_spec', return_value=object()), patch('app.importlib.metadata.version', return_value='2026.10.26'):
            self.assertFalse(app.runtime_health()['node_supported'])

    def test_engine_does_not_suppress_diagnostic_warnings(self):
        args = app.engine_args()
        self.assertNotIn('--no-warnings', args)
        self.assertEqual(args[args.index('--js-runtimes') + 1], 'node')

if __name__ == '__main__':
    unittest.main()
