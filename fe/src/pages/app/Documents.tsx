import { useState, useEffect } from 'react';
import { api } from '../../lib/api';
import { useWorkspaceStore } from '../../store/useWorkspaceStore';
import { useAuthStore } from '../../store/useAuthStore';
import { 
  Upload, File, FileText, Search, Users, Shield, UserPlus, X, Download, 
  Eye, Sparkles, Trash2, Copy, Check, Info, FileCode, ImageIcon 
} from 'lucide-react';

interface Document {
  id: string;
  workspaceId: string;
  name: string;
  description?: string;
  contentType: string;
  fileSizeBytes: number;
  version: number;
  status: string;
  tags?: string[];
  aiSummary?: string;
  uploadedBy: string;
  createdAt: string;
}

interface WorkspaceMember {
  userId: string;
  email: string;
  fullName: string;
  role: string;
}

interface PresenceDetail {
  userId: string;
  sessionId: string;
  documentId: string | null;
}

export default function Documents() {
  const { activeWorkspace, setActiveWorkspace } = useWorkspaceStore();
  const { user } = useAuthStore();
  
  const [documents, setDocuments] = useState<Document[]>([]);
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [presences, setPresences] = useState<PresenceDetail[]>([]);
  
  const [isLoadingDocs, setIsLoadingDocs] = useState(true);
  const [isLoadingMembers, setIsLoadingMembers] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');

  // Clipboard feedbacks
  const [copiedWsId, setCopiedWsId] = useState(false);
  const [copiedMemberId, setCopiedMemberId] = useState<string | null>(null);

  // Invite Modal
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);
  const [inviteUserId, setInviteUserId] = useState('');
  const [inviteRole, setInviteRole] = useState('VIEWER');

  // Upload Modal
  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadName, setUploadName] = useState('');
  const [uploadDescription, setUploadDescription] = useState('');
  const [uploadTags, setUploadTags] = useState('');
  const [isUploading, setIsUploading] = useState(false);

  // Preview Modal
  const [previewDoc, setPreviewDoc] = useState<Document | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [isPreviewLoading, setIsPreviewLoading] = useState(false);
  const [previewType, setPreviewType] = useState<'pdf' | 'image' | 'code' | 'fallback'>('fallback');
  const [codeContent, setCodeContent] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  
  // AI summary trigger state in preview
  const [isSummarizing, setIsSummarizing] = useState(false);

  const fetchDocuments = async () => {
    if (!activeWorkspace) return;
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/documents`);
      const data = res.data?.data || res.data;
      if (data && Array.isArray(data.content)) {
        setDocuments(data.content);
      } else if (Array.isArray(data)) {
        setDocuments(data);
      } else {
        setDocuments([]);
      }
    } catch (err) {
      console.error('Failed to fetch documents', err);
    } finally {
      setIsLoadingDocs(false);
    }
  };

  const fetchMembers = async () => {
    if (!activeWorkspace) return;
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/members`);
      setMembers(res.data?.data || res.data || []);
    } catch (err) {
      console.error('Failed to fetch members', err);
    } finally {
      setIsLoadingMembers(false);
    }
  };

  const fetchPresence = async () => {
    if (!activeWorkspace) return;
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/presence/details`);
      const details = res.data?.data || res.data || [];
      setPresences(details);
    } catch (err) {
      console.error('Failed to fetch presence details', err);
    }
  };

  useEffect(() => {
    fetchDocuments();
    fetchMembers();
    fetchPresence();

    // Poll presence every 10 seconds
    const interval = setInterval(fetchPresence, 10000);
    return () => clearInterval(interval);
  }, [activeWorkspace]);

  if (!activeWorkspace) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh]">
        <h2 className="text-xl font-semibold">No Workspace Selected</h2>
        <p className="text-muted-foreground mt-2">Please select a workspace from the Workspaces page.</p>
      </div>
    );
  }

  // --- Document Deletion ---
  const handleDeleteDocument = async (e: React.MouseEvent, docId: string) => {
    e.stopPropagation();
    if (!window.confirm("Are you sure you want to permanently delete this document?")) return;
    try {
      await api.delete(`/workspaces/${activeWorkspace.id}/documents/${docId}`);
      fetchDocuments();
    } catch (err: any) {
      console.error('Delete failed', err);
      alert(err.response?.data?.message || 'Failed to delete document.');
    }
  };

  // --- Workspace Deletion ---
  const handleDeleteWorkspace = async () => {
    if (!window.confirm("CRITICAL WARNING: This will permanently delete this workspace and all associated members/documents. Proceed?")) return;
    try {
      await api.delete(`/workspaces/${activeWorkspace.id}`);
      setActiveWorkspace(null as any);
      window.location.href = '/app/workspaces';
    } catch (err: any) {
      console.error('Workspace delete failed', err);
      alert(err.response?.data?.message || 'Failed to delete workspace.');
    }
  };

  // --- Member Removal ---
  const handleRemoveMember = async (userId: string, memberName: string) => {
    if (!window.confirm(`Are you sure you want to remove ${memberName} from this workspace?`)) return;
    try {
      await api.delete(`/workspaces/${activeWorkspace.id}/members/${userId}`);
      fetchMembers();
    } catch (err: any) {
      console.error('Member removal failed', err);
      alert(err.response?.data?.message || 'Failed to remove member.');
    }
  };

  // --- File Upload Form handlers ---
  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setSelectedFile(file);
    setUploadName(file.name);
    setUploadDescription('');
    setUploadTags('');
    setIsUploadModalOpen(true);
  };

  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFile) return;

    setIsUploading(true);
    const formData = new FormData();
    formData.append('file', selectedFile);
    formData.append('name', new Blob([uploadName], { type: 'text/plain' }));
    if (uploadDescription) {
      formData.append('description', new Blob([uploadDescription], { type: 'text/plain' }));
    }
    if (uploadTags.trim()) {
      uploadTags.split(',').forEach(tag => {
        if (tag.trim()) {
          formData.append('tags', new Blob([tag.trim()], { type: 'text/plain' }));
        }
      });
    }

    try {
      await api.post(`/workspaces/${activeWorkspace.id}/documents/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      });
      setIsUploadModalOpen(false);
      setSelectedFile(null);
      fetchDocuments();
    } catch (err: any) {
      console.error('Upload failed', err);
      alert(err.response?.data?.message || 'Failed to upload document.');
    } finally {
      setIsUploading(false);
    }
  };

  // --- Member Invitation ---
  const handleInviteMember = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post(`/workspaces/${activeWorkspace.id}/members`, { userId: inviteUserId, role: inviteRole });
      setIsInviteModalOpen(false);
      setInviteUserId('');
      fetchMembers();
    } catch (err: any) {
      console.error('Failed to invite member', err);
      alert(err.response?.data?.message || 'Failed to invite member. Check UUID and permissions.');
    }
  };

  // --- Document Preview & Content Type Resolving ---
  const getFileDisplayType = (name: string, contentType: string) => {
    if (name && name.includes('.')) {
      return name.split('.').pop()?.toUpperCase() || 'RAW';
    }
    if (contentType) {
      if (contentType === 'application/pdf') return 'PDF';
      if (contentType.startsWith('image/')) return contentType.split('/')[1].toUpperCase();
      if (contentType.startsWith('text/')) return contentType.split('/')[1].toUpperCase();
    }
    return 'RAW';
  };

  const isCodeOrText = (name: string, contentType: string) => {
    const ext = name?.split('.').pop()?.toLowerCase();
    const codeExts = ['js', 'jsx', 'ts', 'tsx', 'html', 'css', 'json', 'txt', 'py', 'java', 'xml', 'md', 'sql', 'sh', 'yml', 'yaml', 'c', 'cpp', 'rs', 'go'];
    if (ext && codeExts.includes(ext)) return true;
    if (contentType && (contentType.startsWith('text/') || contentType === 'application/json')) return true;
    return false;
  };

  const isImage = (name: string, contentType: string) => {
    const ext = name?.split('.').pop()?.toLowerCase();
    const imgExts = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico'];
    if (ext && imgExts.includes(ext)) return true;
    if (contentType && contentType.startsWith('image/')) return true;
    return false;
  };

  const isPdf = (name: string, contentType: string) => {
    const ext = name?.split('.').pop()?.toLowerCase();
    return ext === 'pdf' || contentType === 'application/pdf';
  };

  const handlePreviewDocument = async (doc: Document) => {
    setPreviewDoc(doc);
    setIsPreviewLoading(true);
    setPreviewUrl(null);
    setCodeContent(null);
    setPreviewError(null);

    const name = doc.name || '';
    const contentType = doc.contentType || '';
    let type: 'pdf' | 'image' | 'code' | 'fallback' = 'fallback';

    if (isPdf(name, contentType)) {
      type = 'pdf';
    } else if (isImage(name, contentType)) {
      type = 'image';
    } else if (isCodeOrText(name, contentType)) {
      type = 'code';
    }
    setPreviewType(type);

    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/documents/${doc.id}/preview-url`);
      const url = res.data?.presignedUrl || res.data?.data?.presignedUrl || res.data?.url;
      if (!url) throw new Error('No preview URL returned');

      setPreviewUrl(url);

      if (type === 'code') {
        try {
          const textRes = await fetch(url);
          if (!textRes.ok) throw new Error('CORS or connection failed fetching code');
          const text = await textRes.text();
          setCodeContent(text);
        } catch (fetchErr) {
          console.warn('Text fetch failed, falling back to download details', fetchErr);
          setPreviewError('Cannot load visual text preview. Download the file to view its full code.');
          setPreviewType('fallback');
        }
      }
    } catch (err) {
      console.error('Failed to get preview URL', err);
      setPreviewError('Unable to generate secure preview URL.');
    } finally {
      setIsPreviewLoading(false);
    }
  };

  // --- Document Download ---
  const handleDownloadDocument = async (e: React.MouseEvent | null, docId: string) => {
    if (e) e.stopPropagation();
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/documents/${docId}/download-url`);
      const url = res.data?.presignedUrl || res.data?.data?.presignedUrl || res.data?.url;
      if (url) window.open(url, '_blank');
    } catch (err) {
      console.error('Failed to get download URL', err);
      alert('Failed to retrieve download link.');
    }
  };

  // --- AI Summarization Trigger ---
  const handleTriggerSummary = async () => {
    if (!previewDoc) return;
    setIsSummarizing(true);
    try {
      const res = await api.post(`/ai/summarize/${previewDoc.id}`, {});
      const summary = res.data?.data || res.data;
      setPreviewDoc(prev => prev ? { ...prev, aiSummary: summary } : null);
      // Refresh listing
      fetchDocuments();
    } catch (err) {
      console.error('AI summary generation failed', err);
      alert('AI Summarization failed. Verify that your AI provider key is set.');
    } finally {
      setIsSummarizing(false);
    }
  };

  // --- Helper: check if member is online ---
  const getMemberPresence = (userId: string) => {
    // Current user is always online
    const currentUserId = user?.userId || user?.id;
    if (userId === currentUserId) return { online: true, viewingDocId: previewDoc?.id || null };
    
    const detail = presences.find(p => p.userId === userId);
    if (detail) {
      return { online: true, viewingDocId: detail.documentId };
    }
    return { online: false, viewingDocId: null };
  };

  const copyToClipboard = (text: string, type: 'ws' | 'member', memberId?: string) => {
    navigator.clipboard.writeText(text);
    if (type === 'ws') {
      setCopiedWsId(true);
      setTimeout(() => setCopiedWsId(false), 2000);
    } else if (type === 'member' && memberId) {
      setCopiedMemberId(memberId);
      setTimeout(() => setCopiedMemberId(null), 2000);
    }
  };

  const filteredDocs = documents.filter(d => d.name?.toLowerCase().includes(searchQuery.toLowerCase()));

  // Active workspace role checks
  const workspaceRole = activeWorkspace.role || 'MEMBER';
  const isOwner = workspaceRole === 'OWNER';
  const isEditor = workspaceRole === 'EDITOR' || isOwner;

  return (
    <div className="max-w-7xl mx-auto flex flex-col lg:flex-row gap-6 h-full relative">
      
      {/* Upload File Metadata Modal */}
      {isUploadModalOpen && selectedFile && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold">Document Metadata Details</h3>
              <button onClick={() => { setIsUploadModalOpen(false); setSelectedFile(null); }} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <form onSubmit={handleUploadSubmit} className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Document Name *</label>
                <input 
                  type="text" 
                  value={uploadName}
                  onChange={e => setUploadName(e.target.value)}
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Description</label>
                <textarea 
                  value={uploadDescription}
                  onChange={e => setUploadDescription(e.target.value)}
                  placeholder="Compliance context, notes..."
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary h-20 resize-none"
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Tags (comma-separated)</label>
                <input 
                  type="text" 
                  value={uploadTags}
                  onChange={e => setUploadTags(e.target.value)}
                  placeholder="compliance, clinical, annex1"
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => { setIsUploadModalOpen(false); setSelectedFile(null); }} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
                  Cancel
                </button>
                <button type="submit" disabled={isUploading} className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity">
                  {isUploading ? 'Uploading...' : 'Confirm Upload'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Invite Modal */}
      {isInviteModalOpen && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold">Invite Member</h3>
              <button onClick={() => setIsInviteModalOpen(false)} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <form onSubmit={handleInviteMember} className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">User ID (UUID)</label>
                <input 
                  type="text" 
                  value={inviteUserId}
                  onChange={e => setInviteUserId(e.target.value)}
                  placeholder="Paste User ID here..."
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Role</label>
                <select 
                  value={inviteRole}
                  onChange={e => setInviteRole(e.target.value)}
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                >
                  <option value="VIEWER">Viewer (Read-only)</option>
                  <option value="EDITOR">Editor (Upload & Edit)</option>
                  <option value="OWNER">Owner (Full admin)</option>
                </select>
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => setIsInviteModalOpen(false)} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
                  Cancel
                </button>
                <button type="submit" className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity">
                  Send Invite
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Preview Modal */}
      {previewDoc && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-5xl h-[85vh] flex flex-col overflow-hidden">
            
            {/* Header info */}
            <div className="flex justify-between items-center p-4 border-b border-border bg-muted/30">
              <div className="flex items-center gap-3">
                <FileText className="w-5 h-5 text-muted-foreground" />
                <div>
                  <h3 className="font-bold flex items-center gap-2">{previewDoc.name}</h3>
                  <p className="text-xs text-muted-foreground">Type: {getFileDisplayType(previewDoc.name, previewDoc.contentType)}</p>
                </div>
              </div>
              <div className="flex gap-2">
                <button 
                  onClick={() => handleDownloadDocument(null, previewDoc.id)}
                  className="flex items-center gap-2 px-3 py-1.5 rounded bg-primary/10 text-primary hover:bg-primary/20 transition-colors text-sm font-medium cursor-pointer"
                >
                  <Download className="w-4 h-4" /> Download
                </button>
                <button onClick={() => setPreviewDoc(null)} className="p-1.5 text-muted-foreground hover:text-foreground rounded hover:bg-accent cursor-pointer">
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>

            {/* Split layout: Preview & AI summary */}
            <div className="flex-1 flex flex-col lg:flex-row overflow-hidden">
              
              {/* Visual preview frame */}
              <div className="flex-1 bg-zinc-950 flex items-center justify-center relative border-r border-border/80 min-h-[50%] lg:min-h-0">
                {isPreviewLoading ? (
                  <div className="text-muted-foreground flex flex-col items-center">
                    <span className="animate-spin text-2xl mb-2">⏳</span>
                    <p>Generating secure preview link...</p>
                  </div>
                ) : previewError ? (
                  <div className="text-center p-6 space-y-4">
                    <Info className="w-10 h-10 mx-auto text-amber-500 opacity-60" />
                    <p className="text-muted-foreground text-sm">{previewError}</p>
                    <button 
                      onClick={() => handleDownloadDocument(null, previewDoc.id)}
                      className="px-4 py-2 bg-primary/15 text-primary text-xs font-semibold rounded hover:bg-primary/25 cursor-pointer"
                    >
                      Download File to View
                    </button>
                  </div>
                ) : previewType === 'pdf' && previewUrl ? (
                  <iframe src={previewUrl} className="w-full h-full border-none" title="Document Preview" />
                ) : previewType === 'image' && previewUrl ? (
                  <img src={previewUrl} className="max-w-full max-h-full object-contain p-4" alt="Preview" />
                ) : previewType === 'code' && codeContent ? (
                  <pre className="w-full h-full p-6 text-xs text-zinc-300 font-mono overflow-auto whitespace-pre select-text text-left bg-zinc-950">
                    <code>{codeContent}</code>
                  </pre>
                ) : (
                  <div className="text-center p-6 space-y-4">
                    <Info className="w-10 h-10 mx-auto text-muted-foreground opacity-50" />
                    <p className="text-muted-foreground text-sm">Direct visual preview is not supported for this file format.</p>
                    <button 
                      onClick={() => handleDownloadDocument(null, previewDoc.id)}
                      className="px-4 py-2 bg-primary text-primary-foreground text-xs font-semibold rounded hover:opacity-90 cursor-pointer"
                    >
                      Download File to View
                    </button>
                  </div>
                )}
              </div>

              {/* Side panel: Metadata & AI Summarization */}
              <div className="w-full lg:w-80 p-6 flex flex-col overflow-y-auto bg-card shrink-0 gap-6">
                <div>
                  <h4 className="font-semibold text-sm uppercase tracking-wider text-muted-foreground mb-2">Metadata Context</h4>
                  <div className="space-y-2 text-xs">
                    <p><strong className="text-foreground">Size:</strong> {(previewDoc.fileSizeBytes / 1024).toFixed(1)} KB</p>
                    <p><strong className="text-foreground">Uploaded At:</strong> {new Date(previewDoc.createdAt).toLocaleDateString()}</p>
                    <p><strong className="text-foreground">Revision:</strong> v{previewDoc.version}</p>
                    {previewDoc.description && <p className="mt-2"><strong className="text-foreground">Notes:</strong> {previewDoc.description}</p>}
                    {previewDoc.tags && previewDoc.tags.length > 0 && (
                      <div className="flex flex-wrap gap-1 mt-2">
                        {previewDoc.tags.map((t, i) => (
                          <span key={i} className="bg-accent px-2 py-0.5 rounded text-[10px] text-muted-foreground">{t}</span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>

                <hr className="border-border" />

                <div className="flex-1 flex flex-col">
                  <div className="flex items-center justify-between mb-3">
                    <h4 className="font-semibold text-sm uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                      <Sparkles className="w-4 h-4 text-primary animate-pulse" /> AI Compliance Summary
                    </h4>
                    <button 
                      onClick={handleTriggerSummary}
                      disabled={isSummarizing}
                      className="text-xs bg-primary/10 hover:bg-primary/20 text-primary font-semibold px-2 py-1 rounded transition-colors cursor-pointer disabled:opacity-50"
                    >
                      {isSummarizing ? 'Analyzing...' : previewDoc.aiSummary ? 'Regenerate' : 'Generate'}
                    </button>
                  </div>

                  {isSummarizing ? (
                    <div className="flex-1 flex items-center justify-center p-6 border border-dashed border-border rounded-lg text-center bg-muted/10">
                      <div className="text-xs text-muted-foreground space-y-2">
                        <span className="animate-spin inline-block">⏳</span>
                        <p>Extracting text structure and running compliance validations...</p>
                      </div>
                    </div>
                  ) : previewDoc.aiSummary ? (
                    <div className="flex-1 bg-muted/30 border border-border/80 rounded-lg p-4 text-xs leading-relaxed text-muted-foreground font-mono whitespace-pre-wrap overflow-y-auto max-h-[30vh] lg:max-h-none">
                      {previewDoc.aiSummary}
                    </div>
                  ) : (
                    <div className="flex-1 flex flex-col items-center justify-center p-6 border border-dashed border-border rounded-lg text-center bg-muted/10 text-muted-foreground">
                      <Sparkles className="w-8 h-8 opacity-40 mb-2" />
                      <p className="text-xs">No AI summary generated yet.</p>
                      <button 
                        onClick={handleTriggerSummary}
                        className="mt-3 px-3 py-1.5 bg-primary text-primary-foreground text-xs font-semibold rounded hover:opacity-90 cursor-pointer"
                      >
                        Generate AI Summary
                      </button>
                    </div>
                  )}
                </div>

              </div>

            </div>

          </div>
        </div>
      )}

      {/* Left Panel: Documents Table */}
      <div className="flex-1 flex flex-col min-w-0">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-8">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Documents</h1>
            <p className="text-muted-foreground mt-1 flex items-center gap-1.5">
              Workspace: <span className="text-foreground font-medium">{activeWorkspace.name}</span>
            </p>
          </div>
          {isEditor && (
            <label className="flex items-center gap-2 bg-foreground text-background px-4 py-2 rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity cursor-pointer shadow-xl shadow-white/5 shrink-0 justify-center">
              <Upload className="w-4 h-4" /> Upload File
              <input type="file" className="hidden" onChange={handleFileSelect} />
            </label>
          )}
        </div>

        <div className="flex items-center gap-3 px-4 py-2.5 mb-6 bg-card border border-border rounded-lg max-w-md">
          <Search className="w-5 h-5 text-muted-foreground" />
          <input 
            type="text" 
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search documents by name..."
            className="bg-transparent border-none focus:outline-none text-sm w-full text-foreground"
          />
        </div>

        <div className="flex-1 bg-card border border-border rounded-xl overflow-hidden flex flex-col shadow-sm">
          <table className="w-full text-left text-sm">
            <thead className="bg-background border-b border-border text-muted-foreground">
              <tr>
                <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">Name</th>
                <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">Type</th>
                <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider">Uploaded By</th>
                <th className="px-6 py-4 font-medium text-xs uppercase tracking-wider text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {isLoadingDocs ? (
                <tr>
                  <td colSpan={4} className="px-6 py-12 text-center text-muted-foreground">Loading documents...</td>
                </tr>
              ) : filteredDocs.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-6 py-20 text-center">
                    <File className="w-10 h-10 mx-auto text-muted-foreground mb-4 opacity-50" />
                    <p className="text-muted-foreground">No documents found in this workspace.</p>
                  </td>
                </tr>
              ) : (
                filteredDocs.map(doc => {
                  const dispType = getFileDisplayType(doc.name, doc.contentType);
                  return (
                    <tr 
                      key={doc.id} 
                      onClick={() => handlePreviewDocument(doc)}
                      className="hover:bg-accent/40 transition-colors cursor-pointer group"
                    >
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-3 font-medium text-foreground">
                          {dispType === 'PDF' ? <FileText className="w-5 h-5 text-red-400" /> :
                           ['PNG','JPG','JPEG','SVG','WEBP'].includes(dispType) ? <ImageIcon className="w-5 h-5 text-emerald-400" /> :
                           ['JS','JSX','TS','TSX','HTML','CSS','JSON','PY','JAVA'].includes(dispType) ? <FileCode className="w-5 h-5 text-blue-400" /> :
                           <File className="w-5 h-5 text-muted-foreground" />}
                          <span className="truncate max-w-[200px] sm:max-w-xs">{doc.name || 'Untitled Document'}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-muted-foreground text-xs font-semibold">{dispType}</td>
                      <td className="px-6 py-4 text-xs font-mono text-muted-foreground">{doc.uploadedBy ? `${doc.uploadedBy.substring(0,8)}...` : 'Unknown'}</td>
                      <td className="px-6 py-4 text-right" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center justify-end gap-2">
                          <button 
                            onClick={() => handlePreviewDocument(doc)}
                            className="p-1.5 hover:bg-background rounded text-muted-foreground hover:text-foreground cursor-pointer" title="Preview"
                          >
                            <Eye className="w-4 h-4" />
                          </button>
                          <button 
                            onClick={(e) => handleDownloadDocument(e, doc.id)}
                            className="p-1.5 hover:bg-background rounded text-muted-foreground hover:text-foreground cursor-pointer" title="Download"
                          >
                            <Download className="w-4 h-4" />
                          </button>
                          {isEditor && (
                            <button 
                              onClick={(e) => handleDeleteDocument(e, doc.id)}
                              className="p-1.5 hover:bg-destructive/15 rounded text-muted-foreground hover:text-destructive transition-colors cursor-pointer" title="Delete"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Right Panel: Workspace Details & Members */}
      <div className="w-full lg:w-80 flex flex-col gap-6 shrink-0">
        
        {/* Workspace Info Card */}
        <div className="bg-card border border-border rounded-xl p-6 shadow-sm relative overflow-hidden">
          <div className="flex items-center gap-3 mb-2">
            <Shield className="w-5 h-5 text-foreground" />
            <h3 className="font-semibold text-lg truncate pr-6">{activeWorkspace.name}</h3>
          </div>
          <p className="text-xs text-muted-foreground leading-relaxed">
            {activeWorkspace.description || 'No description provided.'}
          </p>
          
          <div className="mt-4 flex items-center justify-between bg-background/50 border border-border rounded-lg px-3 py-1.5">
            <span className="text-[10px] font-mono text-muted-foreground truncate mr-2">ID: {activeWorkspace.id}</span>
            <button 
              onClick={() => copyToClipboard(activeWorkspace.id, 'ws')}
              className="p-1 hover:bg-accent rounded text-muted-foreground hover:text-foreground transition-colors cursor-pointer shrink-0"
              title="Copy Workspace UUID"
            >
              {copiedWsId ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          </div>

          {isOwner && (
            <button 
              onClick={handleDeleteWorkspace}
              className="w-full mt-4 flex items-center justify-center gap-2 border border-destructive/20 bg-destructive/5 hover:bg-destructive/15 text-destructive rounded-lg py-2 text-xs font-semibold transition-colors cursor-pointer"
            >
              <Trash2 className="w-3.5 h-3.5" /> Delete Workspace
            </button>
          )}
        </div>

        {/* Members Panel */}
        <div className="bg-card border border-border rounded-xl p-6 flex-1 flex flex-col min-h-[300px] shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Users className="w-4 h-4 text-foreground" />
              <h3 className="font-semibold">Members</h3>
            </div>
            <div className="flex items-center gap-2">
              <span className="bg-accent px-2 py-0.5 rounded text-xs font-medium text-muted-foreground">
                {members.length}
              </span>
              {isOwner && (
                <button 
                  onClick={() => setIsInviteModalOpen(true)}
                  className="p-1 hover:bg-accent rounded text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
                  title="Invite Member"
                >
                  <UserPlus className="w-4 h-4" />
                </button>
              )}
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto space-y-4 pr-1">
            {isLoadingMembers ? (
              <p className="text-xs text-muted-foreground">Loading members...</p>
            ) : members.length === 0 ? (
              <p className="text-xs text-muted-foreground">No members found.</p>
            ) : (
              members.map(member => {
                const presence = getMemberPresence(member.userId);
                return (
                  <div key={member.userId} className="flex items-center gap-3 text-sm">
                    {/* Status dot avatar */}
                    <div className="relative shrink-0">
                      <div className="w-8 h-8 rounded-full bg-accent flex items-center justify-center text-xs font-semibold text-foreground">
                        {member.fullName?.charAt(0) || 'U'}
                      </div>
                      {presence.online && (
                        <span className="absolute bottom-0 right-0 block h-2.5 w-2.5 rounded-full bg-emerald-500 ring-2 ring-card" title="Online" />
                      )}
                    </div>

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-1.5">
                        <p className="font-medium text-foreground truncate">{member.fullName}</p>
                        <span className={`text-[8px] font-bold px-1 rounded border ${
                          member.role === 'OWNER' ? 'bg-primary/10 border-primary/20 text-primary' :
                          member.role === 'EDITOR' ? 'bg-blue-500/10 border-blue-500/20 text-blue-400' :
                          'bg-zinc-500/10 border-zinc-500/20 text-zinc-400'
                        }`}>
                          {member.role}
                        </span>
                      </div>
                      <p className="text-[10px] text-muted-foreground truncate">{member.email}</p>
                      
                      {/* Active status info */}
                      {presence.online && presence.viewingDocId && (
                        <p className="text-[9px] text-emerald-400 truncate mt-0.5 animate-pulse">
                          👁 Viewing doc: {documents.find(d => d.id === presence.viewingDocId)?.name || 'Viewing document'}
                        </p>
                      )}
                    </div>

                    {/* Copy UUID / Delete Actions */}
                    <div className="flex items-center gap-1.5">
                      <button 
                        onClick={() => copyToClipboard(member.userId, 'member', member.userId)}
                        className="p-1 hover:bg-accent rounded text-muted-foreground hover:text-foreground transition-colors cursor-pointer shrink-0"
                        title="Copy User UUID"
                      >
                        {copiedMemberId === member.userId ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                      </button>
                      {isOwner && member.userId !== (user?.userId || user?.id) && (
                        <button 
                          onClick={() => handleRemoveMember(member.userId, member.fullName)}
                          className="p-1 hover:bg-destructive/15 rounded text-muted-foreground hover:text-destructive transition-colors cursor-pointer shrink-0"
                          title="Remove from Workspace"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
