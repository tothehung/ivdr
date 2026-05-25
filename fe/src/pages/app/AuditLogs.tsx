import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { Shield, ShieldAlert, Sparkles, Search, Filter, X, ChevronLeft, ChevronRight, Eye } from 'lucide-react';

interface AuditLog {
  id: string;
  eventId: string;
  userId: string;
  eventType: string;
  resourceType: string;
  resourceId: string;
  ipAddress: string;
  userAgent: string;
  isAnomaly: boolean;
  createdAt: string;
  metadata: Record<string, any>;
}

export default function AuditLogs() {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  
  // Pagination
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [pageSize] = useState(20);

  // Filters
  const [filterType, setFilterType] = useState<'all' | 'anomalies' | 'eventType' | 'userId'>('all');
  const [selectedEventType, setSelectedEventType] = useState('USER_LOGIN');
  const [searchUserId, setSearchUserId] = useState('');
  const [hasPermission, setHasPermission] = useState(true);

  // AI Explain Anomaly Modal
  const [explainingLog, setExplainingLog] = useState<AuditLog | null>(null);
  const [aiExplanation, setAiExplanation] = useState('');
  const [isExplainingLoading, setIsExplainingLoading] = useState(false);

  // Metadata Modal
  const [viewingMetadata, setViewingMetadata] = useState<Record<string, any> | null>(null);

  const fetchLogs = async () => {
    setIsLoading(true);
    setError('');
    setHasPermission(true);
    try {
      let url = '/audit/logs';
      const params: Record<string, any> = {
        page,
        size: pageSize,
        sort: 'createdAt,desc'
      };

      if (filterType === 'anomalies') {
        url = '/audit/anomalies';
      } else if (filterType === 'eventType' && selectedEventType) {
        url = `/audit/logs/type/${selectedEventType}`;
      } else if (filterType === 'userId' && searchUserId.trim()) {
        url = `/audit/logs/user/${searchUserId.trim()}`;
      }

      const res = await api.get(url, { params });
      const data = res.data;
      
      if (data) {
        setLogs(data.content || []);
        setTotalPages(data.totalPages || 0);
      }
    } catch (err: any) {
      console.error('Failed to fetch audit logs', err);
      if (err.response?.status === 403) {
        setHasPermission(false);
      } else {
        setError(err.response?.data?.message || 'Failed to load audit logs.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs();
  }, [page, filterType, selectedEventType]);

  const handleUserSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    fetchLogs();
  };

  const handleExplainAnomaly = async (logEntry: AuditLog) => {
    setExplainingLog(logEntry);
    setIsExplainingLoading(true);
    setAiExplanation('');
    try {
      const res = await api.post(`/ai/explain-anomaly/${logEntry.id}`, {
        eventType: logEntry.eventType,
        userId: logEntry.userId,
        metadata: logEntry.metadata || {}
      });
      setAiExplanation(res.data?.data || res.data?.message || res.data || 'No explanation generated.');
    } catch (err: any) {
      console.error('Failed to get AI anomaly explanation', err);
      setAiExplanation(err.response?.data?.message || 'Failed to generate explanation. Ensure AI service is configured.');
    } finally {
      setIsExplainingLoading(false);
    }
  };

  if (!hasPermission) {
    return (
      <div className="max-w-4xl mx-auto flex flex-col items-center justify-center min-h-[60vh] text-center p-6 bg-card border border-border rounded-xl">
        <ShieldAlert className="w-12 h-12 text-destructive mb-4 animate-bounce" />
        <h2 className="text-xl font-bold">Access Denied</h2>
        <p className="text-muted-foreground mt-2 max-w-md">
          The Audit Log is restricted to system <strong>Administrators</strong> and <strong>Managers</strong> only. Contact your organization owner for access.
        </p>
      </div>
    );
  }

  const eventTypes = [
    'USER_REGISTERED', 'USER_LOGIN', 'USER_LOGOUT', 'LOGIN_FAILED', 'ACCOUNT_LOCKED',
    'DOCUMENT_UPLOADED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_DELETED', 'DOCUMENT_VIEWED', 'DOCUMENT_AI_SUMMARIZED',
    'WORKSPACE_CREATED', 'WORKSPACE_DELETED', 'WORKSPACE_MEMBER_ADDED', 'WORKSPACE_MEMBER_REMOVED',
    'PERMISSION_DENIED', 'ANOMALY_DETECTED', 'RATE_LIMIT_EXCEEDED'
  ];

  return (
    <div className="max-w-7xl mx-auto space-y-6 relative">
      
      {/* AI Explain Modal */}
      {explainingLog && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-2xl flex flex-col overflow-hidden">
            <div className="flex justify-between items-center p-4 border-b border-border bg-muted/30">
              <div className="flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-primary animate-pulse" />
                <h3 className="font-bold">AI Threat / Anomaly Audit Explanation</h3>
              </div>
              <button onClick={() => setExplainingLog(null)} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-6 overflow-y-auto space-y-4 max-h-[60vh]">
              <div className="p-3 bg-accent rounded text-xs grid grid-cols-2 gap-2">
                <div><strong>Event ID:</strong> {explainingLog.eventId}</div>
                <div><strong>Event Type:</strong> {explainingLog.eventType}</div>
                <div><strong>User ID:</strong> {explainingLog.userId || 'System'}</div>
                <div><strong>IP Address:</strong> {explainingLog.ipAddress || 'N/A'}</div>
              </div>
              <div>
                <h4 className="font-semibold text-sm mb-2">Security Analysis & Root Cause</h4>
                {isExplainingLoading ? (
                  <div className="text-sm text-muted-foreground flex items-center gap-2 p-4 bg-muted/20 border border-dashed border-border rounded-lg justify-center">
                    <span className="animate-spin">⏳</span> Running AI trace across compliance parameters...
                  </div>
                ) : (
                  <div className="text-sm leading-relaxed text-muted-foreground bg-background border border-border rounded-lg p-4 font-mono whitespace-pre-wrap">
                    {aiExplanation}
                  </div>
                )}
              </div>
            </div>
            <div className="p-4 border-t border-border bg-muted/15 flex justify-end">
              <button onClick={() => setExplainingLog(null)} className="px-4 py-2 bg-foreground text-background rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity">
                Close Analysis
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Metadata View Modal */}
      {viewingMetadata && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-lg p-6">
            <div className="flex justify-between items-center mb-4">
              <h3 className="font-bold text-lg">Event Metadata Context</h3>
              <button onClick={() => setViewingMetadata(null)} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <pre className="bg-background border border-border p-4 rounded-lg text-xs overflow-auto font-mono text-muted-foreground max-h-[40vh]">
              {JSON.stringify(viewingMetadata, null, 2)}
            </pre>
          </div>
        </div>
      )}

      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-2">
            Audit Logs Explorer <Shield className="w-6 h-6 text-foreground" />
          </h1>
          <p className="text-muted-foreground mt-1">
            Immutable system logs, security compliance logs, and rate limit triggers.
          </p>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="bg-card border border-border rounded-xl p-4 flex flex-wrap gap-4 items-center shadow-sm">
        <div className="flex items-center gap-2 border-r border-border pr-4 mr-2">
          <Filter className="w-4 h-4 text-muted-foreground" />
          <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Filter By</span>
        </div>

        {/* Core Filter Options */}
        <div className="flex gap-2">
          <button 
            onClick={() => { setFilterType('all'); setPage(0); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
              filterType === 'all' 
                ? 'bg-foreground text-background border-foreground' 
                : 'bg-card text-muted-foreground border-border hover:text-foreground'
            }`}
          >
            All Logs
          </button>
          <button 
            onClick={() => { setFilterType('anomalies'); setPage(0); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all flex items-center gap-1.5 ${
              filterType === 'anomalies' 
                ? 'bg-amber-500 text-black border-amber-500' 
                : 'bg-card text-muted-foreground border-border hover:text-foreground hover:border-amber-500/50'
            }`}
          >
            ⚠️ Anomalies Only
          </button>
          <button 
            onClick={() => { setFilterType('eventType'); setPage(0); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
              filterType === 'eventType' 
                ? 'bg-foreground text-background border-foreground' 
                : 'bg-card text-muted-foreground border-border hover:text-foreground'
            }`}
          >
            Event Type
          </button>
          <button 
            onClick={() => { setFilterType('userId'); setPage(0); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
              filterType === 'userId' 
                ? 'bg-foreground text-background border-foreground' 
                : 'bg-card text-muted-foreground border-border hover:text-foreground'
            }`}
          >
            User ID
          </button>
        </div>

        {/* Dynamic filter forms */}
        {filterType === 'eventType' && (
          <select
            value={selectedEventType}
            onChange={e => { setSelectedEventType(e.target.value); setPage(0); }}
            className="bg-background border border-border rounded-lg text-xs px-2 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary"
          >
            {eventTypes.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        )}

        {filterType === 'userId' && (
          <form onSubmit={handleUserSearchSubmit} className="flex gap-2">
            <input 
              type="text" 
              value={searchUserId}
              onChange={e => setSearchUserId(e.target.value)}
              placeholder="Paste User ID (UUID)..."
              className="bg-background border border-border rounded-lg text-xs px-3 py-1.5 focus:outline-none w-48 font-mono"
            />
            <button type="submit" className="bg-foreground text-background hover:opacity-90 px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1 cursor-pointer">
              <Search className="w-3.5 h-3.5" /> Search
            </button>
          </form>
        )}
      </div>

      {/* Grid Log Table */}
      <div className="bg-card border border-border rounded-xl overflow-hidden shadow-sm flex flex-col">
        <table className="w-full text-left text-sm">
          <thead className="bg-background border-b border-border text-muted-foreground">
            <tr>
              <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">Timestamp</th>
              <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">User ID</th>
              <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">Event Type</th>
              <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">IP / Resource</th>
              <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {isLoading ? (
              <tr>
                <td colSpan={5} className="px-6 py-12 text-center text-muted-foreground">Loading audit log data...</td>
              </tr>
            ) : error ? (
              <tr>
                <td colSpan={5} className="px-6 py-12 text-center text-destructive">{error}</td>
              </tr>
            ) : logs.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-6 py-20 text-center text-muted-foreground">No matching audit logs found.</td>
              </tr>
            ) : (
              logs.map(logEntry => (
                <tr key={logEntry.id} className="hover:bg-accent/40 transition-colors group">
                  <td className="px-6 py-4 text-xs font-mono text-muted-foreground">
                    {new Date(logEntry.createdAt).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 text-xs font-mono text-muted-foreground">
                    {logEntry.userId ? `${logEntry.userId.substring(0,8)}...` : 'System'}
                  </td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded text-[10px] font-bold tracking-wider uppercase border ${
                      logEntry.isAnomaly 
                        ? 'bg-amber-500/10 border-amber-500/20 text-amber-500' 
                        : logEntry.eventType.includes('DELETED') || logEntry.eventType.includes('FAILED') || logEntry.eventType.includes('DENIED')
                        ? 'bg-destructive/10 border-destructive/20 text-destructive'
                        : logEntry.eventType.includes('UPLOADED') || logEntry.eventType.includes('REGISTERED')
                        ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400'
                        : 'bg-zinc-500/10 border-zinc-500/20 text-zinc-400'
                    }`}>
                      {logEntry.isAnomaly && '⚠️ '}{logEntry.eventType}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <div className="text-xs">
                      <span className="text-muted-foreground font-semibold">IP:</span> {logEntry.ipAddress || 'Unknown'}
                    </div>
                    {logEntry.resourceType && (
                      <div className="text-[10px] text-muted-foreground mt-0.5">
                        <span className="font-semibold uppercase">{logEntry.resourceType}:</span> {logEntry.resourceId?.substring(0, 12)}...
                      </div>
                    )}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center justify-end gap-2">
                      {logEntry.metadata && Object.keys(logEntry.metadata).length > 0 && (
                        <button 
                          onClick={() => setViewingMetadata(logEntry.metadata)}
                          className="p-1.5 hover:bg-background rounded text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
                          title="View metadata payload"
                        >
                          <Eye className="w-4 h-4" />
                        </button>
                      )}
                      {logEntry.isAnomaly && (
                        <button 
                          onClick={() => handleExplainAnomaly(logEntry)}
                          className="flex items-center gap-1 px-2.5 py-1 bg-amber-500/15 border border-amber-500/35 hover:bg-amber-500/25 text-amber-500 rounded text-xs font-semibold transition-colors cursor-pointer"
                          title="Explain suspicious log with AI"
                        >
                          <Sparkles className="w-3.5 h-3.5" /> Explain
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {/* Custom Paginator Footer */}
        {totalPages > 1 && (
          <div className="bg-background border-t border-border px-6 py-4 flex items-center justify-between">
            <span className="text-xs text-muted-foreground">
              Page <strong>{page + 1}</strong> of <strong>{totalPages}</strong>
            </span>
            <div className="flex gap-2">
              <button 
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0 || isLoading}
                className="p-1.5 border border-border rounded-lg bg-card hover:bg-accent disabled:opacity-50 text-muted-foreground hover:text-foreground transition-all cursor-pointer"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button 
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page === totalPages - 1 || isLoading}
                className="p-1.5 border border-border rounded-lg bg-card hover:bg-accent disabled:opacity-50 text-muted-foreground hover:text-foreground transition-all cursor-pointer"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
