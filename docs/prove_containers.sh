#!/usr/bin/env bash
# Real failures: each storage node is its own Docker container, so `docker stop` is a genuine node crash.
# Needs the backend running with:  -Dspring-boot.run.profiles=dev,docker-nodes
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
JAR=$(mktemp)
tok() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR"; }
post() { curl -s -X POST "$BASE$1" -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(tok)" "${@:2}"; }
get() { curl -s "$BASE$1" -b "$JAR"; }
cluster() { python3 -c "import sys,json; d=json.load(sys.stdin); [print('   %-7s %-12s %-22s chunks=%s' % (n['name'], n['state'], n['endpoint'], n['chunks'])) for n in d['nodes']]; print('   %d files, %d chunks | more nodes that can fail: %d' % (d['files'], d['chunks'], d['nodesThatCanFail']))"; }
chunks() { python3 -c "import sys,json; d=json.load(sys.stdin); [print('   chunk %d  on %s' % (c['index'], ', '.join(n['name'] for n in c['nodes']))) for c in d['chunks']]"; }
states() { get /api/admin/storage/nodes | python3 -c "import sys,json; print(' '.join(n['state'] for n in json.load(sys.stdin)['nodes']))"; }
wait_for() { for _ in $(seq 1 40); do [ "$(states | tr ' ' '\n' | grep -c "$1")" -eq "$2" ] && return 0; sleep 0.5; done; return 1; }

curl -s "$BASE/api/auth/config" -c "$JAR" >/dev/null
# DRIVE_USER / DRIVE_PASSWORD: an administrator account
post /api/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"${DRIVE_USER:?set DRIVE_USER}\",\"password\":\"${DRIVE_PASSWORD:?set DRIVE_PASSWORD}\"}" >/dev/null

echo "1. Storage nodes running as containers"
docker ps --filter name=drive-node --format '   {{.Names}}  {{.Status}}  {{.Ports}}' | sort

head -c 700000 /dev/urandom > /tmp/proof-c.bin
SUM=$(sha256sum /tmp/proof-c.bin | cut -d' ' -f1)
ID=$(post /api/files -F "file=@/tmp/proof-c.bin;type=application/octet-stream" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo; echo "2. Upload 700000 bytes (sha256 ${SUM:0:16}...): chunks on containers"
get /api/admin/storage/files/$ID/placement | chunks

A=$(get /api/admin/storage/files/$ID/placement | python3 -c 'import sys,json; d=json.load(sys.stdin); print(" ".join(n["name"] for n in d["chunks"][0]["nodes"][:2]))')
set -- $A
echo; echo "3. docker stop drive-$1 drive-$2   (two containers crash together)"
docker stop drive-$1 drive-$2 >/dev/null
START=$(date +%s.%N)
wait_for UNREACHABLE 2 && echo "   heartbeat noticed both within $(python3 -c "print(round($(date +%s.%N)-$START,1))") s, with no administrator action"
get /api/admin/storage/nodes | cluster

echo; echo "4. Download while two containers are dead"
GOT=$(get /api/files/$ID/download | sha256sum | cut -d' ' -f1)
echo "   sha256 of download: ${GOT:0:16}...  original: ${SUM:0:16}...  -> $([ "$GOT" = "$SUM" ] && echo MATCH || echo MISMATCH)"

echo; echo "5. Upload a second file during the outage"
head -c 300000 /dev/urandom > /tmp/proof-c2.bin
ID2=$(post /api/files -F "file=@/tmp/proof-c2.bin;type=application/octet-stream" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
get /api/admin/storage/files/$ID2/placement | chunks

echo; echo "6. docker start drive-$1 drive-$2"
docker start drive-$1 drive-$2 >/dev/null
wait_for ONLINE 5 && echo "   all 5 nodes online again"
sleep 1
get /api/admin/storage/nodes | cluster
echo "   second file after recovery:"
get /api/admin/storage/files/$ID2/placement | chunks

echo; echo "7. Failures one after another: node-1, node-2, node-3, node-4 containers stopped in turn"
for n in 1 2 3 4; do
  docker stop drive-node-$n >/dev/null
  wait_for UNREACHABLE $n || true
  sleep 2
  GOT=$(get /api/files/$ID/download | sha256sum | cut -d' ' -f1)
  echo "   drive-node-$n stopped, $((5-n)) container(s) left: download $([ "$GOT" = "$SUM" ] && echo MATCH || echo MISMATCH)"
done
docker start drive-node-1 drive-node-2 drive-node-3 drive-node-4 >/dev/null
wait_for ONLINE 5 && echo "   all containers started again"

echo; echo "8. Clean up"
for f in $ID $ID2; do curl -s -X DELETE "$BASE/api/files/$f" -b "$JAR" -H "X-XSRF-TOKEN: $(tok)" -o /dev/null -w "   delete status %{http_code}\n"; done
rm -f "$JAR" /tmp/proof-c.bin /tmp/proof-c2.bin
