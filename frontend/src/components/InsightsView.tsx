import { Play } from 'lucide-react';
import { formatBytes, formatRelativeTime } from '../lib/format';
import type { OptimizerStatus, Stats } from '../types';

interface InsightsViewProps {
  stats: Stats;
  optimizer: OptimizerStatus;
  running: boolean;
  onRunOptimizer: () => void;
}

const MODEL_LABELS: Record<string, string> = {
  'logistic-regression': 'Logistic regression (trained on access history)',
  'recency-baseline': 'Recency rule (not enough history to train)',
};

export function InsightsView({ stats, optimizer, running, onRunOptimizer }: InsightsViewProps) {
  const savedBytes = stats.logicalBytes - stats.storedBytes;
  const savedPercent = stats.logicalBytes > 0 ? Math.round((savedBytes / stats.logicalBytes) * 100) : 0;
  const coldRestorePercent = stats.downloads > 0 ? Math.round((stats.coldRestores / stats.downloads) * 100) : 0;
  const { lastRun } = optimizer;

  return (
    <div className="insights">
      <section className="metric-grid">
        <Metric label="Space saved" value={formatBytes(savedBytes)} detail={`${savedPercent}% smaller on disk`} />
        <Metric label="Files in cold storage" value={`${stats.coldFileCount} of ${stats.fileCount}`} detail="compressed to save space" />
        <Metric
          label="Downloads waiting on a restore"
          value={`${coldRestorePercent}%`}
          detail={`${stats.coldRestores} of ${stats.downloads} downloads`}
        />
        <Metric label="Average time to first byte" value={`${Math.round(stats.averageResponseMs)} ms`} detail="across all downloads" />
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>Storage optimizer</h2>
            <p className="muted">
              Files with less than a {Math.round(optimizer.hotProbabilityThreshold * 100)}% predicted chance of being
              opened this week are moved to cold storage.
            </p>
          </div>
          <button className="button button-primary" onClick={onRunOptimizer} disabled={running}>
            <Play size={16} strokeWidth={2} />
            {running ? 'Running…' : 'Run now'}
          </button>
        </div>

        {lastRun ? (
          <dl className="details">
            <div><dt>Last run</dt><dd>{formatRelativeTime(lastRun.ranAt)}</dd></div>
            <div><dt>Model</dt><dd>{MODEL_LABELS[lastRun.model] ?? lastRun.model}</dd></div>
            <div><dt>Training examples</dt><dd>{lastRun.trainingSamples}</dd></div>
            <div><dt>Files evaluated</dt><dd>{lastRun.filesEvaluated}</dd></div>
            <div><dt>Archived this run</dt><dd>{lastRun.movedToCold}</dd></div>
            <div><dt>Promoted this run</dt><dd>{lastRun.movedToHot}</dd></div>
          </dl>
        ) : (
          <p className="muted">The optimizer has not run since the server started. It also runs automatically every night.</p>
        )}
      </section>
    </div>
  );
}

function Metric({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <div className="metric">
      <p className="metric-label">{label}</p>
      <p className="metric-value">{value}</p>
      <p className="metric-detail">{detail}</p>
    </div>
  );
}
