import io
from email.message import Message
import json
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest
from scripts.serve_sync import ROOT, RangeHandler


class RangeTests(unittest.TestCase):
    def request(self, path, header):
        handler = object.__new__(RangeHandler)
        handler.path = '/video.mov'
        handler.headers = {'Range': header}
        handler.translate_path = lambda _: str(path)
        handler.response_headers = {}
        handler.send_response = lambda status: setattr(handler, 'status', status)
        handler.send_header = handler.response_headers.__setitem__
        handler.end_headers = lambda: None
        handler.send_error = lambda status: setattr(handler, 'status', status)
        return handler

    def test_normal_suffix_open_ended_and_invalid_ranges(self):
        folder = ROOT / 'recordings'
        with tempfile.TemporaryDirectory(dir=folder) as temporary:
            path = Path(temporary) / 'video.mov'
            path.write_bytes(b'0123456789')
            for header, status, expected in [('bytes=2-5', 206, b'2345'),
                                               ('bytes=-3', 206, b'789'),
                                               ('bytes=7-', 206, b'789'),
                                               ('bytes=10-', 416, None),
                                               ('bytes=5-2', 416, None),
                                               ('bytes=0-1,3-4', 416, None),
                                               ('bytes=-0', 416, None)]:
                with self.subTest(header=header):
                    handler = self.request(path, header)
                    handle = handler.send_head()
                    self.assertEqual(handler.status, status)
                    if handle:
                        out = io.BytesIO()
                        with handle:
                            handler.copyfile(handle, out)
                        self.assertEqual(out.getvalue(), expected)
                        self.assertEqual(handler.response_headers['Content-Length'], str(len(expected)))
                    else:
                        self.assertIsNone(expected)

    def test_hidden_paths_are_not_served(self):
        handler = self.request(ROOT / '.git/config', 'bytes=0-10')
        self.assertIsNone(handler.send_head())
        self.assertEqual(handler.status, 404)


class FilterEndpointTests(unittest.TestCase):
    def handler(self, origin='http://127.0.0.1:8000', length=2):
        handler = object.__new__(RangeHandler)
        handler.path = '/api/filter'
        handler.server = SimpleNamespace(server_port=8000)
        handler.headers = Message()
        handler.headers['Origin'] = origin
        handler.headers['Content-Length'] = str(length)
        handler.headers['Content-Type'] = 'application/json'
        handler.rfile = io.BytesIO(b'{}')
        handler.send_error = lambda status, *args: setattr(handler, 'status', status)
        handler.json_response = lambda status, data: (setattr(handler, 'status', status), setattr(handler, 'response', data))
        return handler

    def test_rejects_cross_origin_and_oversized_payload_before_running_python(self):
        for handler in [self.handler(origin='https://unrelated.example'), self.handler(length=33*1024*1024)]:
            with patch('scripts.serve_sync.subprocess.run') as run:
                handler.do_POST()
                self.assertIn(handler.status, [403, 413])
                run.assert_not_called()

    def test_calls_isolated_analysis_python_and_returns_result(self):
        handler = self.handler()
        result = SimpleNamespace(returncode=0, stdout=json.dumps({'values': [[0]*6], 'report': {}}).encode())
        with patch('scripts.serve_sync.subprocess.run', return_value=result) as run:
            handler.do_POST()
            self.assertEqual(handler.status, 200)
            self.assertEqual(handler.response['values'], [[0]*6])
            self.assertEqual(run.call_args.args[0][0], str(ROOT/'.tools/imu-analysis/bin/python'))
            self.assertEqual(run.call_args.kwargs['input'], b'{}')


if __name__ == '__main__':
    unittest.main()
