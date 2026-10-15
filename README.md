# Cloud Storage with AI-based tiering

A small Google-Drive-style file store. Behind the scenes a Spring Boot backend keeps files in **hot**
(plain) or **cold** (gzip-compressed) storage, and a model predicts which files will be needed soon so
that idle files are archived and frequently used ones stay fast.

**Tiering is internal.** Users see an ordinary drive: no tiers, badges or archive states appear in the
UI or in the file API. Opening an archived file simply works (it is restored transparently). The only
place the mechanism is visible is **Admin panel → Storage operations**, for administrators.

Core features: username and password sign-in with "Create account", administrator approval of new accounts, private per-account
drives with nested folders, and drag-and-drop. No sharing between accounts yet.

## Run it

Requires Java 21+, Maven, Node 20+ and Docker (for the PostgreSQL database).

```bash
# terminal 0 – PostgreSQL on localhost:5432 (once; it keeps running and keeps its data)
docker compose up -d

# terminal 1 – API on http://localhost:8080 (the dev profile makes the username "admin" the administrator and adds sample data)
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# terminal 2 – UI on http://localhost:5173
cd frontend && npm install && npm run dev
```

With the `dev` profile, the first start fills the drive of the **`admin`** account with 12 sample files in a
small folder tree (Reports › Archive, Notes) that have a month-plus of access history. That account starts
without a password: on the login page choose **Create account**, enter the username `admin` and pick your own
password, and you are signed in as the administrator with the sample drive. Then open **Admin panel →
Storage operations** and press **Run now** to watch the optimizer archive the idle ones.

## Sign-in and approval

1. A person chooses **Create account** and picks a username (3 to 30 characters: letters, digits, dot, dash,
   underscore) and a password (at least 8 characters). The account starts in the **Waiting** state.
2. They see a "Waiting for approval" page, which updates by itself once they are approved. Next time they
   simply **Sign in** with the same username and password.
3. An administrator opens **Admin panel → Accounts** and chooses **Approve** or **Reject**. Approved people
   can be **Revoked** later, and rejected people approved. A decision applies immediately, even to someone
   who is signed in.
4. Everyone's drive is private: another account's files and folders do not exist as far as you are concerned.

Administrators are the usernames listed in `auth.admin-usernames` (environment variable
`AUTH_ADMIN_USERNAMES`). Whoever creates an account with one of those names becomes an administrator, approved
automatically, so create that account yourself right after the first start. Administrators cannot be approved,
rejected or revoked by anyone (including each other).

```bash
export AUTH_ADMIN_USERNAMES=admin
cd backend && mvn spring-boot:run
```

### How passwords are handled

- Passwords are stored only as **BCrypt** hashes; they are never returned by the API or written to logs.
- A wrong username and a wrong password give the same answer ("Wrong username or password"), and an unknown
  username takes as long to check as a wrong password.
- After 5 wrong passwords in a row for one username, sign-in is refused for a minute (HTTP 429).
- Every sign-in starts a fresh session id, and every state-changing request needs the CSRF token.
- Set `auth.registration-open=false` to switch **Create account** off once everyone has joined.
- Use HTTPS in front of the server for anything other than local development, so passwords are not sent in
  clear text.

## Using the drive

- **New folder** and **Upload** sit above the file list (and under **New** in the sidebar).
- Search looks across the whole drive by file name.
- **Drag** a file or folder onto a folder row to move it, or onto a breadcrumb to move it up the tree.
- **Drop files from your computer** onto the page to upload into the current folder, or onto a
  folder row to upload straight into that folder.

## Admin storage summary (AI)

**Admin panel → Storage operations → Summarise** gives the administrator a short plain-language summary of
the storage numbers and three suggestions.

How it works:

1. `POST /api/admin/insights` (administrators only; anyone else gets 403).
2. The server collects aggregate numbers from the database: file counts, hot/cold counts, size before and
   after compression, downloads, how many waited on a restore, average response time, last optimizer run.
3. Those numbers are put in a short prompt and sent to Google's **Gemini API** (model **Gemini 3.8 Flash**,
   id `gemini-3.8-flash`) with one REST call: `POST /v1beta/models/gemini-3.8-flash:generateContent`.
4. The answer comes back as JSON (`summary` and `suggestions`), is parsed by the server and shown in the panel.

Privacy and safety:

- **Only totals are sent.** The data object has no text fields, so a file name, folder name or email cannot
  be included (a test enforces this).
- **The key stays on the server** and is never sent to the browser or put in the URL (it travels in the
  `x-goog-api-key` header).
- **It only advises.** Nothing is changed automatically; the admin decides, for example to press "Run now".
- **It cannot break the page.** With no key, a timeout, an error or a refusal, the server answers with a
  built-in summary written from the same numbers, and the panel says it was written without AI.
- The same numbers asked again within five minutes are answered from memory, so repeated clicks cost nothing.

Turn it on by giving the server a Gemini API key from Google AI Studio (it is read from the environment and
never stored in the repo):

```bash
export GEMINI_API_KEY=<your key>
cd backend && mvn spring-boot:run
```

Optional settings in `application.yml`: `insights.model` (default `gemini-3.8-flash`), `insights.max-tokens`
(default 4096, because Gemini 3 counts its internal reasoning against it) and `insights.timeout-seconds`.
Following the model's documentation, no `temperature`, `top_p` or `top_k` is sent. Tests never call the real
API; they run the client against a local fake of the Gemini REST API.

To use a different AI provider, implement the small `AiClient` interface (one method) and register it;
nothing else changes.

## Viewing files

Click a file's name (or **View** in its menu) to open the viewer. Use ← / → to step through the folder and
**Esc** to close.

| File type | What you see |
|---|---|
| Images (png, jpg, gif, webp, svg, …) | The image |
| PDF | The document in the browser's PDF viewer |
| Video (mp4, webm, mov) and audio (mp3, wav, ogg, …) | A player |
| CSV / TSV | A table (first 1,000 rows) |
| Text, markdown, JSON, logs, source code, HTML, XML | The text, in monospace |
| Anything else (zip, docx, xlsx, executables, …) and text over 2 MB | A details card with a **Download** button |

Every file can be opened; types a browser cannot render show the details card instead of failing.
Office documents are not converted, so they download rather than preview.

**Safety:** uploads are served from the app's own origin, so the server decides how to serve each one from
its extension, never from the content type the uploader claimed. HTML, XML and script files are returned
as `text/plain` and shown as source (they never run); SVG is sandboxed by a Content-Security-Policy; and
`X-Content-Type-Options: nosniff` is always set. Viewing counts as opening the file for the optimizer.

## Responsive layout and drag handle

- **Wide screens:** sidebar, full table with a **grip handle** (⠿) that appears on hover. Drag a row by it
  (or anywhere on the row); a small chip with the item's name follows the cursor and the source row dims.
  Folders and breadcrumbs highlight as valid drop targets.
- **Up to 900 px:** the sidebar becomes a drawer opened from the menu button in the top bar.
- **Up to 720 px, and on touch screens:** rows become a compact list with size and date under the name,
  and the hover icons fold into a `⋮` menu (Download, Rename, Move, Delete). Touch screens cannot drag with
  a mouse, so they use **Move** from that menu instead; the grip is hidden there.

## Database

Metadata lives in **PostgreSQL**: accounts, folders, file records and access history. File *bytes* are
separate, under `backend/data/files/`.

Start the local database with `docker compose up -d` (see `docker-compose.yml`). The application connects
with these defaults, which you can override with environment variables:

| Setting | Default | Variable |
|---|---|---|
| JDBC URL | `jdbc:postgresql://localhost:5432/drive` | `DB_URL` |
| User | `drive` | `DB_USER` |
| Password | `drive` | `DB_PASSWORD` |

Tables are created and updated automatically (`spring.jpa.hibernate.ddl-auto: update`), and
`schema.sql` adds the unique indexes that stop two accounts sharing a username. For a real
deployment, use a strong password and a managed PostgreSQL, and replace `ddl-auto` with a migration tool.

Design rule: **no database transaction is held open across slow disk work.** Uploads write the bytes first
and then record them in one short transaction; downloads restore an archived file with no transaction open
and then log the access; and the optimizer reads a snapshot, moves bytes, and writes all results at the end.
This keeps connections free and works the same on any database.

**Backing up:** dump the database and copy the files together:

```bash
docker exec drive-postgres pg_dump -U drive drive > drive-db.sql
```

Keep it with `backend/data/files/`: the database is the index that gives the anonymous file ids their names
and folders.

**Looking inside:**

```bash
docker exec -it drive-postgres psql -U drive -d drive -c "select name, tier, size_bytes from stored_files;"
```

**Tests** run against a real PostgreSQL server started inside the test run (no Docker needed), one database
per test class.

## API walkthrough

`docs/api-walkthrough.html` is a worked example of the API: what it offers, and what really happens when you
create a folder, upload a file and list files. Every request, response, SQL statement, database row and disk
change in it was captured from a running server. To regenerate it, start the backend with
`-Dspring-boot.run.jvmArguments=-Dlogging.level.org.hibernate.SQL=DEBUG`, then run `docs/capture_api.py`
(set `BACKEND_LOG` to the server's log file) and `docs/build_api_docs.py`.

## Architecture

```
React UI ──/api──▶ Controllers ──▶ Services ──▶ FileStore (disk: hot/, cold/*.gz)
                                      │
                                      ├──▶ Repositories (PostgreSQL: accounts, folders, files, access_logs)
                                      └──▶ TieringService ──▶ AccessPredictor
```

| Package     | Responsibility |
|-------------|----------------|
| `file`      | Upload, list, download, rename, move, delete. REST controller, service, JPA entity. |
| `folder`    | The folder tree: create, rename, move, delete (recursive), breadcrumb path. Declares the `FolderContents` port that `file` implements, so the two packages never depend on each other in a cycle. |
| `account`   | Accounts, roles, approval status and the rules for creating and approving them. |
| `security`  | Spring Security: username/password sign-in, session + CSRF, login throttling, the per-request approval check. |
| `admin`     | The administrator's account queue (`/api/admin/users`). |
| `web`       | Maps domain exceptions to problem responses. |
| `storage`   | `FileStore` interface and the local-disk implementation. Bytes only, no metadata. |
| `access`    | One `AccessLog` row per download; the optimizer's training data. |
| `tiering`   | `TierMover` (the only code that changes a file's tier), `TieringService`, nightly scheduler. |
| `optimizer` | `AccessPredictor` interface, trained `LogisticAccessPredictor`, fixed `RecencyAccessPredictor` fallback, feature and training-set builders. |
| `stats`     | Storage usage for users; operational numbers for the admin page. |
| `demo`      | Start-up seeding of sample data. |

### How the optimizer decides

1. Each file is described by four numbers: accesses in the last 30 days, days since last access,
   size, and age.
2. Training data comes from the access log by going back in time: describe every file as it was
   one and two weeks ago, and label it by whether it was opened in the following week.
3. A logistic regression is trained on those examples and scores every file with the probability
   it will be opened in the next week.
4. Files under `optimizer.hot-probability-threshold` (default 40%) move to cold; files above it
   are promoted back to hot. Fresh uploads are never archived in their first day.
5. With fewer than `optimizer.min-training-samples` examples, or only one outcome seen, it falls
   back to a plain recency rule and says so on the Insights page.

Opening a cold file restores it to hot automatically and returns the original bytes. The only user-
visible effect is a slightly slower first download, which is exactly the cost the optimizer tries to
avoid. The admin page tracks how often that happens.

## API

| Method | Path | Purpose |
|--------|------|---------|
| GET    | `/api/auth/config` | Public: whether creating accounts is open |
| POST   | `/api/auth/register` `{ "username", "password" }` | Create an account (starts waiting for approval) and sign in |
| POST   | `/api/auth/login` `{ "username", "password" }` | Sign in |
| GET    | `/api/me` | The signed-in account and its approval status |
| POST   | `/api/auth/logout` | Sign out |
| GET    | `/api/admin/users?status=` | Admin: list accounts (`PENDING`, `APPROVED`, `REJECTED`) |
| POST   | `/api/admin/users/{id}/approve` · `/reject` | Admin: decide on an account |
| GET    | `/api/files?folderId=` | Files in a folder (top level when omitted) |
| GET    | `/api/files?search=` | Files across the whole drive, by name |
| POST   | `/api/files` (multipart `file`, optional `folderId`) | Upload |
| POST   | `/api/files/{id}/move` `{ "folderId": … }` | Move a file (`null` = top level) |
| GET    | `/api/files/{id}/download` | Download (restores cold files) |
| GET    | `/api/files/{id}/view` | Same bytes served inline for the viewer; 415 for types without a safe preview |
| PATCH  | `/api/files/{id}` `{ "name": "…" }` | Rename |
| DELETE | `/api/files/{id}` | Delete |
| GET    | `/api/folders?parentId=` | Subfolders of a folder, with item counts |
| GET    | `/api/folders/all` | Every folder, flat (for the move picker) |
| GET    | `/api/folders/{id}/path` | Folders from the top level down to this one |
| POST   | `/api/folders` `{ "name", "parentId" }` | Create a folder |
| PATCH  | `/api/folders/{id}` `{ "name" }` | Rename |
| POST   | `/api/folders/{id}/move` `{ "parentId": … }` | Move a folder; refuses moves into itself or a descendant |
| DELETE | `/api/folders/{id}` | Delete the folder with all subfolders and files |
| GET    | `/api/storage` | Space used and quota (the only storage info users see) |
| GET    | `/api/admin/stats` | Internal: tier counts, space saved, cold restores, latency |
| GET    | `/api/admin/optimizer` | Internal: threshold and last run |
| POST   | `/api/admin/insights` | Admin: AI (or built-in) summary of the storage numbers |
| POST   | `/api/admin/optimizer/run` | Internal: run the optimizer now |

## Known limits (deliberate, for a core version)

- Tier moves are serialized with an in-process lock; running several instances needs a shared lock.
- Sessions live in memory, so signing in again is needed after a server restart; use Spring Session with a
  database or Redis for several instances.
- Schema changes rely on Hibernate's `ddl-auto: update`, which is fine for development; use Flyway or
  Liquibase migrations before running this for real.
- The database was moved from SQLite to PostgreSQL. An older `backend/data` that holds a SQLite file is not
  read any more (keep it as a backup, or re-upload what you need).
- There is no email notification when an account is approved; people see the change on the waiting page.
- The optimizer loads the whole access log into memory, which is fine for thousands of files, not millions.
- Files are stored on the local disk; swap `LocalFileStore` for an S3/MinIO `FileStore` to scale out.

## Distributed storage

File bytes are stored the way HDFS, Ceph and Cassandra store theirs (`ReplicatedFileStore`):

- A file is cut into chunks (`storage.chunk-bytes`, default 256 KiB) plus a small manifest.
- Each chunk is placed on `storage.replicas` (default 3) of the nodes, chosen by rendezvous hashing, so chunks spread evenly and no node holds everything.
- Reads use any live node holding a chunk: any two nodes can fail at the same time.
- A heartbeat (every 3 s) detects dead node processes. Repair runs when a node goes down, returns or is added, and every 30 s: it re-creates missing copies on live nodes, removes surplus copies and realigns hot/cold tiers. Failures one after another are survived down to one node; three simultaneous failures can make some chunks unavailable (HTTP 503) until a node returns.
- `StorageReconciler` removes stored data the database no longer knows about (for example a delete that could not reach a node that was down).

### Nodes as Docker containers

Each node is a container running `NodeServer` (a small HTTP server around the same `LocalFileStore`):

```bash
docker compose up -d --build node-1 node-2 node-3 node-4 node-5
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,docker-nodes
docker stop drive-node-3        # a real node failure; the panel shows it Unreachable within ~3 s
docker start drive-node-3       # it rejoins and is resynced
```

A node's data is bind-mounted from `backend/data/files/node-N`. Without the `docker-nodes` profile the nodes are plain directories in the backend process (`storage.nodes`, default 5), which is what the tests use. All containers still run on one machine: a whole-machine failure is not covered.

Admin API: `GET /api/admin/storage/nodes`, `POST /api/admin/storage/nodes` (add a directory node), `GET /api/admin/storage/files/{id}/placement`, `POST /api/admin/storage/nodes/{name}/offline|online` (maintenance), `POST /api/admin/storage/nodes/offline|online?names=a,b` (several at once), `POST /api/admin/storage/repair`.

Demonstrations: `docs/prove_containers.sh` (real `docker stop`/`start`, output in `docs/container-proof.txt`), `docs/prove_availability.py` (every single node, pair and triple; `docs/availability-proof.txt`/`.json`). Files from the earlier whole-file layout are re-chunked automatically at start-up.
