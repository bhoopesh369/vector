#!/usr/bin/env python3
"""Serve the local sync studio, including byte-range requests for video seeking."""
import argparse
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import json
import subprocess
try:
    from .catalog_sessions import library
except ImportError:
    from catalog_sessions import library

ROOT = Path(__file__).resolve().parents[1]


class RangeHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/library":
            self.json_response(200, library())
            return
        super().do_GET()

    def do_POST(self):
        if self.path != '/api/filter':
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        origin = self.headers.get('Origin')
        allowed = {f'http://127.0.0.1:{self.server.server_port}', f'http://localhost:{self.server.server_port}'}
        if origin and origin not in allowed:
            self.send_error(HTTPStatus.FORBIDDEN, 'Local viewer origin required')
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size <= 0 or size > 32*1024*1024:
                self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
                return
            if self.headers.get_content_type() != 'application/json':
                self.send_error(HTTPStatus.UNSUPPORTED_MEDIA_TYPE)
                return
            body = self.rfile.read(size)
            python = ROOT/'.tools/imu-analysis/bin/python'
            if not python.exists():
                self.json_response(503, {'error': 'Run sh scripts/setup-analysis.sh first.'})
                return
            result = subprocess.run([str(python), str(ROOT/'scripts/filter_request.py')], input=body,
                                    capture_output=True, timeout=60, cwd=ROOT)
            try:
                response = json.loads(result.stdout)
            except ValueError:
                self.json_response(500, {'error': 'Filter process failed. Check the project analysis environment.'})
                return
            self.json_response(200 if result.returncode == 0 else 400, response)
        except (ValueError, OSError, subprocess.TimeoutExpired) as error:
            self.json_response(400, {'error': str(error)})

    def json_response(self, status, data):
        body = json.dumps(data, allow_nan=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def send_head(self):
        self.byte_range = None
        path = Path(self.translate_path(self.path))
        if any(part.startswith('.') for part in path.relative_to(ROOT).parts):
            self.send_error(HTTPStatus.NOT_FOUND)
            return None
        requested = self.headers.get('Range')
        if not requested or not path.is_file():
            return super().send_head()
        match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested.strip())
        size = path.stat().st_size
        if not match or not size or not any(match.groups()):
            return self.unsatisfiable(size)
        left, right = match.groups()
        if left:
            start = int(left)
            end = min(int(right), size - 1) if right else size - 1
        else:
            start, end = max(0, size - int(right)), size - 1
        if start >= size or start > end:
            return self.unsatisfiable(size)
        handle = path.open('rb')
        handle.seek(start)
        self.send_response(HTTPStatus.PARTIAL_CONTENT)
        self.send_header('Content-type', self.guess_type(str(path)))
        self.send_header('Content-Length', str(end - start + 1))
        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.send_header('Accept-Ranges', 'bytes')
        self.end_headers()
        self.byte_range = (start, end)
        return handle

    def unsatisfiable(self, size):
        self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        self.send_header('Content-Range', f'bytes */{size}')
        self.send_header('Content-Length', '0')
        self.end_headers()
        return None

    def copyfile(self, source, outputfile):
        if self.byte_range is None:
            return super().copyfile(source, outputfile)
        remaining = self.byte_range[1] - self.byte_range[0] + 1
        try:
            while remaining:
                block = source.read(min(1024 * 1024, remaining))
                if not block:
                    break
                outputfile.write(block)
                remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Browsers cancel old byte-range requests during scrubbing.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8000)
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), partial(RangeHandler, directory=str(ROOT)))
    print(f'Sync studio: http://127.0.0.1:{args.port} (Ctrl-C to stop)', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
