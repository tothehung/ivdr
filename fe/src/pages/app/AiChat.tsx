import { useState, useEffect, useRef } from 'react';
import { api } from '../../lib/api';
import { useWorkspaceStore } from '../../store/useWorkspaceStore';
import { Send, Sparkles, AlertCircle, FileText, Compass } from 'lucide-react';

interface Message {
  id: string;
  sender: 'user' | 'ai';
  text: string;
  timestamp: Date;
  recommendations?: string[];
}

export default function AiChat() {
  const { activeWorkspace } = useWorkspaceStore();
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      sender: 'ai',
      text: 'Hello! I am your AI Compliance Assistant. Ask me a regulatory question or query recommendations from your workspace documents.',
      timestamp: new Date()
    }
  ]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [docsList, setDocsList] = useState<string[]>([]);
  const chatEndRef = useRef<HTMLDivElement>(null);

  // Fetch document names in active workspace to pass to recommendations API
  useEffect(() => {
    const fetchDocNames = async () => {
      if (!activeWorkspace) return;
      try {
        const res = await api.get(`/workspaces/${activeWorkspace.id}/documents`);
        const data = res.data?.data || res.data;
        const content = Array.isArray(data.content) ? data.content : Array.isArray(data) ? data : [];
        const names = content.map((d: any) => d.name).filter(Boolean);
        setDocsList(names);
      } catch (err) {
        console.error('Failed to get document list for AI chat context', err);
      }
    };
    fetchDocNames();
  }, [activeWorkspace]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (!activeWorkspace) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh]">
        <h2 className="text-xl font-semibold">No Workspace Selected</h2>
        <p className="text-muted-foreground mt-2">Select a workspace to start the AI Compliance Chat.</p>
      </div>
    );
  }

  const handleSend = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;

    const userQuery = input.trim();
    setInput('');
    setIsLoading(true);

    const userMsg: Message = {
      id: Math.random().toString(),
      sender: 'user',
      text: userQuery,
      timestamp: new Date()
    };
    setMessages(prev => [...prev, userMsg]);

    try {
      // call recommendations API: GET /ai/recommendations?query={query}&documents={docName1},{docName2}
      const response = await api.get('/ai/recommendations', {
        params: {
          query: userQuery,
          documents: docsList.join(',')
        }
      });

      const recommendationText = response.data?.data || response.data?.message || response.data || 'No recommendations generated.';
      
      const aiMsg: Message = {
        id: Math.random().toString(),
        sender: 'ai',
        text: typeof recommendationText === 'string' ? recommendationText : JSON.stringify(recommendationText),
        timestamp: new Date()
      };

      setMessages(prev => [...prev, aiMsg]);
    } catch (err: any) {
      console.error('AI chat failed', err);
      setMessages(prev => [
        ...prev,
        {
          id: Math.random().toString(),
          sender: 'ai',
          text: `Compliance Assistant error: ${err.response?.data?.message || 'Failed to analyze request. Check your API configuration.'}`,
          timestamp: new Date()
        }
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  const setSuggestedQuery = (query: string) => {
    setInput(query);
  };

  return (
    <div className="max-w-4xl mx-auto flex flex-col h-[calc(100vh-8rem)]">
      <div className="flex items-center justify-between mb-4 shrink-0">
        <div>
          <h1 className="text-3xl font-bold tracking-tight flex items-center gap-2">
            AI Compliance Assistant <Sparkles className="w-5 h-5 text-primary animate-pulse" />
          </h1>
          <p className="text-muted-foreground text-sm mt-1">
            Running smart semantic recommendations across <span className="text-foreground font-medium">{docsList.length} documents</span> in <span className="text-foreground font-medium">{activeWorkspace.name}</span>.
          </p>
        </div>
      </div>

      {/* Main Chat Interface */}
      <div className="flex-1 min-h-0 bg-card border border-border rounded-xl flex flex-col overflow-hidden shadow-xl">
        {/* Messages body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          {messages.map(msg => (
            <div key={msg.id} className={`flex gap-3 max-w-[80%] ${msg.sender === 'user' ? 'ml-auto flex-row-reverse' : ''}`}>
              <div className={`w-8 h-8 rounded-lg shrink-0 flex items-center justify-center text-xs font-bold ${
                msg.sender === 'user' ? 'bg-primary text-primary-foreground' : 'bg-muted border border-border text-foreground'
              }`}>
                {msg.sender === 'user' ? 'U' : 'AI'}
              </div>
              <div className={`rounded-xl p-4 text-sm leading-relaxed ${
                msg.sender === 'user' 
                  ? 'bg-foreground text-background font-medium' 
                  : 'bg-muted/40 border border-border/60 text-foreground'
              }`}>
                <p className="whitespace-pre-wrap">{msg.text}</p>
                <span className="text-[10px] opacity-50 block mt-2 text-right">
                  {msg.timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
            </div>
          ))}
          {isLoading && (
            <div className="flex gap-3 max-w-[80%]">
              <div className="w-8 h-8 rounded-lg bg-muted border border-border flex items-center justify-center text-xs font-bold text-foreground">
                AI
              </div>
              <div className="bg-muted/40 border border-border/60 rounded-xl p-4 text-sm text-muted-foreground flex items-center gap-2">
                <span className="animate-spin">⏳</span> Analyzing compliance framework and workspace docs...
              </div>
            </div>
          )}
          <div ref={chatEndRef} />
        </div>

        {/* Suggestions & Input Bar */}
        <div className="p-4 border-t border-border bg-muted/20 flex flex-col gap-3">
          {messages.length === 1 && (
            <div className="flex flex-wrap gap-2">
              <button 
                onClick={() => setSuggestedQuery("Which document contains clinical trial evaluations?")}
                className="text-xs bg-card hover:bg-accent border border-border rounded-lg px-3 py-1.5 transition-colors text-muted-foreground hover:text-foreground flex items-center gap-1.5"
              >
                <Compass className="w-3.5 h-3.5" /> Clinical trials info?
              </button>
              <button 
                onClick={() => setSuggestedQuery("Check my regulations compliance documents for IVDR Article 5")}
                className="text-xs bg-card hover:bg-accent border border-border rounded-lg px-3 py-1.5 transition-colors text-muted-foreground hover:text-foreground flex items-center gap-1.5"
              >
                <AlertCircle className="w-3.5 h-3.5" /> IVDR Article 5?
              </button>
              <button 
                onClick={() => setSuggestedQuery("Recommend the latest approved document in this workspace")}
                className="text-xs bg-card hover:bg-accent border border-border rounded-lg px-3 py-1.5 transition-colors text-muted-foreground hover:text-foreground flex items-center gap-1.5"
              >
                <FileText className="w-3.5 h-3.5" /> Latest recommendations?
              </button>
            </div>
          )}

          <form onSubmit={handleSend} className="flex gap-2">
            <input 
              type="text"
              value={input}
              onChange={e => setInput(e.target.value)}
              placeholder="Ask compliance queries (e.g. Find documents relating to QSR)..."
              className="flex-1 bg-background border border-border rounded-xl px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
              disabled={isLoading}
            />
            <button 
              type="submit" 
              disabled={isLoading || !input.trim()}
              className="bg-foreground text-background hover:bg-foreground/90 disabled:opacity-50 disabled:hover:bg-foreground px-5 rounded-xl flex items-center justify-center transition-colors cursor-pointer"
            >
              <Send className="w-4 h-4" />
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
