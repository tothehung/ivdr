import { useState, useEffect, useRef } from 'react';
import { api } from '../../lib/api';
import { copyTextToClipboard } from '../../lib/utils';
import { useWorkspaceStore } from '../../store/useWorkspaceStore';
import { useAuthStore } from '../../store/useAuthStore';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { 
  Upload, File, FileText, Search, Users, Shield, UserPlus, X, Download, 
  Eye, Sparkles, Trash2, Copy, Check, Info, FileCode, ImageIcon,
  Folder, FolderPlus, Link2, Lock, Unlock, MessageSquare, Plus, Send
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
  isPasswordProtected?: boolean;
  folderId?: string | null;
  fileKey: string;
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
  status: 'online' | 'offline';
  activity: string;
  activityDetail: string;
  lastActiveAt: number;
}

export default function Documents() {
  const { activeWorkspace, setActiveWorkspace } = useWorkspaceStore();
  const { user } = useAuthStore();

  // Refs for tracking chat drawer state in WebSockets without stale closures
  const isChatOpenRef = useRef(false);
  const chatModeRef = useRef<'group' | 'direct'>('group');
  const chatRecipientIdRef = useRef<string | null>(null);
  
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
  const [uploadPassword, setUploadPassword] = useState('');
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

  // Edit Metadata states
  const [isEditingMetadata, setIsEditingMetadata] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editTags, setEditTags] = useState('');
  const [isSavingMetadata, setIsSavingMetadata] = useState(false);

  // Folder states
  const [currentFolderId, setCurrentFolderId] = useState<string | null>(null);
  const [folderPath, setFolderPath] = useState<{ id: string; name: string }[]>([]);
  const [folders, setFolders] = useState<any[]>([]);
  const [isCreateFolderOpen, setIsCreateFolderOpen] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');
  const [isCreatingFolder, setIsCreatingFolder] = useState(false);

  // Upload Link states
  const [isLinkModalOpen, setIsLinkModalOpen] = useState(false);
  const [linkName, setLinkName] = useState('');
  const [linkUrl, setLinkUrl] = useState('');
  const [linkDescription, setLinkDescription] = useState('');
  const [linkTags, setLinkTags] = useState('');
  const [isSavingLink, setIsSavingLink] = useState(false);

  // Password Protection states
  const [isPasswordModalOpen, setIsPasswordModalOpen] = useState(false);
  const [passwordDoc, setPasswordDoc] = useState<Document | null>(null);
  const [docPassword, setDocPassword] = useState('');
  const [passwordAction, setPasswordAction] = useState<'preview' | 'download'>('preview');
  const [isVerifyingPassword, setIsVerifyingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [newDocPassword, setNewDocPassword] = useState('');

  // Inline Content Editor states
  const [isEditingContent, setIsEditingContent] = useState(false);
  const [editingText, setEditingText] = useState('');
  const [isSavingContent, setIsSavingContent] = useState(false);

  // Chat states
  const [isChatOpen, setIsChatOpen] = useState(false);
  const [chatMessages, setChatMessages] = useState<any[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatMode, setChatMode] = useState<'group' | 'direct'>('group');
  const [chatRecipientId, setChatRecipientId] = useState<string | null>(null);
  const [stompClient, setStompClient] = useState<any>(null);
  const [isChatConnected, setIsChatConnected] = useState(false);

  // Chat unread states
  const [unreadGroup, setUnreadGroup] = useState(false);
  const [unreadDirect, setUnreadDirect] = useState(false);
  const [unreadSenders, setUnreadSenders] = useState<Record<string, boolean>>({});

  // Sync refs with states to prevent WebSocket stale closures
  useEffect(() => {
    isChatOpenRef.current = isChatOpen;
  }, [isChatOpen]);

  useEffect(() => {
    chatModeRef.current = chatMode;
  }, [chatMode]);

  useEffect(() => {
    chatRecipientIdRef.current = chatRecipientId;
  }, [chatRecipientId]);

  // Ref to chat message list for scroll anchoring
  const chatEndRef = useRef<HTMLDivElement>(null);

  const startEditingMetadata = () => {
    if (!previewDoc) return;
    setEditName(previewDoc.name || '');
    setEditDescription(previewDoc.description || '');
    setEditTags(previewDoc.tags ? previewDoc.tags.join(', ') : '');
    setNewDocPassword('');
    setIsEditingMetadata(true);
  };

  const handleSaveMetadata = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeWorkspace || !previewDoc || !editName.trim()) return;
    setIsSavingMetadata(true);
    try {
      const tagsArray = editTags.split(',').map(t => t.trim()).filter(Boolean);
      const res = await api.put(`/workspaces/${activeWorkspace.id}/documents/${previewDoc.id}`, {
        name: editName.trim(),
        description: editDescription.trim(),
        tags: tagsArray
      });
      let updatedDoc = res.data?.data || res.data;

      if (isOwner) {
        const passRes = await api.post(`/workspaces/${activeWorkspace.id}/documents/${previewDoc.id}/password`, {
          password: newDocPassword
        });
        const passDoc = passRes.data?.data || passRes.data;
        if (passDoc) {
          updatedDoc = { ...updatedDoc, isPasswordProtected: passDoc.isPasswordProtected };
        }
      }

      setPreviewDoc(updatedDoc);
      setIsEditingMetadata(false);
      fetchDocuments(currentFolderId);
    } catch (err: any) {
      console.error('Failed to update metadata', err);
      alert(err.response?.data?.message || 'Failed to update metadata.');
    } finally {
      setIsSavingMetadata(false);
    }
  };

  const fetchDocuments = async (folderId: string | null = currentFolderId) => {
    if (!activeWorkspace) return;
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/documents`, {
        params: { folderId: folderId || undefined }
      });
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

  const fetchFolders = async (parentId: string | null = currentFolderId) => {
    if (!activeWorkspace) return;
    try {
      const res = await api.get(`/workspaces/${activeWorkspace.id}/folders`, {
        params: { parentId: parentId || undefined }
      });
      const data = res.data?.data || res.data;
      if (Array.isArray(data)) {
        setFolders(data);
      } else {
        setFolders([]);
      }
    } catch (err) {
      console.error('Failed to fetch folders', err);
      setFolders([]);
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
      // Normalize lastActiveAt to epoch millis (backend sends Long millis)
      const normalized = details.map((d: any) => ({
        ...d,
        lastActiveAt: d.lastActiveAt
          ? (d.lastActiveAt < 1e12 ? d.lastActiveAt * 1000 : d.lastActiveAt)
          : 0
      }));
      setPresences(normalized);
    } catch (err) {
      console.error('Failed to fetch presence details', err);
    }
  };

  const fetchChatHistory = async (mode: 'group' | 'direct', recipientId: string | null) => {
    if (!activeWorkspace) return;
    try {
      if (mode === 'group') {
        const res = await api.get(`/workspaces/${activeWorkspace.id}/chat/history`);
        const messages = res.data?.data || res.data || [];
        setChatMessages(messages);
        
        // Save latest group message details to localStorage to track read status
        if (messages.length > 0) {
          const latestMsg = messages[messages.length - 1];
          localStorage.setItem(`lastReadGroupMsg_${activeWorkspace.id}`, latestMsg.createdAt || latestMsg.id || '');
        }
      } else if (recipientId) {
        const res = await api.get(`/workspaces/${activeWorkspace.id}/chat/direct/${recipientId}`);
        setChatMessages(res.data?.data || res.data || []);
      }
    } catch (err) {
      console.error('Failed to fetch chat history', err);
    }
  };

  useEffect(() => {
    if (!activeWorkspace) return;
    setIsLoadingDocs(true);
    fetchDocuments(currentFolderId);
    fetchFolders(currentFolderId);
    fetchMembers();
    fetchPresence();

    // Fetch initial unread DM senders
    const loadUnreadSenders = async () => {
      try {
        const res = await api.get(`/workspaces/${activeWorkspace.id}/chat/unread`);
        const senderIds: string[] = res.data || [];
        const unreadMap: Record<string, boolean> = {};
        senderIds.forEach(id => {
          unreadMap[id] = true;
        });
        setUnreadSenders(unreadMap);
        setUnreadDirect(senderIds.length > 0);
      } catch (err) {
        console.error('Failed to fetch unread DM senders', err);
      }
    };
    loadUnreadSenders();

    // Check if group chat has unread messages based on localStorage
    const checkGroupUnread = async () => {
      try {
        const res = await api.get(`/workspaces/${activeWorkspace.id}/chat/history`);
        const messages = res.data?.data || res.data || [];
        if (messages.length > 0) {
          const latestMsg = messages[messages.length - 1];
          const lastRead = localStorage.getItem(`lastReadGroupMsg_${activeWorkspace.id}`);
          if (!lastRead || lastRead !== (latestMsg.createdAt || latestMsg.id)) {
            setUnreadGroup(true);
          }
        }
      } catch (err) {
        console.error('Failed to check unread group chat status', err);
      }
    };
    checkGroupUnread();

    // Poll presence every 10 seconds
    const interval = setInterval(fetchPresence, 10000);
    return () => clearInterval(interval);
  }, [activeWorkspace, currentFolderId]);

  // STOMP WebSocket Connection setup
  useEffect(() => {
    if (!activeWorkspace) return;

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      connectHeaders: {
        Authorization: `Bearer ${useAuthStore.getState().token}`,
      },
      debug: (str) => {
        console.log('[STOMP Debug]', str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    let heartbeatInterval: any = null;

    client.onConnect = (frame) => {
      console.log('[STOMP Connected]', frame);
      setIsChatConnected(true);

      // Subscribe to group chat channel
      client.subscribe(`/topic/chat/${activeWorkspace.id}`, (message) => {
        const body = JSON.parse(message.body);
        setChatMessages((prev) => {
          if (prev.some(m => m.id === body.id)) return prev;
          return [...prev, body];
        });

        // Trigger unread indicator if not currently viewing group chat
        if (!isChatOpenRef.current || chatModeRef.current !== 'group') {
          setUnreadGroup(true);
        } else {
          localStorage.setItem(`lastReadGroupMsg_${activeWorkspace.id}`, body.createdAt || body.id || '');
        }
      });

      // Subscribe to direct message topic (broadcast-based, filtered client-side)
      client.subscribe(`/topic/chat.dm/${activeWorkspace.id}`, (message) => {
        const body = JSON.parse(message.body);
        setChatMessages((prev) => {
          if (prev.some(m => m.id === body.id)) return prev;
          return [...prev, body];
        });

        const currentUserId = useAuthStore.getState().user?.userId || useAuthStore.getState().user?.id;
        const isFromMe = body.senderId === currentUserId;

        if (!isFromMe) {
          if (isChatOpenRef.current && chatModeRef.current === 'direct' && chatRecipientIdRef.current === body.senderId) {
            // Auto-read via API
            api.post(`/workspaces/${activeWorkspace.id}/chat/direct/${body.senderId}/read`).catch(console.error);
          } else {
            // Mark as unread
            setUnreadDirect(true);
            setUnreadSenders((prev) => ({ ...prev, [body.senderId]: true }));
          }
        }
      });

      // Subscribe to DM read receipts
      client.subscribe(`/topic/chat.read/${activeWorkspace.id}`, (message) => {
        const receipt = JSON.parse(message.body);
        const currentUserId = useAuthStore.getState().user?.userId || useAuthStore.getState().user?.id;
        if (receipt.senderId === currentUserId && receipt.readerId === chatRecipientIdRef.current) {
          setChatMessages((prev) =>
            prev.map((msg) =>
              msg.senderId === currentUserId ? { ...msg, isRead: true } : msg
            )
          );
        }
      });

      // Subscribe to presence updates
      client.subscribe(`/topic/presence/${activeWorkspace.id}`, (message) => {
        const event = JSON.parse(message.body);
        setPresences((prev) => {
          const index = prev.findIndex(p => p.userId === event.userId);
          
          if (event.type === 'LEAVE') {
            const updated = [...prev];
            if (index !== -1) {
              updated[index] = {
                ...updated[index],
                status: 'offline',
                activity: 'offline',
                activityDetail: '',
                lastActiveAt: Date.now()
              };
            } else {
              updated.push({
                userId: event.userId,
                sessionId: event.sessionId,
                status: 'offline',
                activity: 'offline',
                activityDetail: '',
                lastActiveAt: Date.now()
              });
            }
            return updated;
          }
          
          // Backend now sends timestamp as epoch millis (Long), use directly
          const tsRaw = event.timestamp;
          const tsMillis = (typeof tsRaw === 'number' && tsRaw > 0)
            ? (tsRaw < 1e12 ? tsRaw * 1000 : tsRaw) // handle both seconds and millis just in case
            : Date.now();

          const newPresence = {
            userId: event.userId,
            sessionId: event.sessionId,
            status: 'online' as const,
            activity: event.activity || 'idle',
            activityDetail: event.activityDetail || '',
            lastActiveAt: tsMillis
          };
          
          const updated = [...prev];
          if (index !== -1) {
            updated[index] = newPresence;
          } else {
            updated.push(newPresence);
          }
          return updated;
        });
      });

      // Send join signal
      client.publish({ destination: `/app/workspace/${activeWorkspace.id}/join` });

      // Send a heartbeat every 15 seconds
      heartbeatInterval = setInterval(() => {
        if (client.connected) {
          client.publish({ destination: `/app/workspace/${activeWorkspace.id}/heartbeat` });
        }
      }, 15000);

      // Load chat history after connection is established
      fetchChatHistory(chatMode, chatRecipientId);
    };

    client.onStompError = (frame) => {
      console.error('[STOMP Error]', frame);
      setIsChatConnected(false);
    };

    client.onDisconnect = () => {
      console.log('[STOMP Disconnected]');
      setIsChatConnected(false);
      if (heartbeatInterval) clearInterval(heartbeatInterval);
    };

    client.onWebSocketClose = () => {
      console.log('[STOMP WebSocket Closed]');
      setIsChatConnected(false);
    };

    client.activate();
    setStompClient(client);

    return () => {
      if (heartbeatInterval) clearInterval(heartbeatInterval);
      try {
        client.publish({ destination: `/app/workspace/${activeWorkspace.id}/leave` });
      } catch (err) {}
      client.deactivate();
      setStompClient(null);
      setIsChatConnected(false);
    };
  }, [activeWorkspace?.id]);

  const publishActivity = (activity: string, detail: string) => {
    if (!stompClient || !isChatConnected || !activeWorkspace) return;
    try {
      stompClient.publish({
        destination: `/app/workspace/${activeWorkspace.id}/activity`,
        body: JSON.stringify({ type: activity, detail })
      });
    } catch (err) {
      console.warn('Failed to publish activity', err);
    }
  };

  useEffect(() => {
    if (!isChatConnected) return;

    let activity = 'idle';
    let detail = '';

    if (isUploading) {
      activity = 'uploading';
      detail = 'files';
    } else if (previewDoc) {
      if (isEditingContent) {
        activity = 'editing';
        detail = previewDoc.name;
      } else {
        activity = 'viewing';
        detail = previewDoc.name;
      }
    }

    publishActivity(activity, detail);
  }, [previewDoc, isEditingContent, isUploading, isChatConnected, activeWorkspace?.id]);

  // Scroll chat list to bottom
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages]);

  // Handle switching chat context and marking messages as read
  useEffect(() => {
    if (!activeWorkspace) return;
    if (isChatOpen) {
      fetchChatHistory(chatMode, chatRecipientId);

      if (chatMode === 'group') {
        setUnreadGroup(false);
      } else if (chatMode === 'direct' && chatRecipientId) {
        // Clear unread status locally for this member
        setUnreadSenders((prev) => {
          const copy = { ...prev };
          delete copy[chatRecipientId];
          const hasUnread = Object.values(copy).some(v => v === true);
          setUnreadDirect(hasUnread);
          return copy;
        });

        // Mark as read on backend
        api.post(`/workspaces/${activeWorkspace.id}/chat/direct/${chatRecipientId}/read`).catch(console.error);
      }
    }
  }, [chatMode, chatRecipientId, isChatOpen, activeWorkspace?.id]);

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
      fetchDocuments(currentFolderId);
    } catch (err: any) {
      console.error('Delete failed', err);
      alert(err.response?.data?.message || 'Failed to delete document.');
    }
  };

  // --- Folder Deletion ---
  const handleDeleteFolder = async (e: React.MouseEvent, folderId: string) => {
    e.stopPropagation();
    if (!window.confirm("Are you sure you want to permanently delete this folder? All contents will be detached/deleted.")) return;
    try {
      await api.delete(`/workspaces/${activeWorkspace.id}/folders/${folderId}`);
      fetchFolders(currentFolderId);
    } catch (err: any) {
      console.error('Failed to delete folder', err);
      alert(err.response?.data?.message || 'Failed to delete folder.');
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
      const response = await api.post(`/workspaces/${activeWorkspace.id}/documents/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        params: { folderId: currentFolderId || undefined }
      });
      const newDoc = response.data?.data || response.data;
      
      if (isOwner && uploadPassword.trim() && newDoc?.id) {
        await api.post(`/workspaces/${activeWorkspace.id}/documents/${newDoc.id}/password`, {
          password: uploadPassword.trim()
        });
      }
      
      setUploadPassword('');
      setIsUploadModalOpen(false);
      setSelectedFile(null);
      fetchDocuments(currentFolderId);
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

  // --- Folder Management handlers ---
  const handleCreateFolder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newFolderName.trim() || !activeWorkspace) return;
    setIsCreatingFolder(true);
    try {
      await api.post(`/workspaces/${activeWorkspace.id}/folders`, {
        name: newFolderName.trim(),
        parentId: currentFolderId,
      });
      setNewFolderName('');
      setIsCreateFolderOpen(false);
      fetchFolders(currentFolderId);
    } catch (err: any) {
      console.error('Failed to create folder', err);
      alert(err.response?.data?.message || 'Failed to create folder.');
    } finally {
      setIsCreatingFolder(false);
    }
  };

  const handleEnterFolder = (folder: any) => {
    setCurrentFolderId(folder.id);
    setFolderPath((prev) => [...prev, { id: folder.id, name: folder.name }]);
  };

  const handleBreadcrumbClick = (index: number) => {
    if (index === -1) {
      setCurrentFolderId(null);
      setFolderPath([]);
    } else {
      const target = folderPath[index];
      setCurrentFolderId(target.id);
      setFolderPath(folderPath.slice(0, index + 1));
    }
  };

  const handleFolderSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    setIsUploading(true);
    try {
      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const relativePath = file.webkitRelativePath || '';
        let targetFolderId = currentFolderId;

        if (relativePath && relativePath.includes('/')) {
          const pathSegments = relativePath.split('/');
          pathSegments.pop(); // remove file name
          const folderPathString = pathSegments.join('/');

          // Call API to resolve/create relative path on backend
          const res = await api.post(`/workspaces/${activeWorkspace.id}/folders/path`, null, {
            params: {
              path: folderPathString,
              parentId: currentFolderId || undefined
            }
          });
          targetFolderId = res.data?.data || res.data;
        }

        const formData = new FormData();
        formData.append('file', file);
        formData.append('name', new Blob([file.name], { type: 'text/plain' }));

        await api.post(`/workspaces/${activeWorkspace.id}/documents/upload`, formData, {
          headers: { 'Content-Type': 'multipart/form-data' },
          params: { folderId: targetFolderId || undefined }
        });
      }

      fetchDocuments(currentFolderId);
      fetchFolders(currentFolderId);
    } catch (err: any) {
      console.error('Folder upload failed', err);
      alert(err.response?.data?.message || 'Failed to upload folder structure.');
    } finally {
      setIsUploading(false);
    }
  };

  // --- Link Upload handler ---
  const handleCreateLink = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!linkName.trim() || !linkUrl.trim() || !activeWorkspace) return;
    setIsSavingLink(true);
    try {
      const tagsArray = linkTags.split(',').map(t => t.trim()).filter(Boolean);
      await api.post(`/workspaces/${activeWorkspace.id}/documents/link`, {
        name: linkName.trim(),
        url: linkUrl.trim(),
        description: linkDescription.trim(),
        tags: tagsArray
      }, {
        params: { folderId: currentFolderId || undefined }
      });
      setLinkName('');
      setLinkUrl('');
      setLinkDescription('');
      setLinkTags('');
      setIsLinkModalOpen(false);
      fetchDocuments(currentFolderId);
    } catch (err: any) {
      console.error('Failed to create link', err);
      alert(err.response?.data?.message || 'Failed to upload link.');
    } finally {
      setIsSavingLink(false);
    }
  };

  // --- Password protection Verification ---
  const handleVerifyPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passwordDoc || !docPassword.trim()) return;
    setIsVerifyingPassword(true);
    setPasswordError(null);
    try {
      const res = await api.post(`/workspaces/${activeWorkspace.id}/documents/${passwordDoc.id}/verify-password`, {
        password: docPassword
      }, {
        params: { type: passwordAction }
      });
      const url = res.data?.presignedUrl || res.data?.data?.presignedUrl || res.data?.url;
      if (!url) throw new Error('No presigned URL returned from verification');

      setIsPasswordModalOpen(false);
      setDocPassword('');

      if (passwordAction === 'download') {
        window.open(url, '_blank');
      } else {
        setPreviewDoc(passwordDoc);
        setPreviewUrl(url);

        const name = passwordDoc.name || '';
        const contentType = passwordDoc.contentType || '';
        let type: 'pdf' | 'image' | 'code' | 'fallback' = 'fallback';
        if (isPdf(name, contentType)) type = 'pdf';
        else if (isImage(name, contentType)) type = 'image';
        else if (isCodeOrText(name, contentType)) type = 'code';
        setPreviewType(type);

        if (type === 'code') {
          try {
            const contentRes = await api.get(`/workspaces/${activeWorkspace.id}/documents/${passwordDoc.id}/content`, {
              params: { password: docPassword }
            });
            const text = typeof contentRes.data === 'string' ? contentRes.data : JSON.stringify(contentRes.data);
            setCodeContent(text);
            setEditingText(text);
          } catch (fetchErr) {
            setPreviewError('Cannot load visual text preview. Download the file to view its full code.');
            setPreviewType('fallback');
          }
        }
      }
    } catch (err: any) {
      console.error('Verification failed', err);
      setPasswordError(err.response?.data?.message || 'Incorrect password.');
    } finally {
      setIsVerifyingPassword(false);
    }
  };

  // --- Save Inline Content changes ---
  const handleSaveContent = async () => {
    if (!activeWorkspace || !previewDoc) return;
    setIsSavingContent(true);
    try {
      await api.put(`/workspaces/${activeWorkspace.id}/documents/${previewDoc.id}/content`, {
        content: editingText
      });
      setCodeContent(editingText);
      setIsEditingContent(false);
      fetchDocuments(currentFolderId);
    } catch (err: any) {
      console.error('Failed to save file content', err);
      alert(err.response?.data?.message || 'Failed to save file content.');
    } finally {
      setIsSavingContent(false);
    }
  };

  // --- WebSocket Realtime Chat Sender ---
  const sendChatMessage = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!chatInput.trim() || !activeWorkspace) return;

    const payload = {
      workspaceId: activeWorkspace.id,
      recipientId: chatMode === 'direct' ? chatRecipientId : null,
      messageText: chatInput.trim(),
    };

    const text = chatInput.trim();
    setChatInput('');

    // If STOMP is connected, attempt to publish via STOMP
    if (isChatConnected && stompClient && stompClient.connected) {
      try {
        stompClient.publish({
          destination: '/app/chat.send',
          body: JSON.stringify(payload),
        });
        return;
      } catch (err) {
        console.warn('STOMP publish failed, falling back to REST API', err);
      }
    }

    // Fallback: send message via HTTP REST API
    try {
      const res = await api.post(`/workspaces/${activeWorkspace.id}/chat/send`, payload);
      const newMsg = res.data?.data || res.data;
      setChatMessages((prev) => {
        if (prev.some(m => m.id === newMsg.id)) return prev;
        return [...prev, newMsg];
      });
    } catch (err: any) {
      console.error('Failed to send message via REST API fallback', err);
      alert(err.response?.data?.message || 'Failed to send message. Please check your connection.');
      setChatInput(text); // restore input text
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
    if (doc.isPasswordProtected && !isOwner) {
      setPasswordDoc(doc);
      setPasswordAction('preview');
      setDocPassword('');
      setPasswordError(null);
      setIsPasswordModalOpen(true);
      return;
    }

    setPreviewDoc(doc);
    setIsEditingMetadata(false);
    setIsPreviewLoading(true);
    setPreviewUrl(null);
    setCodeContent(null);
    setPreviewError(null);
    setEditingText('');

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
          const contentRes = await api.get(`/workspaces/${activeWorkspace.id}/documents/${doc.id}/content`);
          const text = typeof contentRes.data === 'string' ? contentRes.data : JSON.stringify(contentRes.data);
          setCodeContent(text);
          setEditingText(text);
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
    const doc = documents.find(d => d.id === docId) || previewDoc;
    if (doc?.isPasswordProtected && !isOwner) {
      setPasswordDoc(doc);
      setPasswordAction('download');
      setDocPassword('');
      setPasswordError(null);
      setIsPasswordModalOpen(true);
      return;
    }

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

  // --- Helpers for real-time member presence ---
  const formatTimeAgo = (milli: number) => {
    if (!milli) return 'Offline';
    const diffMs = Date.now() - milli;
    if (diffMs < 0) return 'Active just now';
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMins / 60);
    const diffDays = Math.floor(diffHours / 24);

    if (diffMins < 1) return 'Active just now';
    if (diffMins < 60) return `Active ${diffMins}m ago`;
    if (diffHours < 24) return `Active ${diffHours}h ago`;
    return `Active ${diffDays}d ago`;
  };

  const getMemberPresence = (userId: string) => {
    const currentUserId = user?.userId || user?.id;
    const detail = presences.find(p => p.userId === userId);
    
    if (userId === currentUserId) {
      let activity = 'idle';
      let activityDetail = '';
      if (isUploading) {
        activity = 'uploading';
        activityDetail = 'files';
      } else if (previewDoc) {
        activity = isEditingContent ? 'editing' : 'viewing';
        activityDetail = previewDoc.name;
      }
      return {
        online: true,
        status: 'online' as const,
        activity,
        activityDetail,
        lastActiveAt: Date.now()
      };
    }
    
    if (detail) {
      return {
        online: detail.status === 'online',
        status: detail.status,
        activity: detail.activity || 'idle',
        activityDetail: detail.activityDetail || '',
        lastActiveAt: detail.lastActiveAt || 0
      };
    }
    
    return {
      online: false,
      status: 'offline' as const,
      activity: 'offline',
      activityDetail: '',
      lastActiveAt: 0
    };
  };

  const copyToClipboard = async (text: string, type: 'ws' | 'member', memberId?: string) => {
    const success = await copyTextToClipboard(text);
    if (success) {
      if (type === 'ws') {
        setCopiedWsId(true);
        setTimeout(() => setCopiedWsId(false), 2000);
      } else if (type === 'member' && memberId) {
        setCopiedMemberId(memberId);
        setTimeout(() => setCopiedMemberId(null), 2000);
      }
    }
  };

  const filteredDocs = documents.filter(d => d.name?.toLowerCase().includes(searchQuery.toLowerCase()));

  // Active workspace role checks
  const currentUserId = user?.userId || user?.id;
  const currentMember = members.find(m => m.userId === currentUserId);
  const workspaceRole = currentMember?.role || activeWorkspace.role || 'VIEWER';
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
              <button onClick={() => { setIsUploadModalOpen(false); setSelectedFile(null); setUploadPassword(''); }} className="text-muted-foreground hover:text-foreground">
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
              {isOwner && (
                <div className="flex flex-col gap-2">
                  <label className="text-sm font-medium">Password Protection (Optional)</label>
                  <input 
                    type="password" 
                    value={uploadPassword}
                    onChange={e => setUploadPassword(e.target.value)}
                    placeholder="Set secure password for this file..."
                    className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  />
                </div>
              )}
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => { setIsUploadModalOpen(false); setSelectedFile(null); setUploadPassword(''); }} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
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
                {previewType === 'code' && isEditor && (
                  isEditingContent ? (
                    <>
                      <button 
                        onClick={handleSaveContent}
                        disabled={isSavingContent}
                        className="flex items-center gap-2 px-3 py-1.5 rounded bg-emerald-600 text-white hover:bg-emerald-700 transition-colors text-sm font-medium cursor-pointer disabled:opacity-50"
                      >
                        {isSavingContent ? 'Saving...' : 'Save File'}
                      </button>
                      <button 
                        onClick={() => { setIsEditingContent(false); setEditingText(codeContent || ''); }}
                        className="flex items-center gap-2 px-3 py-1.5 rounded bg-zinc-800 text-zinc-300 hover:bg-zinc-700 transition-colors text-sm font-medium cursor-pointer"
                      >
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button 
                      onClick={() => setIsEditingContent(true)}
                      className="flex items-center gap-2 px-3 py-1.5 rounded bg-blue-600/90 text-white hover:bg-blue-600 transition-colors text-sm font-medium cursor-pointer"
                    >
                      Edit File
                    </button>
                  )
                )}
                <button 
                  onClick={() => handleDownloadDocument(null, previewDoc.id)}
                  className="flex items-center gap-2 px-3 py-1.5 rounded bg-primary/10 text-primary hover:bg-primary/20 transition-colors text-sm font-medium cursor-pointer"
                >
                  <Download className="w-4 h-4" /> Download
                </button>
                <button onClick={() => { setPreviewDoc(null); setIsEditingMetadata(false); setIsEditingContent(false); }} className="p-1.5 text-muted-foreground hover:text-foreground rounded hover:bg-accent cursor-pointer">
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
                  <iframe src={previewUrl} className="absolute inset-0 w-full h-full border-none" title="Document Preview" />
                ) : previewType === 'image' && previewUrl ? (
                  <img src={previewUrl} className="max-w-full max-h-full object-contain p-4" alt="Preview" />
                ) : previewType === 'code' && codeContent ? (
                  isEditingContent ? (
                    <textarea
                      value={editingText}
                      onChange={(e) => setEditingText(e.target.value)}
                      className="absolute inset-0 w-full h-full p-6 text-xs text-zinc-300 font-mono bg-zinc-950 border-none outline-none resize-none select-text text-left"
                    />
                  ) : (
                    <pre className="absolute inset-0 w-full h-full p-6 text-xs text-zinc-300 font-mono overflow-auto whitespace-pre select-text text-left bg-zinc-950">
                      <code>{codeContent}</code>
                    </pre>
                  )
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
                  <div className="flex justify-between items-center mb-4">
                    <h4 className="font-semibold text-sm uppercase tracking-wider text-muted-foreground">Metadata Context</h4>
                    {isEditor && !isEditingMetadata && (
                      <button 
                        onClick={startEditingMetadata}
                        className="text-xs bg-foreground text-background font-semibold px-2 py-1 rounded hover:opacity-95 transition-opacity cursor-pointer"
                      >
                        Edit Details
                      </button>
                    )}
                  </div>

                  {isEditingMetadata ? (
                    <form onSubmit={handleSaveMetadata} className="flex flex-col gap-3 text-xs">
                      <div className="flex flex-col gap-1.5">
                        <label className="font-medium text-foreground">Document Name *</label>
                        <input 
                          type="text" 
                          value={editName}
                          onChange={e => setEditName(e.target.value)}
                          className="bg-background border border-border rounded px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary text-foreground"
                          required
                        />
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <label className="font-medium text-foreground">Description</label>
                        <textarea 
                          value={editDescription}
                          onChange={e => setEditDescription(e.target.value)}
                          placeholder="Context, notes..."
                          className="bg-background border border-border rounded px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary h-16 resize-none text-foreground"
                        />
                      </div>
                      <div className="flex flex-col gap-1.5">
                        <label className="font-medium text-foreground">Tags (comma-separated)</label>
                        <input 
                          type="text" 
                          value={editTags}
                          onChange={e => setEditTags(e.target.value)}
                          placeholder="tag1, tag2"
                          className="bg-background border border-border rounded px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary text-foreground"
                        />
                      </div>
                      {isOwner && (
                        <div className="flex flex-col gap-1.5 pt-2 border-t border-border mt-1">
                          <label className="font-medium text-foreground">Password Protection (Leave blank to remove)</label>
                          <input 
                            type="password" 
                            value={newDocPassword}
                            onChange={e => setNewDocPassword(e.target.value)}
                            placeholder="Enter secure password..."
                            className="bg-background border border-border rounded px-3 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary text-foreground"
                          />
                        </div>
                      )}
                      <div className="flex gap-2 justify-end mt-2">
                        <button 
                          type="button" 
                          onClick={() => setIsEditingMetadata(false)} 
                          className="px-2.5 py-1 bg-accent text-foreground rounded hover:opacity-90 transition-opacity"
                        >
                          Cancel
                        </button>
                        <button 
                          type="submit" 
                          disabled={isSavingMetadata}
                          className="px-2.5 py-1 bg-foreground text-background rounded font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity"
                        >
                          {isSavingMetadata ? 'Saving...' : 'Save'}
                        </button>
                      </div>
                    </form>
                  ) : (
                    <div className="space-y-2 text-xs">
                      <p><strong className="text-foreground">Size:</strong> {(previewDoc.fileSizeBytes / 1024).toFixed(1)} KB</p>
                       <p><strong className="text-foreground">Uploaded At:</strong> {new Date(previewDoc.createdAt).toLocaleDateString()}</p>
                      <p><strong className="text-foreground">Revision:</strong> v{previewDoc.version}</p>
                      <p className="flex items-center gap-1.5 mt-1 font-semibold text-xs">
                        {previewDoc.isPasswordProtected ? (
                          <span className="flex items-center gap-1 text-amber-500 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded">
                            <Lock className="w-3 h-3" /> Password Protected
                          </span>
                        ) : (
                          <span className="flex items-center gap-1 text-emerald-500 bg-emerald-500/10 border border-emerald-500/20 px-2 py-0.5 rounded">
                            <Unlock className="w-3 h-3" /> Public in Workspace
                          </span>
                        )}
                      </p>
                      {previewDoc.description && <p className="mt-2"><strong className="text-foreground">Notes:</strong> {previewDoc.description}</p>}
                      {previewDoc.tags && previewDoc.tags.length > 0 && (
                        <div className="flex flex-wrap gap-1 mt-2">
                          {previewDoc.tags.map((t, i) => (
                            <span key={i} className="bg-accent px-2 py-0.5 rounded text-[10px] text-muted-foreground">{t}</span>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
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
          <div className="flex flex-wrap items-center gap-2 shrink-0">
            <button 
              onClick={() => setIsChatOpen(!isChatOpen)}
              className="relative flex items-center gap-2 bg-zinc-800 text-foreground border border-border px-3.5 py-2 rounded-lg text-sm font-semibold hover:bg-accent transition-all cursor-pointer shadow-sm animate-fade-in"
            >
              <MessageSquare className="w-4 h-4" /> 
              <span>Chat Drawer</span>
              {unreadGroup && (
                <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-red-500"></span>
                </span>
              )}
              {unreadDirect && !unreadGroup && (
                <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500"></span>
                </span>
              )}
            </button>

            {isEditor && (
              <>
                <label className="flex items-center gap-2 bg-foreground text-background px-3.5 py-2 rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity cursor-pointer shadow-md shrink-0 justify-center">
                  <Upload className="w-4 h-4" /> Upload File
                  <input type="file" className="hidden" onChange={handleFileSelect} />
                </label>

                <label className="flex items-center gap-2 bg-foreground/10 text-foreground border border-border px-3.5 py-2 rounded-lg text-sm font-semibold hover:bg-accent transition-colors cursor-pointer shadow-sm shrink-0 justify-center">
                  <FolderPlus className="w-4 h-4" /> Upload Folder
                  <input 
                    type="file" 
                    className="hidden" 
                    multiple 
                    onChange={handleFolderSelect} 
                    {...({ webkitdirectory: "", directory: "" } as any)} 
                  />
                </label>

                <button 
                  onClick={() => setIsCreateFolderOpen(true)}
                  className="flex items-center gap-2 bg-foreground/10 text-foreground border border-border px-3.5 py-2 rounded-lg text-sm font-semibold hover:bg-accent transition-colors cursor-pointer shadow-sm"
                >
                  <Plus className="w-4 h-4" /> New Folder
                </button>

                <button 
                  onClick={() => setIsLinkModalOpen(true)}
                  className="flex items-center gap-2 bg-foreground/10 text-foreground border border-border px-3.5 py-2 rounded-lg text-sm font-semibold hover:bg-accent transition-colors cursor-pointer shadow-sm"
                >
                  <Link2 className="w-4 h-4" /> New Link
                </button>
              </>
            )}
          </div>
        </div>

        {/* Folder Breadcrumbs */}
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground mb-4">
          <button 
            onClick={() => handleBreadcrumbClick(-1)}
            className="hover:text-foreground hover:underline transition-colors font-medium cursor-pointer"
          >
            Root
          </button>
          {folderPath.map((folder, index) => (
            <span key={folder.id} className="flex items-center gap-1.5">
              <span>/</span>
              <button 
                onClick={() => handleBreadcrumbClick(index)}
                className={`hover:text-foreground hover:underline transition-colors font-medium cursor-pointer ${
                  index === folderPath.length - 1 ? 'text-foreground font-semibold' : ''
                }`}
              >
                {folder.name}
              </button>
            </span>
          ))}
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
                  <td colSpan={4} className="px-6 py-12 text-center text-muted-foreground">Loading items...</td>
                </tr>
              ) : folders.length === 0 && filteredDocs.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-6 py-20 text-center">
                    <File className="w-10 h-10 mx-auto text-muted-foreground mb-4 opacity-50" />
                    <p className="text-muted-foreground">No folders or documents found in this folder.</p>
                  </td>
                </tr>
              ) : (
                <>
                  {/* Folders */}
                  {folders.map(folder => (
                    <tr 
                      key={folder.id} 
                      onClick={() => handleEnterFolder(folder)}
                      className="hover:bg-accent/40 transition-colors cursor-pointer group"
                    >
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-3 font-semibold text-amber-500">
                          <Folder className="w-5 h-5 fill-amber-500/25" />
                          <span className="truncate max-w-[200px] sm:max-w-xs">{folder.name}</span>
                        </div>
                      </td>
                      <td className="px-6 py-4 text-muted-foreground text-xs font-semibold">FOLDER</td>
                      <td className="px-6 py-4 text-xs font-mono text-muted-foreground">{folder.createdBy ? `${folder.createdBy.substring(0,8)}...` : 'Unknown'}</td>
                      <td className="px-6 py-4 text-right" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center justify-end gap-2">
                          <span className="text-xs text-muted-foreground italic mr-2 group-hover:inline hidden">Double-click to open</span>
                          {isEditor && (
                            <button 
                              onClick={(e) => handleDeleteFolder(e, folder.id)}
                              className="p-1.5 hover:bg-destructive/15 rounded text-muted-foreground hover:text-destructive transition-colors cursor-pointer" title="Delete Folder"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}

                  {/* Documents */}
                  {filteredDocs.map(doc => {
                    const dispType = getFileDisplayType(doc.name, doc.contentType);
                    const isLink = doc.contentType === 'link';
                    return (
                      <tr 
                        key={doc.id} 
                        onClick={() => {
                          if (isLink) {
                            if (doc.fileKey) {
                              const url = doc.fileKey.startsWith('http://') || doc.fileKey.startsWith('https://') ? doc.fileKey : `https://${doc.fileKey}`;
                              window.open(url, '_blank');
                            }
                          } else {
                            handlePreviewDocument(doc);
                          }
                        }}
                        className="hover:bg-accent/40 transition-colors cursor-pointer group"
                      >
                        <td className="px-6 py-4">
                          <div className="flex items-center gap-3 font-medium text-foreground">
                            {isLink ? <Link2 className="w-5 h-5 text-indigo-400" /> :
                             dispType === 'PDF' ? <FileText className="w-5 h-5 text-red-400" /> :
                             ['PNG','JPG','JPEG','SVG','WEBP'].includes(dispType) ? <ImageIcon className="w-5 h-5 text-emerald-400" /> :
                             ['JS','JSX','TS','TSX','HTML','CSS','JSON','PY','JAVA'].includes(dispType) ? <FileCode className="w-5 h-5 text-blue-400" /> :
                             <File className="w-5 h-5 text-muted-foreground" />}
                            <span className="truncate max-w-[200px] sm:max-w-xs flex items-center gap-1.5">
                               {doc.name || 'Untitled Document'}
                              {doc.isPasswordProtected && <Lock className="w-3.5 h-3.5 text-amber-500 shrink-0" />}
                            </span>
                          </div>
                        </td>
                        <td className="px-6 py-4 text-muted-foreground text-xs font-semibold">{isLink ? 'LINK' : dispType}</td>
                        <td className="px-6 py-4 text-xs font-mono text-muted-foreground">{doc.uploadedBy ? `${doc.uploadedBy.substring(0,8)}...` : 'Unknown'}</td>
                        <td className="px-6 py-4 text-right" onClick={e => e.stopPropagation()}>
                          <div className="flex items-center justify-end gap-2">
                            {!isLink && (
                              <>
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
                              </>
                            )}
                            {isLink && (
                              <button 
                                onClick={() => {
                                  if (doc.fileKey) {
                                    const url = doc.fileKey.startsWith('http://') || doc.fileKey.startsWith('https://') ? doc.fileKey : `https://${doc.fileKey}`;
                                    window.open(url, '_blank');
                                  }
                                }}
                                className="p-1.5 hover:bg-background rounded text-muted-foreground hover:text-foreground cursor-pointer" title="Open Link"
                              >
                                <Eye className="w-4 h-4" />
                              </button>
                            )}
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
                  })}
                </>
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
                      <span className={`absolute bottom-0 right-0 block h-2.5 w-2.5 rounded-full ring-2 ring-card ${
                        presence.online ? 'bg-emerald-500' : 'bg-zinc-500/60'
                      }`} title={presence.online ? 'Online' : 'Offline'} />
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
                      {presence.online ? (
                        presence.activity === 'viewing' ? (
                          <p className="text-[9px] text-emerald-400 truncate mt-0.5 flex items-center gap-1">
                            <span className="animate-pulse">👁</span> Viewing: {presence.activityDetail}
                          </p>
                        ) : presence.activity === 'editing' ? (
                          <p className="text-[9px] text-amber-400 truncate mt-0.5 flex items-center gap-1">
                            <span className="animate-pulse">✍</span> Editing: {presence.activityDetail}
                          </p>
                        ) : presence.activity === 'uploading' ? (
                          <p className="text-[9px] text-blue-400 truncate mt-0.5 flex items-center gap-1">
                            <span className="animate-pulse">⏳</span> Uploading: {presence.activityDetail}
                          </p>
                        ) : (
                          <p className="text-[9px] text-muted-foreground truncate mt-0.5">💤 Idle</p>
                        )
                      ) : (
                        <p className="text-[9px] text-muted-foreground truncate mt-0.5">
                          {formatTimeAgo(presence.lastActiveAt)}
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

      {/* Create Folder Modal */}
      {isCreateFolderOpen && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold">Create New Folder</h3>
              <button onClick={() => setIsCreateFolderOpen(false)} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <form onSubmit={handleCreateFolder} className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Folder Name</label>
                <input 
                  type="text" 
                  value={newFolderName}
                  onChange={e => setNewFolderName(e.target.value)}
                  placeholder="e.g. Compliance Documents"
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                />
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => setIsCreateFolderOpen(false)} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
                  Cancel
                </button>
                <button type="submit" disabled={isCreatingFolder} className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity">
                  {isCreatingFolder ? 'Creating...' : 'Create Folder'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Create Link Modal */}
      {isLinkModalOpen && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold">Upload External Link</h3>
              <button onClick={() => setIsLinkModalOpen(false)} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <form onSubmit={handleCreateLink} className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Link Name *</label>
                <input 
                  type="text" 
                  value={linkName}
                  onChange={e => setLinkName(e.target.value)}
                  placeholder="e.g. FDA Guidelines Portal"
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">URL *</label>
                <input 
                  type="url" 
                  value={linkUrl}
                  onChange={e => setLinkUrl(e.target.value)}
                  placeholder="https://example.com"
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Description</label>
                <textarea 
                  value={linkDescription}
                  onChange={e => setLinkDescription(e.target.value)}
                  placeholder="External resources, compliance tracker link..."
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary h-20 resize-none"
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Tags (comma-separated)</label>
                <input 
                  type="text" 
                  value={linkTags}
                  onChange={e => setLinkTags(e.target.value)}
                  placeholder="compliance, clinical, annex1"
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                />
              </div>
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => setIsLinkModalOpen(false)} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
                  Cancel
                </button>
                <button type="submit" disabled={isSavingLink} className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity">
                  {isSavingLink ? 'Saving...' : 'Add Link'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Password Prompt Modal */}
      {isPasswordModalOpen && passwordDoc && (
        <div className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-card border border-border rounded-xl shadow-xl w-full max-w-md p-6">
            <div className="flex justify-between items-center mb-6">
              <h3 className="text-lg font-bold flex items-center gap-2">
                <Lock className="w-5 h-5 text-amber-500" /> Unlock Protected File
              </h3>
              <button onClick={() => { setIsPasswordModalOpen(false); setDocPassword(''); }} className="text-muted-foreground hover:text-foreground">
                <X className="w-5 h-5" />
              </button>
            </div>
            <form onSubmit={handleVerifyPassword} className="flex flex-col gap-4">
              <p className="text-xs text-muted-foreground">
                The document <strong className="text-foreground">{passwordDoc.name}</strong> is password protected. Enter the access password to {passwordAction === 'download' ? 'download' : 'view'} it.
              </p>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium">Access Password</label>
                <input 
                  type="password" 
                  value={docPassword}
                  onChange={e => setDocPassword(e.target.value)}
                  placeholder="Enter file password..."
                  className="bg-background border border-border rounded-lg px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary"
                  required
                  autoFocus
                />
              </div>
              {passwordError && (
                <p className="text-xs text-destructive font-semibold">{passwordError}</p>
              )}
              <div className="flex justify-end gap-3 mt-4">
                <button type="button" onClick={() => { setIsPasswordModalOpen(false); setDocPassword(''); }} className="px-4 py-2 rounded-lg text-sm font-medium hover:bg-accent transition-colors">
                  Cancel
                </button>
                <button type="submit" disabled={isVerifyingPassword} className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity">
                  {isVerifyingPassword ? 'Unlocking...' : 'Unlock File'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Real-time Workspace Chat Drawer */}
      {isChatOpen && (
        <div className="fixed inset-y-0 right-0 w-full sm:w-96 bg-card border-l border-border shadow-2xl z-40 flex flex-col transition-transform duration-300 transform translate-x-0">
          {/* Header */}
          <div className="p-4 border-b border-border flex justify-between items-center bg-muted/40 shrink-0">
            <div className="flex items-center gap-2">
              <MessageSquare className="w-5 h-5 text-primary" />
              <div>
                <h3 className="font-bold text-sm">Workspace Chat</h3>
                <p className="text-[10px] text-muted-foreground flex items-center gap-1">
                  <span className={`h-1.5 w-1.5 rounded-full ${isChatConnected ? 'bg-emerald-500 animate-pulse' : 'bg-amber-500 animate-pulse'}`} />
                  {isChatConnected ? 'Real-time Mode' : 'HTTP Fallback Mode (Always Online)'}
                </p>
              </div>
            </div>
            <button onClick={() => setIsChatOpen(false)} className="p-1 hover:bg-accent rounded text-muted-foreground hover:text-foreground">
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Context switch tabs */}
          <div className="flex border-b border-border shrink-0 text-xs">
            <button 
              onClick={() => { setChatMode('group'); setChatRecipientId(null); }}
              className={`flex-1 py-3 text-center font-semibold border-b-2 transition-all relative ${
                chatMode === 'group' ? 'border-primary text-primary bg-primary/5' : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              Workspace Group
              {unreadGroup && (
                <span className="absolute top-2.5 right-3 h-2 w-2 rounded-full bg-red-500 animate-pulse" />
              )}
            </button>
            <button 
              onClick={() => {
                setChatMode('direct');
                const other = members.find(m => m.userId !== (user?.userId || user?.id));
                if (other) setChatRecipientId(other.userId);
              }}
              className={`flex-1 py-3 text-center font-semibold border-b-2 transition-all relative ${
                chatMode === 'direct' ? 'border-primary text-primary bg-primary/5' : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              Direct Messages
              {unreadDirect && (
                <span className="absolute top-2.5 right-3 h-2 w-2 rounded-full bg-emerald-500 animate-pulse" />
              )}
            </button>
          </div>

          {/* Direct Messaging Member Selector list */}
          {chatMode === 'direct' && (
            <div className="p-2 border-b border-border flex items-center gap-1.5 overflow-x-auto shrink-0 bg-muted/20">
              {members.filter(m => m.userId !== (user?.userId || user?.id)).length === 0 ? (
                <span className="text-[10px] text-muted-foreground p-1">No other members to message.</span>
              ) : (
                members.filter(m => m.userId !== (user?.userId || user?.id)).map(member => (
                  <button
                    key={member.userId}
                    onClick={() => setChatRecipientId(member.userId)}
                    className={`px-3 py-1.5 rounded-full text-xs font-medium border transition-all shrink-0 cursor-pointer flex items-center gap-1.5 relative ${
                      chatRecipientId === member.userId
                        ? 'bg-primary border-primary text-primary-foreground shadow-sm shadow-primary/10'
                        : 'bg-background border-border text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    {member.fullName}
                    {unreadSenders[member.userId] && (
                      <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse shrink-0" />
                    )}
                  </button>
                ))
              )}
            </div>
          )}

          {/* Messages list */}
          <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-muted/10 select-text">
            {chatMessages.filter(m => 
              chatMode === 'group' 
                ? m.recipientId === null 
                : (
                  (m.senderId === (user?.userId || user?.id) && m.recipientId === chatRecipientId) ||
                  (m.senderId === chatRecipientId && m.recipientId === (user?.userId || user?.id))
                )
            ).length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center text-muted-foreground p-6">
                <MessageSquare className="w-8 h-8 opacity-25 mb-2" />
                <p className="text-xs">No messages yet. Send a message to start the conversation!</p>
              </div>
            ) : (
              chatMessages
                .filter(m => 
                  chatMode === 'group' 
                    ? m.recipientId === null 
                    : (
                      (m.senderId === (user?.userId || user?.id) && m.recipientId === chatRecipientId) ||
                      (m.senderId === chatRecipientId && m.recipientId === (user?.userId || user?.id))
                    )
                )
                .map((msg, index) => {
                  const isMe = msg.senderId === (user?.userId || user?.id);
                  const senderName = isMe ? 'You' : (members.find(m => m.userId === msg.senderId)?.fullName || 'Member');
                  return (
                    <div key={msg.id || index} className={`flex flex-col ${isMe ? 'items-end' : 'items-start'}`}>
                      <span className="text-[10px] text-muted-foreground px-1 mb-0.5">{senderName}</span>
                      <div className={`max-w-[80%] rounded-2xl px-3.5 py-2 text-xs leading-relaxed shadow-sm ${
                        isMe 
                          ? 'bg-primary text-primary-foreground rounded-tr-none' 
                          : 'bg-card border border-border text-foreground rounded-tl-none'
                      }`}>
                        {msg.messageText}
                      </div>
                      {isMe && msg.recipientId && (
                        <span className="text-[9px] text-muted-foreground px-1 mt-0.5">
                          {msg.isRead ? 'Đã xem' : 'Đã gửi'}
                        </span>
                      )}
                    </div>
                  );
                })
            )}
            <div ref={chatEndRef} />
          </div>

          {/* Input text form */}
          <form onSubmit={sendChatMessage} className="p-3 border-t border-border bg-card flex gap-2 shrink-0">
            <input 
              type="text"
              value={chatInput}
              onChange={e => setChatInput(e.target.value)}
              placeholder={
                !isChatConnected 
                  ? (chatMode === 'group' ? 'Message channel (HTTP Fallback)...' : 'Message member privately (HTTP Fallback)...') 
                  : (chatMode === 'group' ? 'Message workspace channel...' : 'Message member privately...')
              }
              className="flex-1 bg-background border border-border rounded-lg px-3.5 py-2 text-xs focus:outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
            />
            <button 
              type="submit" 
              disabled={!chatInput.trim()}
              className="p-2 bg-primary text-primary-foreground rounded-lg hover:opacity-95 disabled:opacity-40 transition-opacity cursor-pointer flex items-center justify-center shrink-0"
            >
              <Send className="w-3.5 h-3.5" />
            </button>
          </form>
        </div>
      )}

    </div>
  );
}
