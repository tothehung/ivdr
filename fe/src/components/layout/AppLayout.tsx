import { useState } from 'react';
import { Outlet, Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import { LayoutDashboard, Folder, MessageSquare, BarChart, LogOut, Activity } from 'lucide-react';

export default function AppLayout() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const [copied, setCopied] = useState(false);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handleCopyId = () => {
    const idToCopy = user?.userId || user?.id;
    if (idToCopy) {
      navigator.clipboard.writeText(idToCopy);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="min-h-screen bg-background text-foreground flex flex-col">
      {/* Top Navigation */}
      <header className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex h-16 items-center px-6 gap-6">
          <Link to="/app" className="flex items-center gap-2 font-bold text-xl mr-6">
            <img src="/logo.png" alt="Logo" className="w-6 h-6 rounded" />
            IVDR Portal
          </Link>
          
          <nav className="flex items-center gap-6 text-sm font-medium">
            <Link to="/app/workspaces" className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
              <LayoutDashboard className="w-4 h-4" /> Workspaces
            </Link>
            <Link to="/app/documents" className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
              <Folder className="w-4 h-4" /> Documents
            </Link>
            <Link to="/app/ai-chat" className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
              <MessageSquare className="w-4 h-4" /> AI Chat
            </Link>
            <Link to="/app/analytics" className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
              <BarChart className="w-4 h-4" /> Analytics
            </Link>
            <Link to="/app/logs" className="flex items-center gap-2 text-muted-foreground hover:text-foreground transition-colors">
              <Activity className="w-4 h-4" /> Audit Logs
            </Link>
          </nav>

          <div className="ml-auto flex items-center gap-4">
            <div className="flex flex-col items-end">
              <span className="text-sm font-medium text-foreground">
                {user?.fullName || 'User'}
              </span>
              <button 
                onClick={handleCopyId}
                className="text-[10px] text-muted-foreground hover:text-primary transition-colors flex items-center gap-1 cursor-pointer bg-accent/50 px-1.5 py-0.5 rounded border border-border mt-0.5"
                title="Click to copy User ID"
              >
                {copied ? (
                  <span className="text-emerald-500 font-semibold">Copied!</span>
                ) : (
                  <span>ID: {user?.userId ? `${user.userId.substring(0,8)}...` : 'Copy ID'}</span>
                )}
              </button>
            </div>
            <button 
              onClick={handleLogout}
              className="p-2 hover:bg-accent rounded-full text-muted-foreground hover:text-foreground transition-colors"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 overflow-auto p-6 md:p-10">
        <Outlet />
      </main>
    </div>
  );
}
