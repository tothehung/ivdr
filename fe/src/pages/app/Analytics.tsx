import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { useWorkspaceStore } from '../../store/useWorkspaceStore';
import { useAuthStore } from '../../store/useAuthStore';
import { BarChart2, Users, Download, Database, ShieldAlert, Sparkles, Calendar, TrendingUp } from 'lucide-react';

interface Stats {
  totalDocs: number;
  totalDownloads: number;
  activeUsers: number;
  storageBytes: number;
}

interface ActivityPoint {
  date: string;
  count: number;
}

export default function Analytics() {
  const { activeWorkspace } = useWorkspaceStore();
  const { user } = useAuthStore();
  const [stats, setStats] = useState<Stats | null>(null);
  const [heatmap, setHeatmap] = useState<Record<string, number>>({});
  const [timeline, setTimeline] = useState<ActivityPoint[]>([]);
  const [recommendations, setRecommendations] = useState<string>('');
  
  const [isStatsLoading, setIsStatsLoading] = useState(true);
  const [isTimelineLoading, setIsTimelineLoading] = useState(true);
  const [isRecommendLoading, setIsRecommendLoading] = useState(true);
  
  const [hasStatsPermission, setHasStatsPermission] = useState(true);
  const [docNamesMap, setDocNamesMap] = useState<Record<string, string>>({});

  useEffect(() => {
    const fetchTimeline = async () => {
      if (!user) return;
      setIsTimelineLoading(true);
      try {
        const userId = user.userId || user.id;
        const res = await api.get(`/analytics/users/${userId}/timeline`);
        const data = res.data?.data || res.data;
        if (Array.isArray(data)) {
          setTimeline(data);
        }
      } catch (err) {
        console.error('Failed to fetch activity timeline', err);
      } finally {
        setIsTimelineLoading(false);
      }
    };

    const fetchWorkspaceStatsAndHeatmap = async () => {
      if (!activeWorkspace) return;
      setIsStatsLoading(true);
      setHasStatsPermission(true);
      try {
        // Fetch workspace stats
        const statsRes = await api.get(`/analytics/workspace/${activeWorkspace.id}/stats`);
        setStats(statsRes.data?.data || statsRes.data);

        // Fetch heatmap
        const heatmapRes = await api.get(`/analytics/workspace/${activeWorkspace.id}/heatmap`);
        setHeatmap(heatmapRes.data?.data || heatmapRes.data || {});

        // Fetch document details to map IDs to Names
        const docsRes = await api.get(`/workspaces/${activeWorkspace.id}/documents`);
        const docsData = docsRes.data?.data || docsRes.data;
        const docsList = Array.isArray(docsData.content) ? docsData.content : Array.isArray(docsData) ? docsData : [];
        const mapping: Record<string, string> = {};
        docsList.forEach((d: any) => {
          mapping[d.id] = d.name;
        });
        setDocNamesMap(mapping);
      } catch (err: any) {
        console.error('Failed to fetch workspace stats', err);
        if (err.response?.status === 403) {
          setHasStatsPermission(false);
        }
      } finally {
        setIsStatsLoading(false);
      }
    };

    const fetchRecommendations = async () => {
      setIsRecommendLoading(true);
      try {
        const res = await api.get('/ai/recommendations', {
          params: { query: "List core compliance recommendations for IVDR medical device software auditing." }
        });
        setRecommendations(res.data?.data || res.data?.message || res.data || '');
      } catch (err) {
        console.error('Failed to fetch recommendations', err);
        setRecommendations('Unable to fetch compliance recommendations at this time.');
      } finally {
        setIsRecommendLoading(false);
      }
    };

    fetchTimeline();
    fetchWorkspaceStatsAndHeatmap();
    fetchRecommendations();
  }, [activeWorkspace, user]);

  if (!activeWorkspace) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh]">
        <h2 className="text-xl font-semibold">No Workspace Selected</h2>
        <p className="text-muted-foreground mt-2">Select a workspace to view analytics dashboards.</p>
      </div>
    );
  }

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  return (
    <div className="max-w-7xl mx-auto space-y-8">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Workspace Analytics</h1>
        <p className="text-muted-foreground mt-1">
          Detailed metrics, document activity logs, and compliance audits for <span className="text-foreground font-medium">{activeWorkspace.name}</span>.
        </p>
      </div>

      {/* KPI Cards */}
      {hasStatsPermission ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-card border border-border p-6 rounded-xl flex items-center gap-4 shadow-sm">
            <div className="w-12 h-12 rounded-lg bg-primary/10 text-primary flex items-center justify-center">
              <Database className="w-6 h-6" />
            </div>
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Total Documents</p>
              <h3 className="text-2xl font-bold mt-1">{isStatsLoading ? '...' : stats?.totalDocs ?? 0}</h3>
            </div>
          </div>
          <div className="bg-card border border-border p-6 rounded-xl flex items-center gap-4 shadow-sm">
            <div className="w-12 h-12 rounded-lg bg-blue-500/10 text-blue-400 flex items-center justify-center">
              <Download className="w-6 h-6" />
            </div>
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Total Downloads</p>
              <h3 className="text-2xl font-bold mt-1">{isStatsLoading ? '...' : stats?.totalDownloads ?? 0}</h3>
            </div>
          </div>
          <div className="bg-card border border-border p-6 rounded-xl flex items-center gap-4 shadow-sm">
            <div className="w-12 h-12 rounded-lg bg-emerald-500/10 text-emerald-400 flex items-center justify-center">
              <Users className="w-6 h-6" />
            </div>
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Active Users (30d)</p>
              <h3 className="text-2xl font-bold mt-1">{isStatsLoading ? '...' : stats?.activeUsers ?? 0}</h3>
            </div>
          </div>
          <div className="bg-card border border-border p-6 rounded-xl flex items-center gap-4 shadow-sm">
            <div className="w-12 h-12 rounded-lg bg-amber-500/10 text-amber-400 flex items-center justify-center">
              <TrendingUp className="w-6 h-6" />
            </div>
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Storage Utilized</p>
              <h3 className="text-2xl font-bold mt-1">{isStatsLoading ? '...' : stats ? formatBytes(stats.storageBytes) : '0 Bytes'}</h3>
            </div>
          </div>
        </div>
      ) : (
        <div className="p-4 bg-amber-500/10 border border-amber-500/20 text-amber-400 text-sm rounded-xl flex items-center gap-2">
          <ShieldAlert className="w-5 h-5" />
          <span>Note: Workspace stats and heatmap require an <strong>Admin</strong> or <strong>Manager</strong> role. Showing timeline stats only.</span>
        </div>
      )}

      {/* Main Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Heatmap & Timeline column */}
        <div className="lg:col-span-2 space-y-6">
          
          {/* Heatmap Section */}
          {hasStatsPermission && (
            <div className="bg-card border border-border rounded-xl p-6 shadow-sm">
              <h3 className="font-semibold text-lg mb-4 flex items-center gap-2">
                <BarChart2 className="w-5 h-5 text-muted-foreground" /> Document Heatmap (Hottest Downloads)
              </h3>
              {isStatsLoading ? (
                <div className="h-48 flex items-center justify-center text-muted-foreground">Loading heatmap data...</div>
              ) : Object.keys(heatmap).length === 0 ? (
                <div className="h-48 flex items-center justify-center text-muted-foreground">No downloads recorded for this workspace.</div>
              ) : (
                <div className="space-y-4">
                  {Object.entries(heatmap).map(([docId, count]) => {
                    const docName = docNamesMap[docId] || `Document (id: ${docId.substring(0,6)}...)`;
                    return (
                      <div key={docId} className="flex items-center justify-between text-sm">
                        <div className="flex-1 mr-4">
                          <p className="font-medium text-foreground truncate">{docName}</p>
                          <div className="w-full bg-accent rounded-full h-2 mt-1.5">
                            <div 
                              className="bg-primary h-2 rounded-full transition-all duration-500" 
                              style={{ width: `${Math.min(100, (count / Math.max(...Object.values(heatmap))) * 100)}%` }}
                            />
                          </div>
                        </div>
                        <span className="bg-accent px-2.5 py-1 rounded text-xs font-semibold shrink-0">{count} views</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* User Timeline Section */}
          <div className="bg-card border border-border rounded-xl p-6 shadow-sm">
            <h3 className="font-semibold text-lg mb-4 flex items-center gap-2">
              <Calendar className="w-5 h-5 text-muted-foreground" /> Your Activity Timeline (Last 30 Days)
            </h3>
            {isTimelineLoading ? (
              <div className="h-48 flex items-center justify-center text-muted-foreground">Loading timeline logs...</div>
            ) : timeline.length === 0 ? (
              <div className="h-48 flex items-center justify-center text-muted-foreground">No activities logged in the last 30 days.</div>
            ) : (
              <div className="space-y-4">
                {timeline.map((pt, idx) => (
                  <div key={idx} className="flex items-center gap-4 text-sm border-l-2 border-border pl-4 relative ml-2">
                    <div className="w-3 h-3 rounded-full bg-primary absolute -left-[7px] top-1.5" />
                    <div className="flex-1">
                      <p className="font-medium text-foreground">{new Date(pt.date).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' })}</p>
                      <p className="text-muted-foreground text-xs">{pt.count} events logged</p>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* AI Recommendations & Insights column */}
        <div className="space-y-6">
          <div className="bg-card border border-border rounded-xl p-6 shadow-sm bg-gradient-to-br from-card to-accent/5">
            <h3 className="font-semibold text-lg mb-4 flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-primary animate-pulse" /> AI Compliance Auditor
            </h3>
            {isRecommendLoading ? (
              <div className="space-y-2">
                <div className="h-4 bg-muted rounded w-3/4 animate-pulse"></div>
                <div className="h-4 bg-muted rounded w-5/6 animate-pulse"></div>
                <div className="h-4 bg-muted rounded w-2/3 animate-pulse"></div>
              </div>
            ) : (
              <div className="text-sm leading-relaxed text-muted-foreground bg-background/50 border border-border rounded-lg p-4 font-mono whitespace-pre-wrap max-h-[50vh] overflow-y-auto">
                {recommendations}
              </div>
            )}
            <div className="mt-4 p-3 bg-primary/10 rounded-lg border border-primary/20 text-xs text-primary font-medium">
              💡 Use the AI Chat page to retrieve custom recommendations targeting specific files.
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}
