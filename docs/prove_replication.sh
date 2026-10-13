#!/usr/bin/env bash
# Live demonstration on a running dev instance: upload a file, show where its chunks are, fail two
# nodes at once, download it, write another file during the outage, bring the nodes back.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
JAR=$(mktemp)
tok() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR"; }
post() { curl -s -X POST "$BASE$1" -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(tok)" "${@:2}"; }
get() { curl -s "$BASE$1" -b "$JAR"; }
cluster() { python3 -c "import sys,json; d=json.load(sys.stdin); [print('   %-7s %-8s chunks=%-3s' % (n['name'], 'online' if n['online'] else 'OFFLINE', n['chunks'])) for n in d['nodes']]; print('   %d files, %d chunks, each chunk on %d of %d nodes | chunks being re-copied: %d | more nodes that can fail: %d' % (d['files'], d['chunks'], d['replicationFactor'], len(d['nodes']), d['underReplicatedChunks'], d['nodesThatCanFail']))"; }
chunks() { python3 -c "import sys,json; d=json.load(sys.stdin); [print('   chunk %d  on %s' % (c['index'], ', '.join(n['name'] + ('' if n['online'] else ' (offline)') for n in c['nodes']))) for c in d['chunks']]"; }

curl -s "$BASE/api/auth/config" -c "$JAR" >/dev/null
post /api/auth/dev-login -H 'Content-Type: application/json' -d '{"email":"admin@dev.local","name":"Dev Admin"}' >/dev/null

head -c 700000 /dev/urandom > /tmp/proof-file.bin
SUM=$(sha256sum /tmp/proof-file.bin | cut -d' ' -f1)
echo "1. Upload proof-file.bin ($(stat -c%s /tmp/proof-file.bin) bytes, sha256 ${SUM:0:16}...)"
ID=$(post /api/files -F "file=@/tmp/proof-file.bin;type=application/octet-stream" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

echo; echo "2. The file is cut into chunks; each chunk is on 3 of the 5 nodes"
get /api/admin/storage/files/$ID/placement | chunks

echo; echo "3. The cluster"
get /api/admin/storage/nodes | cluster

A=$(get /api/admin/storage/files/$ID/placement | python3 -c 'import sys,json; d=json.load(sys.stdin); print(",".join(n["name"] for n in d["chunks"][0]["nodes"][:2]))')
echo; echo "4. Two nodes fail at the same time: $A (both hold chunk 0)"
post "/api/admin/storage/nodes/offline?names=$A" | cluster

echo; echo "5. Download the file while they are down"
GOT=$(get /api/files/$ID/download | sha256sum | cut -d' ' -f1)
echo "   sha256 of download: ${GOT:0:16}...  original: ${SUM:0:16}...  -> $([ "$GOT" = "$SUM" ] && echo MATCH || echo MISMATCH)"

echo; echo "6. Upload a second file during the outage"
head -c 300000 /dev/urandom > /tmp/proof-file2.bin
ID2=$(post /api/files -F "file=@/tmp/proof-file2.bin;type=application/octet-stream" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
get /api/admin/storage/files/$ID2/placement | chunks

echo; echo "7. The two nodes return: repair completes the copies automatically"
post "/api/admin/storage/nodes/online?names=$A" | cluster
get /api/admin/storage/files/$ID2/placement | chunks

echo; echo "8. Clean up"
for f in $ID $ID2; do curl -s -X DELETE "$BASE/api/files/$f" -b "$JAR" -H "X-XSRF-TOKEN: $(tok)" -o /dev/null -w "   delete status %{http_code}\n"; done
rm -f "$JAR" /tmp/proof-file.bin /tmp/proof-file2.bin
