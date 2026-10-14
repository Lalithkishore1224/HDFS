import { Sparkles } from 'lucide-react';
import { useState } from 'react';
import { api } from '../api/client';
import { formatRelativeTime } from '../lib/format';

const STATUS_LABELS = { OK: 'All good', ATTENTION: 'Needs attention', CRITICAL: 'Critical' };

/**
 * "Summarise": asks the server for a plain-language summary of the storage numbers.
 * Only aggregate numbers are used on the server side; file and folder names are never involved.
 */
export function InsightsPanel() {
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const summarise = async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await api.summariseStorage());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not create the summary');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="panel insights-panel">
      <div className="panel-header">
        <div>
          <h2>Storage summary</h2>
          <p className="muted">
            A short explanation of the numbers above, with a few suggestions. Only totals are used, never file names or
            contents. Nothing is changed automatically.
          </p>
        </div>
        <button className="button button-primary" onClick={() => void summarise()} disabled={loading}>
          <Sparkles size={16} strokeWidth={2} />
          {loading ? 'Summarising…' : 'Summarise'}
        </button>
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {report && (
        <div className="insight-result">
          {report.status && (
            <p className="insight-status">
              <span className={`status-pill status-${report.status.toLowerCase()}`}>{STATUS_LABELS[report.status]}</span>
              {report.headline && <strong className="insight-headline">{report.headline}</strong>}
            </p>
          )}
          <p className="insight-summary">{report.summary}</p>

          {report.findings?.length > 0 && (
            <>
              <h3 className="insight-heading">What the numbers show</h3>
              <ul className="insight-facts">
                {report.findings.map((finding, index) => (
                  <li key={index}>{finding}</li>
                ))}
              </ul>
            </>
          )}

          {report.suggestions.length > 0 && (
            <>
              <h3 className="insight-heading">What to do</h3>
              <ol className="insight-list">
                {report.suggestions.map((suggestion, index) => (
                  <li key={index}>{suggestion}</li>
                ))}
              </ol>
            </>
          )}

          <p className="insight-source">
            {report.source === 'AI' ? `Written by AI (${report.model})` : 'Built-in summary, written without AI'}
            {' · '}
            {formatRelativeTime(report.generatedAt)}
          </p>
          {report.note && <p className="insight-note">{report.note}</p>}
        </div>
      )}
    </section>
  );
}
