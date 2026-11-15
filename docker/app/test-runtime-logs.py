"""Exercise the packaged image with disposable containers and no mounted user data.

Run: python docker/app/test-runtime-logs.py [image]
"""
import http.cookiejar
import json
import queue
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


IMAGE = sys.argv[1] if len(sys.argv) > 1 else 'dmdl:authenticated-log-viewer'
NAME = 'dmdl-logs-' + uuid.uuid4().hex[:10]
REDIS = NAME + '-redis'


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT).strip()


def eventually(check, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(.25)
    raise AssertionError('Timed out waiting for runtime condition')


class Stream:
    def __init__(self, url, cookie):
        self.events = queue.Queue(maxsize=100)
        self.ended = threading.Event()
        self.response = urllib.request.urlopen(urllib.request.Request(url, headers={'Cookie': cookie}), timeout=15)
        assert 'no-store' in self.response.headers['Cache-Control']
        self.worker = threading.Thread(target=self.read, daemon=True)
        self.worker.start()

    def read(self):
        try:
            for raw in self.response:
                text = raw.decode().strip()
                if text.startswith('data:'):
                    self.events.put(json.loads(text[5:]), timeout=5)
        except (OSError, ValueError, AttributeError, queue.Full):
            pass
        finally:
            self.ended.set()

    def matching(self, predicate, seconds=15):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            event = self.events.get(timeout=max(.1, deadline - time.monotonic()))
            if predicate(event):
                return event
        raise AssertionError('Expected SSE event')

    def close(self):
        try:
            self.response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
        except (AttributeError, OSError):
            pass
        self.worker.join(timeout=2)
        self.response.close()


def main():
    docker('network', 'create', NAME)
    docker('run', '-d', '--name', REDIS, '--network', NAME, 'redis:8.10.3-alpine')
    docker('run', '-d', '--name', NAME, '--network', NAME, '-p', '127.0.0.1::8080',
           '-e', 'DMDL_REDIS_HOST=' + REDIS, '-e', 'DMDL_NGINX_ACCESS_LOG=true',
           '-e', 'DMDL_ARIA2_LOG_LEVEL=notice', IMAGE)
    port = docker('port', NAME, '8080/tcp').split(':')[-1]
    base = 'http://127.0.0.1:' + port
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

    def request(path, body=None, anonymous=False):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=data, headers={'Content-Type': 'application/json'})
        response = (urllib.request.urlopen if anonymous else client.open)(req, timeout=10)
        with response:
            return json.load(response) if response.headers.get_content_type() == 'application/json' else response.read()

    def ready():
        nonlocal base
        port = docker('port', NAME, '8080/tcp').split(':')[-1]
        base = 'http://127.0.0.1:' + port
        eventually(lambda: request('/api/auth/registration', anonymous=True), 90)

    def login():
        cookies.clear()
        request('/api/auth/login', {'username': 'fixture', 'password': 'fixture-password'})

    def cookie():
        return '; '.join(item.name + '=' + item.value for item in cookies)

    def inject(message, count=1, source='backend'):
        code = "import sys; f=open('/run/dmdl/logs/' + sys.argv[1] + '.pipe','w'); message=sys.stdin.read(); [f.write(message+'\\n') for _ in range(int(sys.argv[2]))]; f.close()"
        subprocess.run(['docker', 'exec', '-i', NAME, 'python3', '-c', code, source, str(count)], input=message, text=True, check=True, capture_output=True)

    ready()
    for path in ['/api/logs', '/api/logs/events']:
        try:
            request(path, anonymous=True)
            raise AssertionError('Expected 401')
        except urllib.error.HTTPError as error:
            assert error.code == 401
            assert 'no-store' in error.headers['Cache-Control']
    request('/api/auth/register', {'username': 'fixture', 'password': 'fixture-password'})
    login()
    snapshot = eventually(lambda: request('/api/logs') if request('/api/logs')['records'] else None)
    assert {item['source'] for item in snapshot['sources']} == {'backend', 'nginx', 'aria2'}
    assert all(item['state'] == 'available' for item in snapshot['sources'])
    assert request('/api/logs?source=aria2')['records'], 'aria2 startup output must survive backend startup'
    assert eventually(lambda: request('/api/logs?source=nginx&levels=INFO')['records'])
    inject('2026-11-15T10:00:00Z ERROR HandoffTimeout [error].*')
    live = Stream(base + '/api/logs/events?' + urllib.parse.urlencode({'cursor': snapshot['cursor'], 'source': 'backend', 'text': 'handofftimeout [error].*', 'levels': 'ERROR'}), cookie())
    received = live.matching(lambda page: bool(page.get('records')))
    assert len(received['records']) == 1
    saved_cursor = received['cursor']
    live.close()
    inject('2026-11-15T10:00:00Z ERROR retained after pause')
    replay = Stream(base + '/api/logs/events?' + urllib.parse.urlencode({'cursor': saved_cursor}), cookie())
    replay.matching(lambda page: any('retained after pause' in record['message'] for record in page['records']))
    replay.close()
    print('PASS authenticated history, source metadata, startup output, filters, handoff and retained replay', flush=True)

    run = docker('exec', NAME, 'cat', '/run/dmdl/logs/run-id')
    request('/api/aria2/restart', {})
    eventually(lambda: request('/api/aria2/status')['restartState'] == 'IDLE', 40)
    assert docker('exec', NAME, 'cat', '/run/dmdl/logs/run-id') == run
    assert eventually(lambda: request('/api/logs?source=aria2&text=restarting')['records'])
    allocation = "import subprocess,json; p='/downloads/.dmdl/aria2-allocation.json'; out=subprocess.check_output(['awk','-v','status='+p,'-f','/usr/local/bin/allocation-readout.awk'],input=b'[FileAlloc:#abcdef 1024B/2048B]\\n'); assert out==b''; assert json.load(open(p))['allocatedBytes']==1024; print('allocation OK')"
    assert docker('exec', NAME, 'python3', '-c', allocation) == 'allocation OK'
    print('PASS requested aria2 restart preserves capture and allocation processing', flush=True)

    paused = request('/api/logs')['cursor']
    inject('2026-11-15T10:00:00Z INFO rotation ' + 'x' * 40000, count=900)
    rotation = Stream(base + '/api/logs/events?' + urllib.parse.urlencode({'cursor': paused}), cookie())
    rotation.matching(lambda page: page.get('gap') == 'retention', 30)
    rotation.close()
    sizes = json.loads(docker('exec', NAME, 'python3', '-c', "import json,pathlib; print(json.dumps([p.stat().st_size for p in pathlib.Path('/run/dmdl/logs/backend').glob('*.jsonl')]))"))
    assert len(sizes) == 3 and max(sizes) <= 10 * 1024 * 1024
    print('PASS bounded rotation and explicit pause gap', flush=True)

    active = Stream(base + '/api/logs/events', cookie())
    active.matching(lambda page: 'records' in page)
    request('/api/auth/logout', {})
    assert active.ended.wait(5), 'logout must close the active subscription'
    active.close()
    try:
        request('/api/logs/events')
        raise AssertionError('Expected rejected reconnect')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    login()
    expiry = Stream(base + '/api/logs/events', cookie())
    expiry.matching(lambda page: 'records' in page)
    # Expire the persisted Redis sessions while the HTTP connection remains open.
    docker('exec', REDIS, 'redis-cli', 'FLUSHDB')
    assert expiry.ended.wait(5), 'session expiry must close the active subscription'
    expiry.close()
    try:
        request('/api/logs/events')
        raise AssertionError('Expected rejected expired-session reconnect')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    print('PASS logout, persisted-session expiry and rejected reconnect', flush=True)

    login()
    prior_cursor = request('/api/logs')['cursor']
    docker('stop', '-t', '20', NAME)
    assert docker('inspect', '-f', '{{.State.ExitCode}}', NAME) == '143'
    docker('start', NAME)
    ready()
    login()
    assert docker('exec', NAME, 'cat', '/run/dmdl/logs/run-id') != run
    restarted = Stream(base + '/api/logs/events?' + urllib.parse.urlencode({'cursor': prior_cursor}), cookie())
    restarted.matching(lambda page: page.get('gap') == 'reset')
    restarted.close()
    print('PASS graceful stop, existing-container restart, new run and cursor reset', flush=True)

    for source in ['nginx', 'backend', 'aria2']:
        if source == 'nginx':
            code = "import os,signal; os.kill(int(open('/run/nginx.pid').read()),signal.SIGKILL)"
        else:
            executable = 'java' if source == 'backend' else 'aria2c'
            code = "import os,pathlib,signal; [os.kill(int(p.name),signal.SIGKILL) for p in pathlib.Path('/proc').iterdir() if p.name.isdigit() and (p/'comm').exists() and (p/'comm').read_text().strip()==" + repr(executable) + ']'
        docker('exec', NAME, 'python3', '-c', code)
        eventually(lambda: docker('inspect', '-f', '{{.State.Running}}', NAME) == 'false', 30)
        assert docker('inspect', '-f', '{{.State.ExitCode}}', NAME) == '137'
        docker('start', NAME)
        ready()
    print('PASS nginx, backend and aria2 failure propagation with collectors active', flush=True)

    # Recreate only the disposable application container to exercise default access logging and error-level aria2 output.
    docker('rm', '-f', NAME)
    docker('run', '-d', '--name', NAME, '--network', NAME, '-p', '127.0.0.1::8080',
           '-e', 'DMDL_REDIS_HOST=' + REDIS, '-e', 'DMDL_ARIA2_LOG_LEVEL=error', IMAGE)
    port = docker('port', NAME, '8080/tcp').split(':')[-1]
    base = 'http://127.0.0.1:' + port
    ready()
    request('/api/auth/register', {'username': 'fixture', 'password': 'fixture-password'})
    login()
    for _ in range(3):
        request('/api/logs')
    assert request('/api/logs?source=nginx&levels=INFO')['records'] == []
    assert request('/api/logs?source=aria2&levels=INFO')['records'] == []
    inject('2026/11/15 10:00:00 [error] fixture nginx error', source='nginx')
    assert eventually(lambda: request('/api/logs?source=nginx&levels=ERROR')['records'])
    assert 'fixture nginx error' in docker('logs', NAME)
    print('PASS nginx access default-off, console forwarding and aria2 error-level capture', flush=True)

    # Make the collector's next segment unwritable while its service stays up.
    docker('exec', NAME, 'python3', '-c', "from pathlib import Path; Path('/run/dmdl/logs/backend/00000000000000000002.jsonl').mkdir()")
    inject('2026-11-15T10:00:00Z INFO sink failure ' + 'x' * 40000, count=300)
    eventually(lambda: any(source['state'] == 'failed' for source in request('/api/logs')['sources']))
    assert docker('inspect', '-f', '{{.State.Running}}', NAME) == 'true'
    print('PASS capture failure metadata while services and console remain active', flush=True)


try:
    main()
except Exception:
    try:
        print(docker('inspect', '-f', '{{.State.Status}} exit={{.State.ExitCode}}', NAME), flush=True)
        print(docker('logs', '--tail', '5', NAME)[-3000:], flush=True)
    except subprocess.CalledProcessError:
        pass
    raise
finally:
    for resource in [NAME, REDIS]:
        subprocess.run(['docker', 'rm', '-f', resource], capture_output=True)
    subprocess.run(['docker', 'network', 'rm', NAME], capture_output=True)
