#!/usr/bin/env python3
"""Uploads multi-chunk files, then fails nodes and checks every file still downloads byte-for-byte:
every single node, every pair, every triple (all at the same time), and nodes failing one after another
with automatic repair in between. Writes docs/availability-proof.json."""
import hashlib, http.cookiejar, itertools, json, os, urllib.request, urllib.error, uuid

BASE = os.environ.get('BASE', 'http://localhost:8080')
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def token():
    return next((c.value for c in jar if c.name == 'XSRF-TOKEN'), '')

def call(method, path, body=None, headers=None, raw=False):
    req = urllib.request.Request(BASE + path, data=body, method=method, headers={'X-XSRF-TOKEN': token(), **(headers or {})})
    try:
        with opener.open(req) as r:
            data = r.read()
            return r.status, (data if raw else (json.loads(data) if data else None))
    except urllib.error.HTTPError as e:
        return e.code, None

def upload(name, content):
    b = uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f'Content-Type: application/octet-stream\r\n\r\n').encode() + content + f'\r\n--{b}--\r\n'.encode()
    status, data = call('POST', '/api/files', body, {'Content-Type': f'multipart/form-data; boundary={b}'})
    assert status in (200, 201), status
    return data['id']

def offline(n): call('POST', f'/api/admin/storage/nodes/{n}/offline')
def online(n): call('POST', f'/api/admin/storage/nodes/{n}/online')

call('GET', '/api/auth/config')
call('POST', '/api/auth/login', json.dumps({'username': os.environ['DRIVE_USER'], 'password': os.environ['DRIVE_PASSWORD']}).encode(),
     {'Content-Type': 'application/json'})   # an administrator account

_, cluster = call('GET', '/api/admin/storage/nodes')
nodes = [n['name'] for n in cluster['nodes']]
sizes = {'thesis-draft.bin': 300_000, 'budget-2026.bin': 520_000, 'lecture-notes.bin': 700_000,
         'photo-album.bin': 900_000, 'dataset.bin': 1_100_000, 'minutes.bin': 1_300_000}
files = []
for name, size in sizes.items():
    content = os.urandom(size)
    fid = upload(name, content)
    _, place = call('GET', f'/api/admin/storage/files/{fid}/placement')
    files.append({'name': name, 'id': fid, 'bytes': size, 'sha': hashlib.sha256(content).hexdigest(),
                  'chunks': [{'index': c['index'], 'nodes': [n['name'] for n in c['nodes']]} for c in place['chunks']]})

def intact_count():
    ok = 0
    for f in files:
        status, data = call('GET', f"/api/files/{f['id']}/download", raw=True)
        ok += status == 200 and hashlib.sha256(data).hexdigest() == f['sha']
    return ok

_, after = call('GET', '/api/admin/storage/nodes')
simultaneous = {}
for k in (1, 2, 3):
    results = []
    for combo in itertools.combinations(nodes, k):
        call('POST', '/api/admin/storage/nodes/offline?names=' + ','.join(combo))   # all at once
        results.append({'offline': list(combo), 'intact': intact_count()})
        call('POST', '/api/admin/storage/nodes/online?names=' + ','.join(combo))
    simultaneous[k] = {'cases': len(results), 'allIntactCases': sum(r['intact'] == len(files) for r in results),
                       'fewestIntact': min(r['intact'] for r in results), 'results': results}

sequential = []
for n in nodes[:-1]:
    offline(n)
    _, st = call('GET', '/api/admin/storage/nodes')
    sequential.append({'failed': n, 'nodesOnline': sum(x['online'] for x in st['nodes']), 'intact': intact_count(),
                       'underReplicated': st['underReplicatedChunks']})
for n in nodes[:-1]:
    online(n)

for f in files:
    call('DELETE', f"/api/files/{f['id']}")

out = {'nodes': nodes, 'replicationFactor': cluster['replicationFactor'], 'chunkBytes': cluster['chunkBytes'],
       'files': files, 'simultaneous': simultaneous, 'sequential': sequential}
json.dump(out, open('docs/availability-proof.json', 'w'), indent=2)

print(f"{len(nodes)} nodes, {cluster['replicationFactor']} copies of every chunk, chunk size {cluster['chunkBytes']} bytes\n")
print('Files (each cut into chunks; chunk 0 shown):')
for f in files:
    print(f"  {f['name']:<20} {f['bytes']:>8} B  {len(f['chunks'])} chunks   chunk 0 on {', '.join(f['chunks'][0]['nodes'])}")
print('\nNodes failing at the same time (files intact out of %d):' % len(files))
for k, v in simultaneous.items():
    print(f"  {k} node(s) offline: {v['allIntactCases']} of {v['cases']} combinations keep all files intact (worst case {v['fewestIntact']} of {len(files)})")
print('\nNodes failing one after another (automatic repair in between):')
for s in sequential:
    print(f"  {s['failed']} fails -> {s['nodesOnline']} nodes online, {s['intact']} of {len(files)} files intact")
