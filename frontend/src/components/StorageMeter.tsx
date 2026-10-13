import { formatBytes } from '../lib/format';
import type { StorageUsage } from '../types';

export function StorageMeter({ usage }: { usage: StorageUsage | null }) {
  if (!usage) return null;
  const usedPercent = Math.min(100, (usage.usedBytes / usage.quotaBytes) * 100);

  return (
    <div className="storage-meter">
      <div className="meter-track" role="progressbar" aria-valuenow={Math.round(usedPercent)} aria-valuemin={0} aria-valuemax={100}>
        <div className="meter-fill" style={{ width: `${Math.max(usedPercent, 1)}%` }} />
      </div>
      <p className="meter-label">
        {formatBytes(usage.usedBytes)} of {formatBytes(usage.quotaBytes)} used
      </p>
    </div>
  );
}
