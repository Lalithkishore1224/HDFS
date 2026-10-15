"""Builds api-walkthrough.html from capture.json (produced by capture_api.py).

Every request, response, SQL statement, database row and storage change in the page was recorded from
the running server; only the plain-language explanations are written by hand (EXPLAIN below).
"""
import html
import json
import os
import re
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = json.load(open(os.path.join(HERE, 'capture.json')))
STEPS = {s['id']: s for s in DATA['steps']}
OUT = os.path.join(HERE, 'api-walkthrough.html')
esc = html.escape
NODES = ['node-1', 'node-2', 'node-3', 'node-4', 'node-5']


# ------------------------------------------------------------------ rendering helpers

TOKEN = re.compile(r'("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?')


def highlight_json(obj):
    text = json.dumps(obj, indent=2, ensure_ascii=False)
    out, last = [], 0
    for m in TOKEN.finditer(text):
        out.append(esc(text[last:m.start()]))
        tok = m.group(0)
        if m.group(1) and m.group(2):
            out.append(f'<span class="j-key">{esc(m.group(1))}</span>{esc(m.group(2))}')
        elif m.group(1):
            out.append(f'<span class="j-str">{esc(tok)}</span>')
        elif m.group(3):
            out.append(f'<span class="j-lit">{esc(tok)}</span>')
        else:
            out.append(f'<span class="j-num">{esc(tok)}</span>')
        last = m.end()
    out.append(esc(text[last:]))
    return ''.join(out)


def code(inner_html, label=None, copy_text=None):
    copy = f'<button class="copy" data-copy="{esc(copy_text, quote=True)}">Copy</button>' if copy_text else ''
    lab = f'<span class="code-label">{esc(label)}</span>' if label else ''
    return f'<div class="code"><div class="code-bar">{lab}{copy}</div><pre>{inner_html}</pre></div>'


def status_class(code_):
    return 'ok' if code_ < 300 else ('warn' if code_ < 500 else 'bad')


REASONS = {200: 'OK', 201: 'Created', 204: 'No Content', 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden',
           404: 'Not Found', 409: 'Conflict', 413: 'Payload Too Large', 415: 'Unsupported Media Type',
           503: 'Service Unavailable'}
PLAIN_STATUS = {200: 'It worked', 201: 'Created', 204: 'Done, nothing to show', 400: 'Your input was not valid',
                401: 'You are not signed in', 403: 'You are not allowed', 404: 'Not found', 409: 'Already exists',
                413: 'Too big', 415: 'Cannot be shown', 503: 'Temporarily unavailable'}


def request_http(step):
    r = step['request']
    lines = [f'<span class="verb">{esc(r["method"])}</span> {esc(r["path"])} HTTP/1.1', 'Host: localhost:8080']
    for k, v in r['headers']:
        lines.append(f'<span class="hdr">{esc(k)}</span>: {esc(v)}')
    body = ''
    if r['body'] is not None:
        if 'multipart' in r['body']:
            parts = r['body']['multipart']
            body = '\n' + '\n'.join(f'<span class="j-key">{esc(k)}</span> = {esc(str(v))}' for k, v in parts.items())
        else:
            body = '\n\n' + highlight_json(r['body'])
    return '\n'.join(lines) + body


def response_http(step):
    r = step['response']
    reason = REASONS.get(r['status'], r['reason'])
    lines = [f'HTTP/1.1 <span class="status {status_class(r["status"])}">{r["status"]} {esc(str(reason))}</span>']
    for k, v in r['headers']:
        if k != 'Cache-Control':
            lines.append(f'<span class="hdr">{esc(k)}</span>: {esc(v)}')
    if r['body'] not in ('', None, []):
        body = '\n\n' + (highlight_json(r['body']) if r['is_json'] else esc(str(r['body'])).replace('\\n', '\n'))
    elif r['body'] == []:
        body = '\n\n[]   <span class="muted">(an empty list)</span>'
    else:
        body = '\n\n<span class="muted">(empty body)</span>'
    return '\n'.join(lines) + body


def sql_block(step):
    if not step['sql']:
        return '<p class="muted small">No database statements ran.</p>'
    items = []
    for q in step['sql']:
        kind = q.split()[0].lower()
        flat = re.sub(r'\s+', ' ', q)
        if kind == 'select' and len(flat) > 140:
            flat = re.sub(r'select (.{60,}?) from ', 'select … from ', flat)
        items.append(f'<li><span class="sql-kind sql-{kind}">{kind}</span><code>{esc(flat)}</code></li>')
    return f'<ol class="sql">{"".join(items)}</ol>'


TABLE_WORDS = {'accounts': 'accounts (people)', 'folders': 'folders', 'stored_files': 'files',
               'access_logs': 'open history'}


def counters(step):
    changed = []
    for table, before in step['counts_before'].items():
        after = step['counts_after'][table]
        if after != before:
            changed.append(f'<span class="counter changed"><b>{esc(TABLE_WORDS.get(table, table))}</b> {before} → {after}</span>')
    return ''.join(changed) or '<span class="counter">Nothing was added or removed in the database</span>'


def pretty_value(v):
    if v is None or v == '':
        return '<span class="null">empty</span>'
    if isinstance(v, (int, float)):
        return f'<span class="j-num">{esc(str(v))}</span>'
    return f'<span class="j-str">{esc(str(v))}</span>'


def rows_table(step):
    out = []
    for table, row in step['rows'].items():
        if not row:
            continue
        cells = ''.join(f'<tr><th>{esc(k)}</th><td>{pretty_value(v)}</td></tr>' for k, v in row.items())
        out.append(f'<div class="rowcard"><div class="rowcard-title">The new database record, in the <b>{esc(TABLE_WORDS.get(table, table))}</b> table</div>'
                   f'<table class="kv">{cells}</table></div>')
    return ''.join(out)


def piece_label(suffix):
    if suffix == 'manifest':
        return 'manifest (the "table of contents")'
    return f'piece {suffix[1:]}'


def storage_block(step):
    """What changed on the storage nodes, drawn as a grid: which node holds which piece."""
    def group(paths):
        grid = OrderedDict()
        per_node = {n: 0 for n in NODES}
        for path in paths:
            node, tier, key = path.split('/', 2)
            fid, _, suffix = key.partition('.')
            grid.setdefault((fid[:8], suffix), set()).add(node)
            per_node[node] = per_node.get(node, 0) + 1
        return grid, per_node

    parts = []
    for paths, kind, verb in ((step['disk_added'], 'added', 'stored'), (step['disk_removed'], 'removed', 'removed')):
        if not paths:
            continue
        grid, per_node = group(paths)
        if len(grid) > 8:
            counts = ', '.join(f'{n}: {c}' for n, c in per_node.items() if c)
            parts.append(f'<div class="store {kind}"><b>Storage nodes:</b> {len(paths)} pieces were {verb} across the nodes '
                         f'({esc(counts)}). This covers every file in the drive, not just one.</div>')
            continue
        head = ''.join(f'<th>{n.replace("node-", "node ")}</th>' for n in NODES)
        trs = ''
        for (fid, suffix), holders in sorted(grid.items(), key=lambda kv: (kv[0][0], kv[0][1])):
            tds = ''.join(f'<td class="{"has" if n in holders else ""}">{"●" if n in holders else ""}</td>' for n in NODES)
            trs += f'<tr><th>file …{fid[:4]} · {esc(piece_label(suffix))}</th>{tds}</tr>'
        word = 'New pieces stored' if kind == 'added' else 'Pieces removed'
        parts.append(f'<div class="store {kind}"><b>Storage nodes: {word}.</b> A dot means that node holds a copy.'
                     f'<table class="grid"><thead><tr><th></th>{head}</tr></thead><tbody>{trs}</tbody></table></div>')
    if not parts:
        return '<div class="store none"><b>Storage nodes:</b> nothing changed. No file contents were written or removed.</div>'
    return ''.join(parts)


def inside_list(items):
    return '<ol class="inside">' + ''.join(f'<li>{i}</li>' for i in items) + '</ol>'


# ------------------------------------------------------------------ hand-written, plain-language explanations

C = lambda s: f'<code>{s}</code>'  # noqa: E731

ANYONE, MEMBER, ADMIN = 'Anyone', 'A signed-in account that an administrator has approved', 'An administrator'

EXPLAIN = {
    'config': {
        'who': ANYONE,
        'lead': 'Asks the server whether new accounts can be created. The sign-in page uses this to decide whether to show the "Create account" tab.',
        'reply': 'One yes/no answer: is creating accounts open.',
        'inside': [
            'The server answers straight away. This is the one call that needs no sign-in.',
            'It also hands your browser a small secret called the <b>CSRF token</b> (in a cookie). Later calls that change things must show it, which stops other websites from tricking your browser into acting for you.',
        ],
    },
    'register': {
        'who': ANYONE,
        'lead': 'Creates an account from a username and a password, and signs the new person in.',
        'reply': 'The new account: username, role and approval status (PENDING until an administrator approves it).',
        'inside': [
            '<b>Checks.</b> The username must be 3 to 30 characters (letters, digits, dot, dash, underscore) and not taken; the password must be at least 8 characters. Otherwise the answer is 400 or 409.',
            '<b>The password is never stored.</b> Only a one-way <b>BCrypt hash</b> is saved, so even the database does not reveal it.',
            'The account starts as <b>PENDING</b>, which means "waiting for an administrator". Usernames on the administrator list become <b>ADMIN</b> and <b>APPROVED</b> straight away.',
            'The server starts a <b>session</b>: it sends back a cookie called <code>JSESSIONID</code> that your browser shows on every later call.',
        ],
    },
    'login': {
        'who': ANYONE,
        'lead': 'Signs you in with your username and password.',
        'reply': 'Your account: username, role and approval status.',
        'inside': [
            'The server finds the account and checks the password against the stored hash. A wrong username and a wrong password give the same answer (401), so nobody can discover which usernames exist.',
            'After 5 wrong passwords in a row for one username, further attempts are refused for a minute (429), which slows down guessing.',
            'A successful sign-in starts a fresh <b>session</b> with a new <code>JSESSIONID</code> cookie, so an old session id is useless afterwards.',
        ],
    },
    'me': {
        'who': 'Anyone signed in',
        'lead': 'Asks "who am I and am I approved yet?". The app uses it to choose between the sign-in page, the "waiting for approval" page and the drive.',
        'reply': 'The same account description as at sign-in.',
        'inside': [
            'The server finds your account from the session cookie and reads it fresh from the database.',
            'Because it is read fresh every time, an approval or rejection by the administrator takes effect immediately, even in a session that is already open.',
        ],
    },
    'create-folder': {
        'who': MEMBER,
        'lead': 'Creates an empty folder at the top level of your drive.',
        'reply': 'The new folder: its id, name, and how many items it holds (0).',
        'inside': [
            '<b>Safety checks.</b> The server confirms the CSRF token, recognises your session, and checks your account is approved. If not, it answers 403.',
            '<b>Name check.</b> Names cannot be blank or contain <code>/</code> or <code>\\</code>, and two folders in the same place cannot share a name (capital letters do not count as different).',
            '<b>Save.</b> One new row is added to the <b>folders</b> table with your account as the owner.',
        ],
        'callout': ('A folder is only a database record.',
                    'Look at the storage grid: nothing was written to the storage nodes. A folder exists only as a row in the database, which is why moving or renaming a folder is instant and never copies any file data.'),
    },
    'create-subfolder': {
        'who': MEMBER,
        'lead': 'Creates a folder <em>inside</em> another folder. It is the same call with one extra value, <code>parentId</code>.',
        'reply': 'The new folder, now with the parent\'s id filled in.',
        'inside': [
            'Everything from the previous step applies.',
            'The server first checks that the parent folder exists <em>and is yours</em>. A folder that is missing, or belongs to someone else, gives the same answer (404), so nobody can probe other people\'s folders.',
            'The only difference in the saved record is <code>parent_id</code>. That one column is the whole folder tree: a folder\'s path is found by following parents upwards.',
        ],
    },
    'upload': {
        'who': MEMBER,
        'lead': 'Sends a file (about 650 KB here) into a folder. The file travels as a "form upload" with the file itself and the id of the folder to put it in.',
        'reply': 'A description of the new file: id, name, folder, size, type and dates. Nothing about where its bytes are kept.',
        'inside': [
            '<b>Checks.</b> The folder must be yours, and the file must fit in your storage allowance (otherwise 413).',
            '<b>Cut into pieces.</b> The server cuts the file into <b>chunks</b> of 256 KiB. This file became 3 chunks (the last one is smaller), plus a tiny <b>manifest</b> that says "3 chunks, 668787 bytes".',
            '<b>Spread the copies.</b> Each chunk is written to <b>3 different storage nodes</b>, chosen by a hash of the chunk\'s id, so the pieces spread evenly. The manifest is stored the same way.',
            '<b>Then the record.</b> Only after the bytes are safe does the server save the file\'s record (name, owner, folder, size) in the database, in one short step. If that fails, the bytes are removed again.',
        ],
        'callout': ('The name you gave is only a label.',
                    'On the storage nodes the pieces are named after a random id, never <code>invoices-march.csv</code>. The name, the folder and the owner live in the database record. No single node holds the whole file, and every piece exists on three nodes.'),
    },
    'placement': {
        'who': ADMIN,
        'lead': 'Shows where each piece of a file is kept. This is the proof that the file really was spread across several storage nodes.',
        'reply': 'For every chunk: its size and the list of nodes that hold a copy.',
        'inside': [
            'The server does not look the answer up in a table. It <b>recomputes</b> it: it ranks all five nodes with the same hash that was used at upload time, then confirms which of them really hold the piece.',
            'Each chunk lists 3 nodes, and different chunks list different nodes. That is what "distributed" means here.',
        ],
    },
    'nodes': {
        'who': ADMIN,
        'lead': 'Shows the health of the whole storage cluster: every node, how many pieces it holds, and how many more nodes could fail without any file becoming unreadable.',
        'reply': 'The replication setting (3 copies), how many nodes can fail, and one line per node.',
        'inside': [
            'The server checks each node (every few seconds it also pings them, a "heartbeat") and adds up what each one holds.',
            '<code>nodesThatCanFail: 2</code> means any 2 nodes could stop working right now and every file would still be readable.',
        ],
    },
    'list-files': {
        'who': MEMBER,
        'lead': 'Lists the files inside one folder. This is what the file table in the app is built from.',
        'reply': 'A list of files with their id, name, size, type and dates.',
        'inside': [
            'The server asks the database for files where the owner is <em>you</em> and the folder is the one requested.',
            'Because "the owner is you" is part of the question, a folder id that belongs to someone else simply returns nothing. It can never show their files.',
        ],
    },
    'list-folders': {
        'who': MEMBER,
        'lead': 'Lists the folders inside a folder (leave it out for the top level), each with the number of items it holds.',
        'reply': 'A list of folders, each with an <code>itemCount</code>.',
        'inside': [
            'One question finds the child folders. Then, for each child, a few more small questions count what is inside it.',
        ],
    },
    'search': {
        'who': MEMBER,
        'lead': 'Looks for files whose name contains some text, anywhere in your drive.',
        'reply': 'The matching files, newest first.',
        'inside': [
            'With search text, the folder is ignored and the database is asked for your files whose name contains the text, ignoring capital letters.',
            'Only file names are searched, not folder names and not file contents.',
        ],
    },
    'path': {
        'who': MEMBER,
        'lead': 'Returns the chain of folders from the top down to one folder. The app draws its breadcrumb ("My files &gt; Invoices &gt; 2026") from it.',
        'reply': 'A list of folders, outermost first.',
        'inside': [
            'The server starts at the folder and keeps going to its parent until it reaches the top, then reverses the list.',
            'Every step is limited to your own folders.',
        ],
    },
    'storage': {
        'who': MEMBER,
        'lead': 'Says how much of your storage allowance you have used.',
        'reply': 'Bytes used and your limit.',
        'inside': [
            'The server adds up the sizes of your files and compares it with your limit (1 GiB by default).',
            'It deliberately says nothing about how or where the bytes are kept.',
        ],
    },
    'view': {
        'who': MEMBER,
        'lead': 'Shows a file inside the app (an image, a PDF, a table of text) instead of downloading it.',
        'reply': 'The file\'s contents, marked "inline" so the browser displays it.',
        'inside': [
            '<b>Find the pieces.</b> The server reads the manifest to learn how many chunks there are, then fetches each chunk from any storage node that holds it, and joins them in order.',
            '<b>Safe display.</b> The type to show is decided from the file extension, not from what the uploader claimed. A <code>.csv</code> is shown as plain text; web pages and scripts are also shown as plain text so they can never run inside the app.',
            '<b>Remember the visit.</b> The open is recorded in the open history. That history is what the storage optimizer learns from.',
        ],
    },
    'download': {
        'who': MEMBER,
        'lead': 'Sends the file so the browser saves it.',
        'reply': 'The exact bytes that were uploaded. Its fingerprint (SHA-256) matches the original.',
        'inside': [
            'Same as viewing, except the reply says "attachment" so the browser saves it instead of showing it.',
            'If the optimizer had archived the file (compressed it to save space), it is restored automatically before it is sent. You never notice.',
        ],
    },
    'node-down': {
        'who': ADMIN,
        'lead': 'Simulates a failure: two of the storage nodes that hold the first chunk are switched off at the same moment. (In the container setup you can also really stop them with <code>docker stop</code>.)',
        'reply': 'The cluster status, now with those two nodes out of service.',
        'inside': [
            'Both nodes are taken out of service together, like a power cut that hits two machines at once.',
            'The server immediately starts <b>repair</b>: every piece that lost a copy is copied to the nodes that are still working, so each piece is back to three copies where possible.',
        ],
        'callout': ('Why so many pieces were copied.',
                    'With only three working nodes left, every piece is stored on all three. The count above covers every file in the drive, not only this one.'),
    },
    'download-degraded': {
        'who': MEMBER,
        'lead': 'Downloads the same file again, while two storage nodes are down.',
        'reply': 'The same bytes. The fingerprint (SHA-256) is identical to the one before the failure.',
        'inside': [
            'For each chunk, the server asks the best-ranked node; if that node is down it simply asks the next one.',
            'Because every chunk had three copies on three different nodes, at least one copy of every chunk survived two failures.',
            '<b>Nothing visible changed for the user.</b> Same file, same bytes, no error.',
        ],
        'callout': ('This is the whole point of the design.',
                    'Two nodes failed at once and the file was still delivered perfectly. If three nodes holding the same chunk had failed together, that chunk would be unavailable until one of them came back.'),
    },
    'node-up': {
        'who': ADMIN,
        'lead': 'Brings the two nodes back into service.',
        'reply': 'The cluster status with all nodes working again.',
        'inside': [
            'The returning nodes are brought up to date with anything they missed.',
            'The temporary extra copies made during the outage are removed, so the pieces go back to their normal, evenly spread places.',
        ],
    },
    'err-duplicate': {
        'who': MEMBER,
        'lead': 'Tries to create a folder whose name already exists in the same place (capital letters do not matter).',
        'reply': '<b>409 Conflict</b> with a readable message.',
        'inside': [
            'The checks pass, then the server compares the name with the folders already in that place and finds a match.',
            '<b>Nothing was written.</b> The row counters did not change.',
        ],
    },
    'err-badname': {
        'who': MEMBER,
        'lead': 'Tries a folder name that contains a slash.',
        'reply': '<b>400 Bad Request</b>.',
        'inside': [
            'The input is rejected before any real work starts, so the database is never touched for the folder.',
        ],
    },
    'err-anon': {
        'who': ANYONE,
        'lead': 'Asks for the file list without being signed in.',
        'reply': '<b>401 Unauthorized</b>, with no body.',
        'inside': [
            'The server finds no session, so it refuses.',
            'A person who is signed in but still <em>pending</em> would get <b>403 Forbidden</b> instead. The two numbers tell apart "who are you?" from "you are not allowed yet".',
        ],
    },
    'admin-users': {
        'who': ADMIN,
        'lead': 'Lists the accounts so an administrator can approve or reject people.',
        'reply': 'Each account together with how much storage it uses.',
        'inside': [
            'The server first checks the caller is an administrator; anyone else gets 403.',
            'Add <code>?status=PENDING</code> to see only the people waiting for approval.',
        ],
    },
    'admin-stats': {
        'who': ADMIN,
        'lead': 'Shows storage numbers for the administrator: how much space is saved by archiving, how many files are cold, and how fast downloads start.',
        'reply': 'File counts, logical size versus size on disk, downloads and timings.',
        'inside': [
            'This is the only place where "hot" (fast) and "cold" (compressed) storage are visible. Ordinary users never see it.',
        ],
    },
    'insights': {
        'who': ADMIN,
        'lead': 'Asks for a short plain-language summary of the storage numbers, with a few suggestions.',
        'reply': 'A summary, a list of suggestions, and which source wrote it (AI or built-in).',
        'inside': [
            'Only totals are used. File names, file contents and user details are never sent anywhere.',
            'With a Gemini API key configured, the numbers are sent to Gemini 3.8 Flash and it writes the summary. Without a key (as in this capture) a built-in writer produces the summary from the same numbers, and the reply says so in <code>source</code>.',
            'Nothing is changed automatically; it only explains and suggests.',
        ],
    },
    'delete-folder': {
        'who': MEMBER,
        'lead': 'Deletes a folder and <b>everything inside it</b>: sub-folders and files.',
        'reply': '<b>204 No Content</b>: done, nothing to show.',
        'inside': [
            'The server finds the folder and every folder below it.',
            'For each file: its pieces are removed from all storage nodes, its open history is deleted, then its database record.',
            'Finally the folder records themselves are deleted.',
        ],
        'callout': ('One request, a whole tree. No undo.',
                    'Compare the counters and the storage grid: the folders, the file, and every copy of its pieces disappeared. There is no trash.'),
    },
}

GROUPS = [
    ('A', 'Sign in', 'Every call after this one carries your session cookie.', ['config', 'register', 'login', 'me']),
    ('B', 'Create folders', 'A folder is just a database record. Watch the storage grid: nothing is written there.', ['create-folder', 'create-subfolder']),
    ('C', 'Upload a file', 'The one place file contents are written. Here the file is cut up and spread over the storage nodes.', ['upload', 'placement', 'nodes']),
    ('D', 'Get the files', 'Reading is safe: nothing is changed.', ['list-files', 'list-folders', 'search', 'path', 'storage']),
    ('E', 'Open a file', 'Viewing and downloading both count as an "open".', ['view', 'download']),
    ('F', 'When storage nodes fail', 'Two nodes fail at once, and the file is still delivered, byte for byte.', ['node-down', 'download-degraded', 'node-up']),
    ('G', 'When things go wrong', 'Each mistake gets a clear answer and changes nothing.', ['err-duplicate', 'err-badname', 'err-anon']),
    ('H', 'Administrator calls', 'Only administrators may use these.', ['admin-users', 'admin-stats', 'insights']),
    ('I', 'Delete', 'Deleting a folder removes its whole tree, including every copy of every piece.', ['delete-folder']),
]

CATALOG = [
    ('Sign in', [
        ('GET', '/api/auth/config', 'public', 'Can new accounts be created?'),
        ('POST', '/api/auth/register', 'public', 'Create an account from a username and password (starts waiting for approval) and sign in.'),
        ('POST', '/api/auth/login', 'public', 'Sign in with a username and password.'),
        ('POST', '/api/auth/logout', 'signed in', 'Sign out.'),
        ('GET', '/api/me', 'signed in', 'Who am I, and am I approved yet?'),
    ]),
    ('Folders', [
        ('GET', '/api/folders?parentId=', 'approved', 'The folders inside a folder (top level if left out).'),
        ('GET', '/api/folders/all', 'approved', 'Every folder you own, as a flat list (for the "move to…" picker).'),
        ('GET', '/api/folders/{id}/path', 'approved', 'The chain of folders down to this one (breadcrumb).'),
        ('POST', '/api/folders', 'approved', 'Create a folder: name, and optionally the parent.'),
        ('PATCH', '/api/folders/{id}', 'approved', 'Rename a folder.'),
        ('POST', '/api/folders/{id}/move', 'approved', 'Move a folder (never into itself).'),
        ('DELETE', '/api/folders/{id}', 'approved', 'Delete a folder and everything in it.'),
    ]),
    ('Files', [
        ('GET', '/api/files?folderId=&search=', 'approved', 'The files in a folder, or a name search over the whole drive.'),
        ('POST', '/api/files', 'approved', 'Upload a file (and optionally the folder to put it in).'),
        ('GET', '/api/files/{id}/view', 'approved', 'Show a file inside the app.'),
        ('GET', '/api/files/{id}/download', 'approved', 'Download a file.'),
        ('PATCH', '/api/files/{id}', 'approved', 'Rename a file.'),
        ('POST', '/api/files/{id}/move', 'approved', 'Move a file to another folder.'),
        ('DELETE', '/api/files/{id}', 'approved', 'Delete a file and every copy of it.'),
        ('GET', '/api/storage', 'approved', 'How much of your allowance you have used.'),
    ]),
    ('Administrator: people', [
        ('GET', '/api/admin/users?status=', 'admin', 'The accounts, and how much storage each uses.'),
        ('POST', '/api/admin/users/{id}/approve', 'admin', 'Approve an account.'),
        ('POST', '/api/admin/users/{id}/reject', 'admin', 'Reject, or revoke, an account.'),
    ]),
    ('Administrator: storage', [
        ('GET', '/api/admin/stats', 'admin', 'Space saved, cold files, download speed.'),
        ('GET', '/api/admin/optimizer', 'admin', 'The archiving threshold and the last run.'),
        ('POST', '/api/admin/optimizer/run', 'admin', 'Run the archiving optimizer now.'),
        ('POST', '/api/admin/insights', 'admin', 'A plain-language storage summary with suggestions.'),
        ('GET', '/api/admin/storage/nodes', 'admin', 'Health of all storage nodes.'),
        ('GET', '/api/admin/storage/files/{id}/placement', 'admin', 'Which nodes hold each piece of a file.'),
        ('POST', '/api/admin/storage/nodes/{name}/offline', 'admin', 'Take one node out of service (maintenance).'),
        ('POST', '/api/admin/storage/nodes/{name}/online', 'admin', 'Put one node back into service.'),
        ('POST', '/api/admin/storage/nodes/offline?names=a,b', 'admin', 'Take several nodes out at the same moment.'),
        ('POST', '/api/admin/storage/nodes/online?names=a,b', 'admin', 'Put several nodes back.'),
        ('POST', '/api/admin/storage/nodes', 'admin', 'Add a storage node (directory mode).'),
        ('POST', '/api/admin/storage/repair', 'admin', 'Re-create any missing copies now.'),
    ]),
]

STATUSES = [
    ('200 OK', 'It worked, and the reply has the result.'),
    ('201 Created', 'A folder or file was created; the reply describes it.'),
    ('204 No Content', 'It worked and there is nothing to show (deletes).'),
    ('400 Bad Request', 'The input is not valid (blank name, a slash in a name, a folder moved into itself).'),
    ('401 Unauthorized', 'You are not signed in.'),
    ('403 Forbidden', 'Signed in but not allowed: account waiting or rejected, missing CSRF token, or not an administrator.'),
    ('404 Not Found', 'It does not exist, <em>or it belongs to someone else</em> (deliberately the same answer).'),
    ('409 Conflict', 'A folder with that name already exists in that place.'),
    ('413 Payload Too Large', 'The upload would go over your storage allowance.'),
    ('415 Unsupported Media Type', 'That file type has no safe preview; download it instead.'),
    ('503 Service Unavailable', 'The pieces of that file are on nodes that are all down right now; try again when one returns.'),
]

GLOSSARY = [
    ('API', 'The set of messages the app is allowed to send to the server. Every button in the app sends one.'),
    ('Request / response', 'A request is what you send ("create this folder"). The response is the server\'s answer.'),
    ('Endpoint', 'One address you can send a request to, such as <code>/api/folders</code>.'),
    ('GET · POST · PATCH · DELETE', '"Show me" · "create or do this" · "change this" · "remove this".'),
    ('JSON', 'The plain-text format used for data, like <code>{"name": "Invoices"}</code>.'),
    ('Status code', 'A number in every answer: 2xx means it worked, 4xx means you did something the server cannot accept.'),
    ('Session cookie', 'Your "wristband" after signing in, so the server knows who you are on later calls.'),
    ('CSRF token', 'A secret that stops other websites from making your browser change things behind your back.'),
    ('Database', 'Where names, folders, owners and sizes are kept (PostgreSQL). It does not hold file contents.'),
    ('Storage node', 'One place that holds file contents. This system has five (separate Docker containers).'),
    ('Chunk', 'A piece of a file, 256 KiB. Big files are cut into several chunks.'),
    ('Manifest', 'A tiny record per file saying how many chunks it has.'),
    ('Replica / copy', 'Every chunk is stored on 3 different nodes, so there are 3 copies.'),
    ('Heartbeat', 'The server pinging each node every few seconds to see if it is alive.'),
    ('Repair', 'Re-creating copies that were lost when a node failed.'),
    ('Hot / cold', 'Hot files are stored plainly for speed; cold files are compressed to save space. Users never see the difference.'),
]


# ------------------------------------------------------------------ page assembly

def step_card(number, step):
    ex = EXPLAIN[step['id']]
    r, resp = step['request'], step['response']
    callout = f'<aside class="callout"><b>{ex["callout"][0]}</b><p>{ex["callout"][1]}</p></aside>' if 'callout' in ex else ''
    curl = r.get('curl') or ''
    plain = PLAIN_STATUS.get(resp['status'], resp['status'])

    check = ''
    if step['id'] in ('download', 'download-degraded'):
        same = resp.get('sha256') == step.get('original_sha256')
        down = ''
        if step['id'] == 'download-degraded':
            down = f' while <b>{" and ".join(step["holders_down"])}</b> were down'
        check = (f'<div class="fingerprint {"yes" if same else "no"}">Fingerprint (SHA-256) of the downloaded file{down}: '
                 f'<code>{resp["sha256"][:24]}…</code> '
                 f'{"= identical to the uploaded file ✔" if same else "does NOT match the upload ✘"}</div>')

    sendbody = ''
    if r['body'] is not None:
        if 'multipart' in r['body']:
            parts = r['body']['multipart']
            sendbody = code('\n'.join(f'<span class="j-key">{esc(k)}</span> = {esc(str(v))}' for k, v in parts.items()), 'You send (form upload)')
        else:
            sendbody = code(highlight_json(r['body']), 'You send (JSON)')
    else:
        sendbody = '<p class="muted small">Nothing is sent in the body; the address says everything.</p>'

    if resp['body'] in ('', None):
        getbody = '<p class="muted small">The reply has no body.</p>'
    elif resp['body'] == []:
        getbody = code('[]  <span class="muted">(an empty list: nothing to show)</span>', 'You get back')
    elif resp['is_json']:
        getbody = code(highlight_json(resp['body']), 'You get back (JSON)')
    else:
        getbody = code(esc(str(resp['body'])).replace('\\n', '\n'), 'You get back (the file\'s contents)')

    return f'''
<article class="step" id="{esc(step["id"])}">
  <header class="step-head">
    <span class="step-no">{number}</span>
    <div>
      <h3>{esc(step["title"])}</h3>
      <p class="lead">{ex["lead"]}</p>
    </div>
  </header>
  <div class="meta">
    <span class="endpoint"><span class="verb v-{r["method"].lower()}">{esc(r["method"])}</span> <code>{esc(r["path"].split("?")[0])}</code></span>
    <span class="who-chip">Who can do this: {ex["who"]}</span>
  </div>

  <div class="exchange">
    <div class="col">
      <h4><span class="dot req"></span>1 · What you send</h4>
      {sendbody}
    </div>
    <div class="col">
      <h4><span class="dot res"></span>2 · What you get back <span class="ms">{resp["ms"]} ms</span></h4>
      <p class="reply"><span class="status-chip {status_class(resp["status"])}">{resp["status"]} · {esc(str(plain))}</span> {ex["reply"]}</p>
      {getbody}
      {check}
    </div>
  </div>

  <div class="inside-wrap">
    <h4><span class="dot in"></span>3 · What happens behind the scenes</h4>
    {inside_list(ex["inside"])}
  </div>

  <div class="effects">
    <h4><span class="dot db"></span>4 · What changed</h4>
    <div class="counters">{counters(step)}</div>
    {rows_table(step)}
    {storage_block(step)}
    {callout}
  </div>

  <details class="tech">
    <summary>For developers: raw HTTP, curl and the SQL the server ran</summary>
    <div class="tech-grid">
      <div>{code(request_http(step), "Raw request")}</div>
      <div>{code(response_http(step), "Raw response")}</div>
    </div>
    <div style="margin-top:12px">{code(esc(curl), "curl", copy_text=curl)}</div>
    <h5>{len(step["sql"])} SQL statement{"s" if len(step["sql"]) != 1 else ""} <span class="muted">(logged by Hibernate)</span></h5>
    {sql_block(step)}
  </details>
</article>'''


ORDER = [sid for _, _, _, ids in GROUPS for sid in ids]


def number_of(step_id):
    return ORDER.index(step_id) + 1


def placement_picture():
    """The big visual for the distributed part, from the real upload."""
    p = STEPS['placement']['response']['body']
    chunks = p['chunks']
    head = ''.join(f'<th>{n.replace("node-", "node ")}</th>' for n in NODES)
    rows = ''
    for c in chunks:
        have = {n['name'] for n in c['nodes']}
        tds = ''.join(f'<td class="{"has" if n in have else ""}">{"●" if n in have else ""}</td>' for n in NODES)
        rows += f'<tr><th>piece {c["index"]} <span class="muted">({c["sizeBytes"]:,} bytes)</span></th>{tds}</tr>'
    down = STEPS['download-degraded']['holders_down']
    same = STEPS['download-degraded']['response']['sha256'] == STEPS['download-degraded']['original_sha256']
    return f'''
<div class="card">
  <p>This table is real: it is the upload from <a href="#upload">step {number_of('upload')}</a> below. The file <b>invoices-march.csv</b>
  ({DATA["file_bytes"]:,} bytes) was cut into <b>{len(chunks)} pieces</b>. A dot means that node holds a copy of that piece.</p>
  <div class="table-wrap" style="margin-top:12px"><table class="grid big"><thead><tr><th></th>{head}</tr></thead><tbody>{rows}</tbody></table></div>
  <div class="split" style="margin-top:16px">
    <div class="where"><h4>What you can see in the table</h4><ul>
      <li>Every piece has <b>three</b> dots: three copies on three different nodes.</li>
      <li>No node holds the whole file.</li>
      <li>Different pieces use different nodes, so the load is spread out.</li></ul></div>
    <div class="where"><h4>What happens when nodes fail</h4><ul>
      <li>Stop <b>{down[0]}</b> and <b>{down[1]}</b> together: every piece still has at least one copy left.</li>
      <li>The download in <a href="#download-degraded">step {number_of('download-degraded')}</a> {"returned the same bytes (identical fingerprint)" if same else "was compared with the original"}.</li>
      <li>The server then re-creates the missing copies by itself ("repair").</li></ul></div>
  </div>
</div>'''


def build():
    nav, sections, n = [], [], 0
    for letter, title, blurb, ids in GROUPS:
        nav.append(f'<a href="#sec-{letter}"><b>{letter}</b> {esc(title)}</a>')
        cards = []
        for sid in ids:
            n += 1
            cards.append(step_card(n, STEPS[sid]))
        sections.append(f'<section id="sec-{letter}"><div class="sec-head"><span class="sec-letter">{letter}</span>'
                        f'<div><h2>{esc(title)}</h2><p class="muted">{esc(blurb)}</p></div></div>{"".join(cards)}</section>')

    catalog = []
    for group, rows in CATALOG:
        trs = ''.join(
            f'<tr><td><span class="verb v-{m.lower()}">{m}</span></td><td><code>{esc(p)}</code></td>'
            f'<td><span class="who who-{w.split()[0]}">{esc(w)}</span></td><td>{d}</td></tr>'
            for m, p, w, d in rows)
        catalog.append(f'<h3 class="cat">{esc(group)}</h3><div class="table-wrap"><table class="catalog"><thead>'
                       f'<tr><th>Action</th><th>Address</th><th>Who</th><th>What it does</th></tr></thead><tbody>{trs}</tbody></table></div>')

    statuses = ''.join(f'<tr><td><code>{esc(s)}</code></td><td>{d}</td></tr>' for s, d in STATUSES)
    glossary = ''.join(f'<tr><th>{t}</th><td>{d}</td></tr>' for t, d in GLOSSARY)

    page = PAGE
    for key, value in (('%%GENERATED%%', esc(DATA['generated'])), ('%%NAV%%', ''.join(nav)), ('%%CATALOG%%', ''.join(catalog)),
                       ('%%SECTIONS%%', ''.join(sections)), ('%%STATUSES%%', statuses), ('%%GLOSSARY%%', glossary),
                       ('%%PICTURE%%', placement_picture()), ('%%SETUP%%', code(esc(SETUP), 'Setup: run once', copy_text=SETUP))):
        page = page.replace(key, value)
    return page


PAGE = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Drive API, explained simply</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
<style>
:root {
  --bg: #f6f8fc; --surface: #ffffff; --surface-2: #f1f3f4; --border: #e0e4e9;
  --text: #1f1f1f; --muted: #5f6368; --accent: #0b57d0; --accent-soft: #d3e3fd;
  --ok: #0d652d; --ok-bg: #e6f4ea; --warn: #8a5300; --warn-bg: #fef7e0; --bad: #a50e0e; --bad-bg: #fce8e6;
  --code-bg: #1e2227; --code-text: #e6e8eb; --code-muted: #8b949e;
  --k: #8ab4f8; --s: #81c995; --n: #fdd663; --l: #f28b82;
  --font: 'Poppins', system-ui, -apple-system, 'Segoe UI', sans-serif;
  --mono: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #131416; --surface: #1c1e21; --surface-2: #26292d; --border: #33373c;
    --text: #e8eaed; --muted: #9aa0a6; --accent: #8ab4f8; --accent-soft: #263a5c;
    --ok: #81c995; --ok-bg: #1b3326; --warn: #fdd663; --warn-bg: #3a3118; --bad: #f28b82; --bad-bg: #3c1f1d;
    --code-bg: #0f1113;
  }
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: 16px; }
body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.7 var(--font); -webkit-font-smoothing: antialiased; }
h1, h2, h3, h4, h5, p { margin: 0; }
code { font-family: var(--mono); font-size: .88em; background: var(--surface-2); padding: .1em .4em; border-radius: 6px; overflow-wrap: anywhere; }
.muted { color: var(--muted); } .small { font-size: 13px; }

.layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); max-width: 1320px; margin: 0 auto; gap: 40px; padding: 0 28px; }
nav.toc { position: sticky; top: 0; align-self: start; padding: 32px 0; max-height: 100vh; overflow: auto; }
nav.toc .brand { font-weight: 600; color: var(--accent); margin-bottom: 20px; font-size: 17px; }
nav.toc a { display: block; padding: 7px 12px; margin: 1px 0; border-radius: 10px; color: var(--muted); text-decoration: none; font-size: 14px; }
nav.toc a:hover { background: var(--surface-2); color: var(--text); }
nav.toc a b { display: inline-block; width: 22px; color: var(--accent); }
nav.toc .group { margin: 18px 12px 6px; font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }

main { padding: 40px 0 96px; min-width: 0; }
.hero h1 { font-size: 36px; font-weight: 600; letter-spacing: -.01em; }
.hero p.sub { margin-top: 12px; font-size: 18px; color: var(--muted); max-width: 66ch; }
.proof { margin-top: 20px; display: inline-flex; gap: 10px; align-items: center; padding: 10px 16px; border-radius: 999px; background: var(--ok-bg); color: var(--ok); font-size: 14px; }
.legend { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 22px; font-size: 14px; color: var(--muted); }
.dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; }
.dot.req { background: #1a73e8; } .dot.res { background: #188038; } .dot.in { background: #9aa0a6; } .dot.db { background: #f9ab00; }

section { margin-top: 64px; }
section > h2.plain { font-size: 25px; font-weight: 600; margin-bottom: 8px; }
.sec-head { display: flex; gap: 16px; align-items: center; margin-bottom: 20px; }
.sec-head h2 { font-size: 25px; font-weight: 600; }
.sec-letter { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 14px; background: var(--accent); color: #fff; font-weight: 600; font-size: 18px; flex: none; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 20px; padding: 24px 26px; }
.three { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }
.three .card h4 { font-size: 16px; margin-bottom: 8px; }
.three .card p, .three .card li { font-size: 15px; color: var(--muted); }
.three ul { margin: 6px 0 0; padding-left: 18px; }
.analogy { margin-top: 16px; padding: 18px 22px; border-radius: 16px; background: var(--accent-soft); border-left: 4px solid var(--accent); }

.pipeline { display: flex; flex-wrap: wrap; align-items: stretch; gap: 10px; margin-top: 16px; }
.stage { flex: 1 1 150px; background: var(--surface-2); border-radius: 14px; padding: 14px 16px; }
.stage b { display: block; font-size: 14px; }
.stage span { font-size: 13px; color: var(--muted); line-height: 1.5; display: block; margin-top: 4px; }
.arrow { display: grid; place-items: center; color: var(--muted); font-size: 20px; flex: 0 0 18px; }
.stack { display: flex; flex-direction: column; gap: 10px; flex: 1 1 170px; }
.stack .stage { flex: 1 1 auto; }
.stage.db { border-left: 4px solid #f9ab00; } .stage.disk { border-left: 4px solid #188038; }
.split { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }
.where { border-radius: 14px; padding: 16px 18px; background: var(--surface-2); }
.where h4 { font-size: 14px; margin-bottom: 6px; } .where ul { margin: 0; padding-left: 18px; color: var(--muted); font-size: 14px; }

h3.cat { margin: 28px 0 10px; font-size: 16px; font-weight: 600; }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 16px; background: var(--surface); }
table.catalog, table.plain, table.gloss { width: 100%; border-collapse: collapse; font-size: 14px; }
table.catalog th, table.plain th { text-align: left; padding: 10px 14px; font-size: 12px; font-weight: 500; color: var(--muted); border-bottom: 1px solid var(--border); white-space: nowrap; }
table.catalog td, table.plain td, table.gloss td, table.gloss th { padding: 10px 14px; border-bottom: 1px solid var(--border); vertical-align: top; text-align: left; }
table.gloss th { width: 24%; font-weight: 600; }
table.catalog tr:last-child td, table.plain tr:last-child td, table.gloss tr:last-child td, table.gloss tr:last-child th { border-bottom: none; }
.verb { font-family: var(--mono); font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 6px; background: var(--surface-2); }
.v-get { color: #1a73e8; } .v-post { color: #188038; } .v-patch { color: #b06000; } .v-delete { color: #c5221f; }
.who { font-size: 12px; padding: 2px 10px; border-radius: 999px; white-space: nowrap; background: var(--surface-2); color: var(--muted); }
.who-public { background: var(--ok-bg); color: var(--ok); } .who-admin { background: var(--bad-bg); color: var(--bad); }
.who-approved { background: var(--accent-soft); color: var(--accent); }

table.grid { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 8px; }
table.grid th, table.grid td { padding: 6px 10px; border: 1px solid var(--border); text-align: center; }
table.grid thead th { background: var(--surface-2); font-weight: 500; }
table.grid tbody th { text-align: left; font-weight: 500; white-space: nowrap; }
table.grid td.has { color: var(--accent); font-size: 16px; background: var(--accent-soft); }
table.grid.big { font-size: 15px; margin-top: 0; } table.grid.big td.has { font-size: 20px; }

.step { background: var(--surface); border: 1px solid var(--border); border-radius: 22px; padding: 26px; margin-top: 22px; }
.step-head { display: grid; grid-template-columns: auto 1fr; gap: 16px; align-items: start; }
.step-no { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); font-weight: 600; font-size: 14px; }
.step-head h3 { font-size: 20px; font-weight: 600; }
.lead { color: var(--muted); margin-top: 4px; max-width: 75ch; }
.meta { display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; margin-top: 14px; font-size: 13px; }
.who-chip { padding: 3px 12px; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
.exchange { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 18px; margin-top: 20px; }
h4 { font-size: 14px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; }
h5 { font-size: 14px; font-weight: 500; margin: 14px 0 6px; }
.ms { margin-left: auto; font-weight: 400; color: var(--muted); font-size: 12px; }
.reply { font-size: 14px; margin-bottom: 8px; }
.status-chip { display: inline-block; font-weight: 600; font-size: 12px; padding: 2px 10px; border-radius: 999px; margin-right: 6px; }
.status-chip.ok { background: var(--ok-bg); color: var(--ok); } .status-chip.warn { background: var(--warn-bg); color: var(--warn); } .status-chip.bad { background: var(--bad-bg); color: var(--bad); }
.fingerprint { margin-top: 10px; padding: 8px 12px; border-radius: 12px; font-size: 13px; }
.fingerprint.yes { background: var(--ok-bg); color: var(--ok); } .fingerprint.no { background: var(--bad-bg); color: var(--bad); }

.code { background: var(--code-bg); color: var(--code-text); border-radius: 14px; overflow: hidden; }
.code-bar { display: flex; justify-content: space-between; align-items: center; padding: 6px 10px 0 14px; min-height: 30px; }
.code-label { font-size: 11px; letter-spacing: .06em; text-transform: uppercase; color: var(--code-muted); }
.copy { background: transparent; border: 1px solid #3a3f45; color: var(--code-muted); border-radius: 8px; font: 12px var(--font); padding: 3px 10px; cursor: pointer; }
.copy:hover { color: #fff; border-color: #6a7077; }
pre { margin: 0; padding: 8px 14px 14px; font: 12.5px/1.65 var(--mono); overflow: auto; max-height: 360px; white-space: pre; }
.j-key { color: var(--k); } .j-str { color: var(--s); } .j-num { color: var(--n); } .j-lit { color: var(--l); }
.code .verb { color: #8ab4f8; background: transparent; padding: 0; font-weight: 600; }
.hdr { color: #9aa0a6; }
.status { font-weight: 600; padding: 1px 8px; border-radius: 6px; }
.status.ok { background: #1b3326; color: #81c995; } .status.warn { background: #3a3118; color: #fdd663; } .status.bad { background: #3c1f1d; color: #f28b82; }
.code .muted { color: var(--code-muted); }

.inside-wrap { margin-top: 24px; }
ol.inside { margin: 0; padding: 0; counter-reset: s; list-style: none; }
ol.inside li { position: relative; padding: 0 0 12px 38px; }
ol.inside li::before { counter-increment: s; content: counter(s); position: absolute; left: 0; top: 2px; width: 24px; height: 24px; border-radius: 50%; background: var(--surface-2); color: var(--muted); font-size: 12px; font-weight: 600; display: grid; place-items: center; }
ol.inside li:not(:last-child)::after { content: ''; position: absolute; left: 11.5px; top: 30px; bottom: 4px; width: 1px; background: var(--border); }

.effects { margin-top: 12px; padding: 18px 20px; border-radius: 16px; background: var(--surface-2); }
.counters { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.counter { font-size: 13px; padding: 4px 12px; border-radius: 999px; background: var(--surface); color: var(--muted); }
.counter.changed { background: var(--warn-bg); color: var(--warn); }
.rowcard { background: var(--surface); border-radius: 14px; border: 1px solid var(--border); margin: 10px 0; overflow: hidden; }
.rowcard-title { padding: 8px 14px; font-size: 12px; color: var(--muted); border-bottom: 1px solid var(--border); }
table.kv { width: 100%; border-collapse: collapse; font-family: var(--mono); font-size: 12.5px; }
table.kv th { text-align: left; width: 34%; font-weight: 500; color: var(--muted); padding: 5px 14px; vertical-align: top; }
table.kv td { padding: 5px 14px; word-break: break-all; }
table.kv tr + tr { border-top: 1px dashed var(--border); }
.null { color: var(--code-muted); font-style: italic; }
.store { margin: 10px 0; padding: 12px 14px; border-radius: 12px; font-size: 14px; }
.store.none { background: var(--surface); color: var(--muted); }
.store.added { background: var(--ok-bg); color: var(--ok); } .store.removed { background: var(--bad-bg); color: var(--bad); }
.store table.grid { background: var(--surface); color: var(--text); }
.callout { margin: 12px 0; padding: 14px 18px; border-radius: 14px; background: var(--accent-soft); border-left: 4px solid var(--accent); }
.callout p { margin-top: 4px; font-size: 14px; }

details.tech { margin-top: 14px; border-top: 1px dashed var(--border); padding-top: 12px; }
details.tech summary { cursor: pointer; font-size: 14px; font-weight: 500; color: var(--muted); }
.tech-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 12px; }
ol.sql { margin: 8px 0 0; padding: 0; list-style: none; display: grid; gap: 6px; }
ol.sql li { display: flex; gap: 10px; align-items: flex-start; background: var(--code-bg); border-radius: 10px; padding: 8px 12px; }
ol.sql code { background: transparent; color: var(--code-text); font-size: 12px; padding: 0; word-break: break-word; }
.sql-kind { font-family: var(--mono); font-size: 10.5px; font-weight: 700; text-transform: uppercase; padding: 2px 7px; border-radius: 5px; flex: none; margin-top: 1px; }
.sql-select { background: #263a5c; color: #8ab4f8; } .sql-insert { background: #1b3326; color: #81c995; }
.sql-update { background: #3a3118; color: #fdd663; } .sql-delete { background: #3c1f1d; color: #f28b82; }

.note-list { margin: 12px 0 0; padding-left: 20px; } .note-list li { margin: 8px 0; }
.setup-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; margin-top: 14px; }
footer { margin-top: 64px; color: var(--muted); font-size: 13px; }

@media (max-width: 1000px) {
  .layout { grid-template-columns: minmax(0, 1fr); gap: 0; padding: 0 16px; }
  nav.toc { position: static; padding: 20px 0 0; max-height: none; display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
  nav.toc .brand, nav.toc .group { width: 100%; margin: 6px 0; }
  nav.toc a { display: inline-block; background: var(--surface); border: 1px solid var(--border); }
  .exchange, .split, .setup-grid, .three, .tech-grid { grid-template-columns: minmax(0, 1fr); }
  .step { padding: 18px; }
}
</style>
</head>
<body>
<div class="layout">
<nav class="toc">
  <div class="brand">Drive API</div>
  <div class="group">Start here</div>
  <a href="#basics">Read this first</a>
  <a href="#words">Words used here</a>
  <a href="#picture">The big picture</a>
  <a href="#distributed">How a file is stored</a>
  <a href="#setup">Try it yourself</a>
  <div class="group">Walkthrough</div>
  %%NAV%%
  <div class="group">Reference</div>
  <a href="#catalog">All the actions</a>
  <a href="#status">Status codes</a>
  <a href="#notes">Good to know</a>
</nav>

<main>
  <header class="hero">
    <h1>The Drive API, explained simply</h1>
    <p class="sub">Every button in the app sends a small message to the server. This page shows each message, the answer it gets back, and what really happens behind the scenes, using real examples.</p>
    <div class="proof">&#10003; Recorded from the running system on %%GENERATED%%. Nothing on this page is made up.</div>
    <div class="legend">
      <span><span class="dot req"></span>1 · What you send</span>
      <span><span class="dot res"></span>2 · What you get back</span>
      <span><span class="dot in"></span>3 · Behind the scenes</span>
      <span><span class="dot db"></span>4 · What changed</span>
    </div>
  </header>

  <section id="basics">
    <h2 class="plain">Read this first: what is an API?</h2>
    <div class="analogy">
      <b>Think of a restaurant.</b> You (the app) look at the menu and tell the waiter (the API) what you want. The waiter takes your order to the kitchen (the server) and brings back a reply. You never go into the kitchen. The API is the list of orders the waiter understands, and the replies you can expect.
    </div>
    <div class="three">
      <div class="card"><h4>What you send</h4><p>An <b>action</b> and an <b>address</b>, and sometimes some details:</p>
        <ul><li><b>GET</b>: show me something</li><li><b>POST</b>: create something, or do something</li><li><b>PATCH</b>: change something</li><li><b>DELETE</b>: remove something</li></ul></div>
      <div class="card"><h4>What you get back</h4><p>A <b>status number</b> and usually some data:</p>
        <ul><li><b>2xx</b>: it worked</li><li><b>4xx</b>: you asked for something the server cannot accept</li><li><b>5xx</b>: the server could not do it right now</li></ul></div>
      <div class="card"><h4>Who is asking</h4><p>The server needs to know who you are:</p>
        <ul><li>You sign in once and get a <b>session cookie</b></li><li>Changes also need a <b>CSRF token</b></li><li>Some actions are for <b>administrators</b> only</li></ul></div>
    </div>
  </section>

  <section id="words">
    <h2 class="plain">Words used on this page</h2>
    <div class="table-wrap" style="margin-top:14px"><table class="gloss"><tbody>%%GLOSSARY%%</tbody></table></div>
  </section>

  <section id="picture">
    <h2 class="plain">The big picture</h2>
    <p class="muted">One server application. Every request walks the same path. Only the last stop differs.</p>
    <div class="card" style="margin-top:16px">
      <div class="pipeline">
        <div class="stage"><b>The app or curl</b><span>Sends your request with your session cookie.</span></div><div class="arrow">&rarr;</div>
        <div class="stage"><b>Security checks</b><span>Who are you? Are you approved? Is this an admin-only action?</span></div><div class="arrow">&rarr;</div>
        <div class="stage"><b>The rules</b><span>Is the name valid? Is it your folder? Is there room in your allowance?</span></div><div class="arrow">&rarr;</div>
        <div class="stack">
          <div class="stage db"><b>Database</b><span>Names, folders, owners, sizes, history. No file contents.</span></div>
          <div class="stage disk"><b>5 storage nodes</b><span>The file contents, in chunks, 3 copies each.</span></div>
        </div>
      </div>
      <div class="split">
        <div class="where"><h4>The database knows</h4><ul><li>who you are and whether you are approved</li><li>every folder, and which folder is inside which</li><li>every file's name, owner, folder and size</li></ul></div>
        <div class="where"><h4>The storage nodes know</h4><ul><li>nothing but pieces of bytes with random names</li><li>no folders, no file names, no owners</li><li>which is why folder actions never touch them</li></ul></div>
      </div>
    </div>
  </section>

  <section id="distributed">
    <h2 class="plain">How a file is stored: the distributed part</h2>
    <p class="muted">A file is not saved in one place. It is cut into pieces, and every piece is kept on three different storage nodes.</p>
    <div style="margin-top:16px">%%PICTURE%%</div>
    <div class="three">
      <div class="card"><h4>1 · Cut</h4><p>The file is split into 256 KiB pieces called <b>chunks</b>, plus a small manifest saying how many.</p></div>
      <div class="card"><h4>2 · Spread</h4><p>Each chunk goes to <b>3 of the 5 nodes</b>, picked by a hash of the chunk's id. No lookup table is needed: the same hash finds it again later.</p></div>
      <div class="card"><h4>3 · Heal</h4><p>A heartbeat notices a dead node within seconds, and <b>repair</b> re-creates the lost copies on the nodes that still work.</p></div>
    </div>
  </section>

  <section id="setup">
    <h2 class="plain">Try it yourself</h2>
    <p class="muted">Two things identify every call: a session cookie, and, on anything that changes data, a CSRF header.</p>
    <div class="setup-grid">
      <div class="card"><b>Session cookie</b>
        <p class="muted small">You receive <code>JSESSIONID</code> when you sign in. Send it back on every call; <code>curl -b cookies.txt</code> does this for you.</p></div>
      <div class="card"><b>CSRF header</b>
        <p class="muted small">The server sets an <code>XSRF-TOKEN</code> cookie. For POST, PATCH and DELETE, send its value as the <code>X-XSRF-TOKEN</code> header. GET requests do not need it.</p></div>
    </div>
    <div style="margin-top:14px">%%SETUP%%</div>
  </section>

  %%SECTIONS%%

  <section id="catalog">
    <h2 class="plain">All the actions the API offers</h2>
    <p class="muted">"Who" says what is needed: <b>public</b> needs nothing, <b>approved</b> needs a signed-in account an administrator has approved, <b>admin</b> needs an administrator.</p>
    %%CATALOG%%
  </section>

  <section id="status">
    <h2 class="plain">Status codes you will see</h2>
    <div class="table-wrap" style="margin-top:14px"><table class="plain"><thead><tr><th>Status</th><th>What it means here</th></tr></thead><tbody>%%STATUSES%%</tbody></table></div>
    <p class="muted small" style="margin-top:12px">Errors carry a small JSON body with a human-readable <code>detail</code>.</p>
  </section>

  <section id="notes">
    <h2 class="plain">Good to know</h2>
    <div class="card" style="margin-top:14px">
      <ul class="note-list">
        <li><b>Folders are virtual.</b> Creating, renaming and moving a folder only changes database records; no file data moves.</li>
        <li><b>Someone else's items answer 404, not 403.</b> It avoids confirming that an id exists.</li>
        <li><b>Storage tiers are invisible to users on purpose.</b> A rarely used file may be compressed ("cold"); opening it restores it automatically. Only the admin endpoints show tiers.</li>
        <li><b>Two storage nodes may fail at the same moment</b> without any file becoming unreadable. Three failing together can make the pieces they alone held unreadable until one returns (HTTP 503).</li>
        <li><b>In this setup all five nodes run on one computer</b> (as Docker containers), so a failure of the whole computer is not covered.</li>
        <li><b>Passwords are stored as BCrypt hashes only.</b> Use HTTPS in front of the server so passwords are not sent in clear text, and set the administrator usernames in the server settings.</li>
      </ul>
    </div>
  </section>

  <footer>Regenerate this page: start the backend with <code>--logging.level.org.hibernate.SQL=DEBUG</code>, run <code>docs/capture_api.py</code>, then <code>docs/build_api_docs.py</code>.</footer>
</main>
</div>

<script>
document.addEventListener('click', function (e) {
  var copy = e.target.closest('.copy');
  if (copy) {
    navigator.clipboard.writeText(copy.dataset.copy).then(function () {
      copy.textContent = 'Copied'; setTimeout(function () { copy.textContent = 'Copy'; }, 1400);
    });
  }
});
</script>
</body>
</html>
'''

SETUP = '''# 1. Pick a base URL and let curl keep the cookies in a file
BASE=http://localhost:8080

# 2. Any GET gives you the XSRF-TOKEN cookie
curl -s -c cookies.txt "$BASE/api/auth/config"

# 3. Read the token out of the cookie file (used for every POST / PATCH / DELETE)
TOKEN=$(grep XSRF-TOKEN cookies.txt | awk '{print $7}')

# 4. Sign in (or create the account first with POST /api/auth/register)
curl -s -b cookies.txt -c cookies.txt -X POST "$BASE/api/auth/login" \\
  -H "X-XSRF-TOKEN: $TOKEN" -H "Content-Type: application/json" \\
  -d '{"username":"admin","password":"<your password>"}'

# 5. The session cookie changed; refresh the token before the next write
TOKEN=$(grep XSRF-TOKEN cookies.txt | awk '{print $7}')'''

if __name__ == '__main__':
    page = build()
    open(OUT, 'w', encoding='utf-8').write(page)
    print('wrote', OUT, f'({len(page) // 1024} KB)')
