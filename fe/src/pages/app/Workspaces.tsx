import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { useWorkspaceStore } from '../../store/useWorkspaceStore';
import { useNavigate } from 'react-router-dom';
import { Plus, Folder, Users } from 'lucide-react';

export default function Workspaces() {
  const [workspaces, setWorkspaces] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [newWsName, setNewWsName] = useState('');
  
  const { setActiveWorkspace } = useWorkspaceStore();
  const navigate = useNavigate();

  const fetchWorkspaces = async () => {
    try {
      const res = await api.get('/workspaces');
      const data = res.data?.data || res.data;
      if (data && Array.isArray(data.content)) {
        setWorkspaces(data.content);
      } else if (Array.isArray(data)) {
        setWorkspaces(data);
      } else {
        setWorkspaces([]);
      }
    } catch (err) {
      console.error('Failed to fetch workspaces', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchWorkspaces();
  }, []);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newWsName.trim()) return;
    try {
      await api.post('/workspaces', { name: newWsName });
      setNewWsName('');
      setIsCreating(false);
      fetchWorkspaces();
    } catch (err) {
      console.error('Failed to create workspace', err);
    }
  };

  const selectWorkspace = (ws: any) => {
    setActiveWorkspace(ws);
    navigate('/app/documents');
  };

  return (
    <div className="max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Workspaces</h1>
          <p className="text-muted-foreground mt-1">Manage your team's document workspaces.</p>
        </div>
        <button 
          onClick={() => setIsCreating(true)}
          className="flex items-center gap-2 bg-foreground text-background px-4 py-2 rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity"
        >
          <Plus className="w-4 h-4" /> New Workspace
        </button>
      </div>

      {isCreating && (
        <form onSubmit={handleCreate} className="mb-8 p-6 bg-card border border-border rounded-xl shadow-sm flex items-end gap-4">
          <div className="flex-1 flex flex-col gap-2">
            <label className="text-sm font-medium">Workspace Name</label>
            <input 
              type="text" 
              value={newWsName}
              onChange={e => setNewWsName(e.target.value)}
              className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="e.g. Finance Q3"
              autoFocus
            />
          </div>
          <button type="submit" className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-sm font-semibold hover:bg-primary/90">
            Create
          </button>
          <button type="button" onClick={() => setIsCreating(false)} className="px-4 py-2 rounded-lg text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-accent">
            Cancel
          </button>
        </form>
      )}

      {isLoading ? (
        <div className="text-center py-20 text-muted-foreground">Loading workspaces...</div>
      ) : workspaces.length === 0 ? (
        <div className="text-center py-20 border border-dashed border-border rounded-xl bg-card/50">
          <Folder className="w-10 h-10 mx-auto text-muted-foreground mb-4 opacity-50" />
          <h3 className="text-lg font-medium">No Workspaces Found</h3>
          <p className="text-muted-foreground text-sm mt-1">Create your first workspace to get started.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {workspaces.map(ws => (
            <div 
              key={ws.id} 
              onClick={() => selectWorkspace(ws)}
              className="p-6 bg-card border border-border rounded-xl hover:border-foreground/50 transition-colors cursor-pointer group flex flex-col"
            >
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-lg bg-accent flex items-center justify-center text-foreground group-hover:bg-foreground group-hover:text-background transition-colors">
                  <Folder className="w-5 h-5" />
                </div>
                <h3 className="font-semibold text-lg">{ws.name}</h3>
              </div>
              <div className="mt-auto flex items-center gap-2 text-sm text-muted-foreground">
                <Users className="w-4 h-4" /> {ws.role || 'Member'}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
