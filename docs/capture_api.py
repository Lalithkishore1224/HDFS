"""Runs a real session against the live API and records everything needed to document it."""
import hashlib, http.cookiejar, json, os, subprocess, time, urllib.error, urllib.request, uuid

BASE = 'http://localhost:8080'
DATA = os.environ.get('DRIVE_DATA', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'backend', 'data'))
LOG = os.environ.get('BACKEND_LOG', 'backend.log')   # the server's log file, started with -Dlogging.level.org.hibernate.SQL=DEBUG
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture.json')

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
anonymous = urllib.request.build_opener()


def csrf():
    return next((c.value for c in jar if c.name == 'XSRF-TOKEN'), None)


def read_sql(offset):
    with open(LOG, 'r', errors='replace') as f:
        f.seek(offset)
        lines = f.read().splitlines()
    out = []
    for line in lines:
        if 'org.hibernate.SQL' in line and ' : ' in line:
            out.append(line.split(' : ', 1)[1].strip())
    return out


PG_CONTAINER = os.environ.get('PG_CONTAINER', 'drive-postgres')
TABLES = ('accounts', 'folders', 'stored_files', 'access_logs')


def psql(sql):
    """Runs one query inside the PostgreSQL container and returns its single-value output."""
    result = subprocess.run(
        ['docker', 'exec', PG_CONTAINER, 'psql', '-U', 'drive', '-d', 'drive', '-At', '-c', sql],
        capture_output=True, text=True, check=True)
    return result.stdout.strip()


def disk():
    """Every chunk and manifest on every storage node, as {"node-1/hot/<key>": size}."""
    snap = {}
    files = os.path.join(DATA, 'files')
    for node in sorted(os.listdir(files)):
        if not node.startswith('node-'):
            continue
        for tier in ('hot', 'cold'):
            d = os.path.join(files, node, tier)
            if os.path.isdir(d):
                for name in os.listdir(d):
                    snap[f'{node}/{tier}/{name}'] = os.path.getsize(os.path.join(d, name))
    return snap


def counts():
    parts = ', '.join(f"'{t}', (select count(*) from {t})" for t in TABLES)
    return json.loads(psql(f"select json_build_object({parts})"))


def row(table, ident):
    """The row with this id as a dict, straight from PostgreSQL."""
    assert table in TABLES
    uuid.UUID(ident)  # refuses anything that is not a UUID before it reaches SQL
    out = psql(f"select row_to_json(t) from (select * from {table} where id = '{ident}') t")
    return json.loads(out) if out else None


def multipart(fields, file_field, filename, content, ctype):
    boundary = '----drive' + uuid.uuid4().hex[:16]
    body = b''
    for k, v in fields.items():
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
             f'Content-Type: {ctype}\r\n\r\n').encode() + content + b'\r\n' + f'--{boundary}--\r\n'.encode()
    return body, f'multipart/form-data; boundary={boundary}'


steps = []


def call(step_id, title, method, path, *, json_body=None, upload=None, auth=True, shows=None, curl=None, note=None):
    headers = {}
    data = None
    shown_body = None
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers['Content-Type'] = 'application/json'
        shown_body = json_body
    if upload is not None:
        data, ctype = multipart(upload['fields'], 'file', upload['filename'], upload['content'], upload['type'])
        headers['Content-Type'] = ctype
        shown_body = {'multipart': {**upload['fields'], 'file': f"@{upload['filename']} ({upload['type']}, {len(upload['content'])} bytes)"}}
    token = csrf() if auth else None
    if method != 'GET' and token:
        headers['X-XSRF-TOKEN'] = token

    before_counts, before_disk = counts(), disk()
    offset = os.path.getsize(LOG)
    started = time.perf_counter()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    op = opener if auth else anonymous
    try:
        resp = op.open(req)
        status, reason, rheaders, raw = resp.status, resp.reason, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        status, reason, rheaders, raw = e.code, e.reason, e.headers, e.read()
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    time.sleep(0.25)

    sha = hashlib.sha256(raw).hexdigest()
    text = raw.decode('utf-8', errors='replace')
    try:
        body, is_json = json.loads(text), True
    except ValueError:
        body, is_json = (text if len(text) < 700 else text[:700] + '…'), False

    shown_headers = [('Accept', '*/*')]
    if auth and any(c.name == 'JSESSIONID' for c in jar):
        shown_headers.append(('Cookie', 'JSESSIONID=<your session>'))
    for k, v in headers.items():
        shown_headers.append((k, '<value of the XSRF-TOKEN cookie>' if k == 'X-XSRF-TOKEN' else (v.split(';')[0] + '; boundary=…' if v.startswith('multipart') else v)))

    keep = ('Content-Type', 'Content-Disposition', 'Content-Length', 'X-Content-Type-Options', 'Cache-Control', 'Set-Cookie')
    resp_headers = []
    for k in keep:
        v = rheaders.get(k)
        if v:
            if k == 'Set-Cookie' and 'JSESSIONID' in v:
                v = 'JSESSIONID=<new session>; Path=/; HttpOnly; SameSite=Lax'
            if k == 'Set-Cookie' and 'XSRF-TOKEN' in v:
                v = 'XSRF-TOKEN=<token>; Path=/'
            resp_headers.append((k, v))

    after_disk = disk()
    added = {k: v for k, v in after_disk.items() if k not in before_disk}
    removed = {k: v for k, v in before_disk.items() if k not in after_disk}
    step = {
        'id': step_id, 'title': title, 'note': note,
        'request': {'method': method, 'path': path, 'headers': shown_headers, 'body': shown_body, 'curl': curl},
        'response': {'status': status, 'reason': reason, 'headers': resp_headers, 'body': body, 'is_json': is_json, 'ms': elapsed_ms,
                     'sha256': sha, 'bytes': len(raw)},
        'sql': read_sql(offset),
        'counts_before': before_counts, 'counts_after': counts(),
        'disk_added': added, 'disk_removed': removed,
        'rows': {},
    }
    if shows:
        step['rows'] = shows(body)
    steps.append(step)
    return body


def main():
    opener.open(BASE + '/api/auth/config').read()

    call('config', 'Ask how people can sign in', 'GET', '/api/auth/config', auth=False,
         curl='curl -s "$BASE/api/auth/config"')
    me = call('login', 'Sign in (development login)', 'POST', '/api/auth/dev-login',
              json_body={'email': 'admin@dev.local', 'name': 'Dev Admin'},
              curl='curl -s -X POST "$BASE/api/auth/dev-login" \\\n  -b cookies.txt -c cookies.txt \\\n  -H "X-XSRF-TOKEN: $TOKEN" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"email":"admin@dev.local","name":"Dev Admin"}\'',
              shows=lambda b: {'accounts': row('accounts', b['id'])})
    call('me', 'Who am I?', 'GET', '/api/me', curl='curl -s "$BASE/api/me" -b cookies.txt')

    stamp = time.strftime('%H%M%S')
    top = f'Invoices {stamp}'
    invoices = call('create-folder', 'Create a folder', 'POST', '/api/folders',
                    json_body={'name': top},
                    curl='curl -s -X POST "$BASE/api/folders" \\\n  -b cookies.txt -c cookies.txt \\\n  -H "X-XSRF-TOKEN: $TOKEN" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"name":"' + top + '"}\'',
                    shows=lambda b: {'folders': row('folders', b['id'])})

    year = call('create-subfolder', 'Create a sub-folder inside it', 'POST', '/api/folders',
                json_body={'name': '2026', 'parentId': invoices['id']},
                curl='curl -s -X POST "$BASE/api/folders" \\\n  -b cookies.txt -c cookies.txt \\\n  -H "X-XSRF-TOKEN: $TOKEN" \\\n  -H "Content-Type: application/json" \\\n  -d \'{"name":"2026","parentId":"' + invoices['id'] + '"}\'',
                shows=lambda b: {'folders': row('folders', b['id'])})

    # about 600 KB, so the file is cut into three chunks (chunks are 256 KiB)
    header = b'invoice,customer,amount,status\n'
    rows_ = b''.join(f'INV-2026-{i:05d},Customer {i % 97},{(i * 37) % 5000}.{i % 100:02d},{"paid" if i % 3 else "due"}\n'.encode()
                     for i in range(1, 17000))
    csv = header + rows_
    uploaded = call('upload', 'Upload a file into that sub-folder', 'POST', '/api/files',
                    upload={'fields': {'folderId': year['id']}, 'filename': 'invoices-march.csv', 'content': csv, 'type': 'text/csv'},
                    curl='curl -s -X POST "$BASE/api/files" \\\n  -b cookies.txt -c cookies.txt \\\n  -H "X-XSRF-TOKEN: $TOKEN" \\\n  -F "file=@invoices-march.csv;type=text/csv" \\\n  -F "folderId=' + year['id'] + '"',
                    shows=lambda b: {'stored_files': row('stored_files', b['id'])})
    original_sha = hashlib.sha256(csv).hexdigest()

    placement = call('placement', 'Ask where the pieces of the file are kept (admin)', 'GET',
                     f"/api/admin/storage/files/{uploaded['id']}/placement",
                     curl=f'curl -s "$BASE/api/admin/storage/files/{uploaded["id"]}/placement" -b cookies.txt')
    call('nodes', 'See the storage nodes (admin)', 'GET', '/api/admin/storage/nodes',
         curl='curl -s "$BASE/api/admin/storage/nodes" -b cookies.txt')

    call('list-files', 'Get all the files in a folder', 'GET', f"/api/files?folderId={year['id']}",
         curl=f'curl -s "$BASE/api/files?folderId={year["id"]}" -b cookies.txt')
    call('list-folders', 'Get the folders inside a folder', 'GET', f"/api/folders?parentId={invoices['id']}",
         curl=f'curl -s "$BASE/api/folders?parentId={invoices["id"]}" -b cookies.txt')
    call('search', 'Search all files by name', 'GET', '/api/files?search=invoices-march',
         curl='curl -s "$BASE/api/files?search=invoices-march" -b cookies.txt')
    call('path', 'Get the breadcrumb path of a folder', 'GET', f"/api/folders/{year['id']}/path",
         curl=f'curl -s "$BASE/api/folders/{year["id"]}/path" -b cookies.txt')
    call('storage', 'How much space am I using?', 'GET', '/api/storage', curl='curl -s "$BASE/api/storage" -b cookies.txt')
    call('view', 'View the file (inline)', 'GET', f"/api/files/{uploaded['id']}/view",
         curl=f'curl -s "$BASE/api/files/{uploaded["id"]}/view" -b cookies.txt')
    healthy = call('download', 'Download the file (attachment)', 'GET', f"/api/files/{uploaded['id']}/download",
                   curl=f'curl -s -OJ "$BASE/api/files/{uploaded["id"]}/download" -b cookies.txt')
    steps[-1]['original_sha256'] = original_sha

    # --- two storage nodes that hold a copy of the first chunk stop working
    holders = [n['name'] for n in placement['chunks'][0]['nodes']][:2]
    names = ','.join(holders)
    call('node-down', 'Two storage nodes stop working (admin)', 'POST', f'/api/admin/storage/nodes/offline?names={names}',
         curl=f'curl -s -X POST "$BASE/api/admin/storage/nodes/offline?names={names}" \\\n  -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $TOKEN"')
    call('download-degraded', 'Download the same file while those two nodes are down', 'GET',
         f"/api/files/{uploaded['id']}/download",
         curl=f'curl -s -OJ "$BASE/api/files/{uploaded["id"]}/download" -b cookies.txt')
    steps[-1]['original_sha256'] = original_sha
    steps[-1]['holders_down'] = holders
    call('node-up', 'The two nodes come back (admin)', 'POST', f'/api/admin/storage/nodes/online?names={names}',
         curl=f'curl -s -X POST "$BASE/api/admin/storage/nodes/online?names={names}" \\\n  -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $TOKEN"')
    steps[-1]['holders_down'] = holders

    call('err-duplicate', 'Error: a folder with that name already exists', 'POST', '/api/folders',
         json_body={'name': top.lower()},
         curl='curl -s -X POST "$BASE/api/folders" -b cookies.txt -H "X-XSRF-TOKEN: $TOKEN" \\\n  -H "Content-Type: application/json" -d \'{"name":"' + top.lower() + '"}\'')
    call('err-badname', 'Error: a folder name that is not allowed', 'POST', '/api/folders',
         json_body={'name': 'a/b'},
         curl='curl -s -X POST "$BASE/api/folders" -b cookies.txt -H "X-XSRF-TOKEN: $TOKEN" \\\n  -H "Content-Type: application/json" -d \'{"name":"a/b"}\'')
    call('err-anon', 'Error: calling without being signed in', 'GET', '/api/files', auth=False,
         curl='curl -s -i "$BASE/api/files"   # no cookie')

    call('admin-users', 'See all the accounts (admin)', 'GET', '/api/admin/users',
         curl='curl -s "$BASE/api/admin/users" -b cookies.txt')
    call('admin-stats', 'See the storage numbers (admin)', 'GET', '/api/admin/stats',
         curl='curl -s "$BASE/api/admin/stats" -b cookies.txt')
    call('insights', 'Ask for a plain-language storage summary (admin)', 'POST', '/api/admin/insights',
         curl='curl -s -X POST "$BASE/api/admin/insights" -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $TOKEN"')

    call('delete-folder', 'Delete the folder (and everything inside it)', 'DELETE', f"/api/folders/{invoices['id']}",
         curl=f'curl -s -X DELETE "$BASE/api/folders/{invoices["id"]}" \\\n  -b cookies.txt -c cookies.txt \\\n  -H "X-XSRF-TOKEN: $TOKEN"')

    json.dump({'generated': time.strftime('%Y-%m-%d %H:%M:%S'), 'steps': steps,
               'ids': {'invoices': invoices['id'], 'year': year['id'], 'file': uploaded['id']},
               'file_bytes': len(csv)}, open(OUT, 'w'), indent=1)
    print('captured', len(steps), 'steps ->', OUT)
    for s in steps:
        print(f"  {s['id']:18} {s['request']['method']:6} {s['request']['path'][:52]:52} -> {s['response']['status']}  sql={len(s['sql'])}  disk+{len(s['disk_added'])}-{len(s['disk_removed'])}")


main()
