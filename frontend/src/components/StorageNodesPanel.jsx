import { Plus, Wrench } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { formatBytes } from '../lib/format';

/**
 * The storage nodes behind the drive. Files are cut into chunks and every chunk is kept on several
 * nodes, so nodes can fail (or be added) without any file becoming unavailable.
 */
export function StorageNodesPanel() {
  const [cluster, setCluster] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  const load = useCallback(async () => {
    try {
      setCluster(await api.storageNodes());
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Could not load storage nodes');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (action) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'The request failed');
    }
    await load();
    setBusy(false);
  };

  if (!cluster) return null;

  const onlineNodes = cluster.nodes.filter((node) => node.online).length;

  return (
    <section className="panel nodes-panel">
      <div className="panel-header">
        <div>
          <h2>Storage nodes</h2>
          <p className="muted">
            {cluster.files} files in {cluster.chunks} chunks; each chunk is kept on {cluster.replicationFactor} of{' '}
            {cluster.nodes.length} nodes. {onlineNodes} online.{' '}
            {cluster.underReplicatedChunks > 0 ? `${cluster.underReplicatedChunks} chunks are being re-copied. ` : ''}
            {cluster.nodesThatCanFail === 0
              ? 'No further node can fail without making a file unavailable.'
              : `${cluster.nodesThatCanFail} more ${cluster.nodesThatCanFail === 1 ? 'node' : 'nodes'} can fail and every file stays available.`}
          </p>
        </div>
        <div className="toolbar-actions">
          {!cluster.separateProcesses && (
            <button
              className="button button-outline"
              disabled={busy}
              onClick={() => void run(() => api.addStorageNode())}
            >
              <Plus size={16} strokeWidth={2} />
              Add node
            </button>
          )}
          <button
            className="button button-primary"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const result = await api.repairStorage();
                setMessage(`Repair made ${result.copiesMade} new ${result.copiesMade === 1 ? 'copy' : 'copies'}.`);
              })
            }
          >
            <Wrench size={16} strokeWidth={2} />
            Repair copies
          </button>
        </div>
      </div>

      <table className="nodes-table">
        <thead>
          <tr>
            <th>Node</th>
            <th>Status</th>
            <th>Chunks</th>
            <th>Stored</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {cluster.nodes.map((node) => (
            <tr key={node.name}>
              <td>
                {node.name}
                {node.endpoint && <span className="muted node-endpoint">{node.endpoint.replace('http://', '')}</span>}
              </td>
              <td>
                <span className={`node-state ${node.online ? 'node-online' : 'node-offline'}`}>
                  {node.state === 'ONLINE' ? 'Online' : node.state === 'MAINTENANCE' ? 'Maintenance' : 'Unreachable'}
                </span>
              </td>
              <td>{node.chunks}</td>
              <td>{formatBytes(node.bytes)}</td>
              <td className="nodes-action">
                <button
                  className="button button-outline button-small"
                  disabled={busy || node.state === 'UNREACHABLE'}
                  onClick={() => void run(() => api.setNodeOnline(node.name, !node.online))}
                >
                  {node.online ? 'Maintenance' : 'Bring online'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {message && (
        <p className="muted nodes-message" role="status">
          {message}
        </p>
      )}
    </section>
  );
}
