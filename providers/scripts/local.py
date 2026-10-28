#!/usr/bin/env python3
"""Start/stop only this lab's Java processes; MongoDB is managed separately by Compose."""
import json, os, signal, socket, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.runtime'
SPECS = {
    'stripe': (18101, 5005, ROOT/'spring-provider/target/spring-provider-1.0.0-SNAPSHOT.jar', ['--spring.profiles.active=stripe']),
    'adyen': (18102, 5006, ROOT/'spring-provider/target/spring-provider-1.0.0-SNAPSHOT.jar', ['--spring.profiles.active=adyen']),
    'paypal': (18103, 5007, ROOT/'paypal/target/quarkus-app/quarkus-run.jar', []),
}

def java():
    configured = os.environ.get('JAVA_HOME')
    if configured:
        candidate = Path(configured)/'bin/java'
        if not candidate.is_file(): raise RuntimeError('JAVA_HOME does not contain bin/java')
        return str(candidate)
    bundled = Path('/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java')
    return str(bundled) if bundled.is_file() else 'java'

def command(pid):
    return subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout.strip()

def owned(name, info):
    return str(SPECS[name][2]) in command(info['pid']) and f'address=127.0.0.1:{SPECS[name][1]}' in command(info['pid'])

def saved():
    file = RUNTIME/'processes.json'
    return json.loads(file.read_text()) if file.exists() else {}

def healthy(name):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{SPECS[name][0]}/health', timeout=1) as response:
            body = json.load(response)
            return body.get('status') == 'UP' and body.get('provider') == name
    except Exception:
        return False

def stop():
    items = saved()
    for name, info in items.items():
        if owned(name, info): os.kill(info['pid'], signal.SIGTERM)
    deadline = time.monotonic()+20
    while time.monotonic() < deadline and any(owned(name, info) for name, info in items.items()): time.sleep(.2)
    remaining = [name for name, info in items.items() if owned(name, info)]
    if remaining: raise RuntimeError('Still shutting down: '+', '.join(remaining)+'. Inspect logs; no forced termination used.')
    (RUNTIME/'processes.json').unlink(missing_ok=True)
    print('Stopped the locally managed provider processes. MongoDB remains running.')

def start():
    if any(owned(name, info) for name, info in saved().items()):
        raise RuntimeError('Some lab services are already running. Use status or stop before start.')
    for name, (port, debug, artifact, args) in SPECS.items():
        if not artifact.exists(): raise RuntimeError(f'Build first: mvn -DskipTests package (missing {artifact.name})')
        for check in (port, debug):
            with socket.socket() as test:
                try: test.bind(('127.0.0.1', check))
                except OSError: raise RuntimeError(f'Port {check} is occupied. Stop the old {name} run before starting another.')
    RUNTIME.mkdir(exist_ok=True)
    items = {}
    try:
        for name, (port, debug, artifact, args) in SPECS.items():
            with (RUNTIME/(name+'.log')).open('a') as log:
                process = subprocess.Popen([java(), f'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{debug}',
                        '-jar', str(artifact), *args], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            items[name] = {'pid': process.pid}
            (RUNTIME/'processes.json').write_text(json.dumps(items, indent=2)+'\n')
        deadline = time.monotonic()+40
        while time.monotonic() < deadline:
            if all(healthy(name) for name in SPECS):
                status(); return
            if any(not owned(name, info) for name, info in items.items()):
                raise RuntimeError('A provider exited; inspect .runtime logs.')
            time.sleep(.3)
        raise RuntimeError('Startup health checks timed out; inspect .runtime logs.')
    except Exception:
        stop(); raise

def status():
    for name, (port, debug, artifact, args) in SPECS.items():
        print(f'{name}: {"UP" if healthy(name) else "DOWN"}; HTTP {port}; IntelliJ attach port {debug}')

if __name__ == '__main__':
    try:
        action = sys.argv[1] if len(sys.argv)>1 else 'status'
        if action not in ('start','stop','status'): raise RuntimeError('Usage: python3 scripts/local.py start|stop|status')
        {'start':start,'stop':stop,'status':status}[action]()
    except Exception as error:
        print(str(error), file=sys.stderr); sys.exit(1)
