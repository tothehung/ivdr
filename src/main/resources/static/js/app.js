/**
 * IVDR Compliance Portal — Frontend Logic
 * Features: OTP Registration, Multi-org Login, Force Download, Analytics, 
 *           RBAC enforcement, Watermark protection, File Editor, Chat, WebSocket
 */

'use strict';

// ============================================================
// STATE
// ============================================================
let state = {
    accessToken: null,
    refreshToken: null,
    user: null,
    currentWorkspaceId: null,
    currentWorkspacePasswordVerified: false,
    wsPassword: null, // stored workspace access password
    documents: [],
    stompClient: null,
    timelineChart: null,
    heatmapChart: null,
    logsPage: 0,
    logsAnomalyOnly: false,
    selectedDoc: null,
    monacoEditor: null,
    quillEditor: null,
    chatHistory: [], // AI chatbot history
    registerData: {}, // temp storage across OTP steps
};

// ============================================================
// API BASE
// ============================================================
const API_BASE = '/api/v1';

async function request(method, path, body = null, options = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (state.accessToken) headers['Authorization'] = `Bearer ${state.accessToken}`;
    
    const init = { method, headers };
    if (body) init.body = JSON.stringify(body);

    let res = await fetch(API_BASE + path, init);

    // Auto refresh on 401
    if (res.status === 401 && state.refreshToken && !options.noRefresh) {
        const refreshed = await tryRefreshToken();
        if (refreshed) {
            headers['Authorization'] = `Bearer ${state.accessToken}`;
            res = await fetch(API_BASE + path, { method, headers, body: init.body });
        } else {
            doLogout();
            return null;
        }
    }

    // Handle empty responses (204, etc.)
    const contentType = res.headers.get('content-type') || '';
    if (!contentType.includes('application/json')) {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return null;
    }

    const text = await res.text();
    if (!text) return null;
    
    const data = JSON.parse(text);
    if (!res.ok) throw new Error(data.message || `HTTP ${res.status}`);
    return data;
}

async function tryRefreshToken() {
    try {
        const res = await fetch(API_BASE + '/auth/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: state.refreshToken })
        });
        if (!res.ok) return false;
        const data = await res.json();
        state.accessToken = data.data.accessToken;
        state.refreshToken = data.data.refreshToken;
        persistTokens();
        return true;
    } catch { return false; }
}

// ============================================================
// PERSISTENCE
// ============================================================
function persistTokens() {
    localStorage.setItem('ivdr_access', state.accessToken || '');
    localStorage.setItem('ivdr_refresh', state.refreshToken || '');
    if (state.user) localStorage.setItem('ivdr_user', JSON.stringify(state.user));
}

function loadPersistedTokens() {
    state.accessToken = localStorage.getItem('ivdr_access') || null;
    state.refreshToken = localStorage.getItem('ivdr_refresh') || null;
    try {
        state.user = JSON.parse(localStorage.getItem('ivdr_user') || 'null');
    } catch { state.user = null; }
}

// ============================================================
// TOASTS
// ============================================================
function showToast(message, type = 'info', duration = 4000) {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    const icons = { success: '✓', error: '✕', info: 'ℹ', warning: '⚠' };
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `<span class="toast-icon">${icons[type]||'ℹ'}</span><span>${message}</span>`;
    container.appendChild(toast);
    setTimeout(() => toast.classList.add('toast-show'), 10);
    setTimeout(() => {
        toast.classList.remove('toast-show');
        setTimeout(() => toast.remove(), 400);
    }, duration);
}

// ============================================================
// SCREEN NAVIGATION
// ============================================================
function showScreen(screenId) {
    document.querySelectorAll('.auth-container, .app-layout').forEach(el => {
        el.classList.remove('active');
    });
    const el = document.getElementById(screenId);
    if (el) {
        el.classList.add('active');
        el.style.animation = 'none';
        requestAnimationFrame(() => {
            el.style.animation = '';
        });
    }
}

function navigateTab(tabId) {
    // Hide all tabs
    document.querySelectorAll('.main-content .view-section').forEach(el => {
        el.classList.remove('active');
    });
    // Update nav
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    
    const tab = document.getElementById(tabId);
    if (tab) {
        tab.classList.add('active');
        tab.style.animation = 'none';
        requestAnimationFrame(() => { tab.style.animation = ''; });
    }
    
    // Map tabs to nav items
    const navMap = {
        'tab-workspaces': 'nav-workspaces',
        'tab-analytics': 'nav-analytics',
        'tab-logs': 'nav-logs'
    };
    const navId = navMap[tabId];
    if (navId) {
        const navEl = document.getElementById(navId);
        if (navEl) navEl.classList.add('active');
    }

    // Load data when switching
    if (tabId === 'tab-analytics') loadAnalytics();
    if (tabId === 'tab-logs') loadAuditLogs(false);
    if (tabId === 'tab-workspaces') loadWorkspaces();
}

// ============================================================
// MODAL
// ============================================================
function openModal(id) {
    const modal = document.getElementById(id);
    if (modal) {
        modal.classList.add('active');
        modal.style.animation = 'none';
        requestAnimationFrame(() => { modal.style.animation = ''; });
    }
}

function closeModal(id) {
    const modal = document.getElementById(id);
    if (modal) modal.classList.remove('active');
}

// Close modal on backdrop click
document.addEventListener('click', (e) => {
    if (e.target.classList.contains('modal-overlay')) {
        e.target.classList.remove('active');
    }
});

// ============================================================
// MULTI-ORG LOGIN
// ============================================================
let availableOrgs = [];

async function lookupOrgs(email) {
    if (!email || !email.includes('@')) return;
    try {
        const res = await fetch(`${API_BASE}/auth/orgs?email=${encodeURIComponent(email)}`);
        if (!res.ok) return;
        const data = await res.json();
        availableOrgs = data.data || [];
        
        const group = document.getElementById('org-selector-group');
        const select = document.getElementById('org-selector');
        
        if (availableOrgs.length > 1) {
            select.innerHTML = availableOrgs.map(o =>
                `<option value="${o.organizationId}">${o.organizationName} [${o.plan}]</option>`
            ).join('');
            group.style.display = 'block';
        } else {
            group.style.display = 'none';
        }
    } catch {}
}

async function handleLogin(event) {
    event.preventDefault();
    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-pass').value;
    const orgSelect = document.getElementById('org-selector');
    const orgId = orgSelect && orgSelect.closest('[style*="block"]') ? orgSelect.value : null;

    try {
        const body = { email, password };
        if (orgId) body.organizationId = orgId;
        
        const res = await fetch(API_BASE + '/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Login failed');

        state.accessToken = data.data.accessToken;
        state.refreshToken = data.data.refreshToken;
        state.user = data.data.user;
        persistTokens();
        
        showScreen('screen-app');
        initApp();
        showToast(`Welcome back, ${state.user.fullName}!`, 'success');
    } catch (err) {
        showToast(err.message || 'Login failed', 'error');
    }
}

// ============================================================
// OTP REGISTRATION
// ============================================================
let registerStep = 1;

function showRegisterStep(step) {
    for (let i = 1; i <= 3; i++) {
        const el = document.getElementById(`register-step-${i}`);
        if (el) el.style.display = i === step ? 'block' : 'none';
        const dot = document.getElementById(`step-dot-${i}`);
        if (dot) {
            dot.classList.toggle('active', i <= step);
            dot.classList.toggle('completed', i < step);
        }
    }
    registerStep = step;
}

async function handleRegisterStep1(event) {
    event.preventDefault();
    const pass = document.getElementById('reg-pass').value;
    const pass2 = document.getElementById('reg-pass2').value;
    
    if (pass !== pass2) {
        showToast('Passwords do not match', 'error');
        return;
    }
    if (pass.length < 8) {
        showToast('Password must be at least 8 characters', 'error');
        return;
    }

    state.registerData = {
        organizationName: document.getElementById('reg-org').value.trim(),
        fullName: document.getElementById('reg-name').value.trim(),
        email: document.getElementById('reg-email').value.trim().toLowerCase(),
        password: pass,
        phone: document.getElementById('reg-phone').value.trim(),
        jobTitle: document.getElementById('reg-job').value.trim()
    };

    // Send OTP
    try {
        const res = await fetch(API_BASE + '/auth/send-otp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                email: state.registerData.email,
                fullName: state.registerData.fullName,
                organizationName: state.registerData.organizationName
            })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Failed to send OTP');
        
        const msg = document.getElementById('otp-sent-msg');
        if (msg) msg.textContent = `Code sent to ${state.registerData.email}`;
        
        showRegisterStep(2);
        showToast(`OTP sent to ${state.registerData.email}`, 'success');
        document.getElementById('otp-0')?.focus();
    } catch (err) {
        showToast(err.message || 'Failed to send OTP', 'error');
    }
}

function otpNext(input, index) {
    input.value = input.value.replace(/\D/g, '');
    if (input.value.length === 1 && index < 5) {
        document.getElementById(`otp-${index + 1}`)?.focus();
    }
}

function otpBack(event, index) {
    if (event.key === 'Backspace' && !event.target.value && index > 0) {
        document.getElementById(`otp-${index - 1}`)?.focus();
    }
}

function getOtpValue() {
    return [0,1,2,3,4,5].map(i => document.getElementById(`otp-${i}`)?.value || '').join('');
}

async function handleVerifyOtp() {
    const otp = getOtpValue();
    if (otp.length !== 6) {
        showToast('Please enter all 6 digits', 'warning');
        return;
    }

    try {
        const res = await fetch(API_BASE + '/auth/verify-otp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: state.registerData.email, otp })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Invalid OTP');
        
        showRegisterStep(3);
        showToast('Email verified successfully!', 'success');
    } catch (err) {
        showToast(err.message || 'OTP verification failed', 'error');
    }
}

async function resendOtp() {
    if (!state.registerData.email) return;
    try {
        await fetch(API_BASE + '/auth/send-otp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                email: state.registerData.email,
                fullName: state.registerData.fullName,
                organizationName: state.registerData.organizationName
            })
        });
        showToast('New OTP sent!', 'success');
    } catch {
        showToast('Failed to resend OTP', 'error');
    }
}

async function completeRegistration() {
    try {
        const res = await fetch(API_BASE + '/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(state.registerData)
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Registration failed');

        state.accessToken = data.data.accessToken;
        state.refreshToken = data.data.refreshToken;
        state.user = data.data.user;
        persistTokens();
        
        showScreen('screen-app');
        initApp();
        showToast(`Welcome, ${state.user.fullName}! Organization created.`, 'success', 6000);
    } catch (err) {
        showToast(err.message || 'Registration failed', 'error');
    }
}

// ============================================================
// PASSWORD STRENGTH CHECKER
// ============================================================
function checkPasswordStrength(pw) {
    let score = 0;
    if (pw.length >= 8) score++;
    if (pw.length >= 12) score++;
    if (/[A-Z]/.test(pw)) score++;
    if (/[0-9]/.test(pw)) score++;
    if (/[^A-Za-z0-9]/.test(pw)) score++;

    const fill = document.getElementById('pw-strength-fill');
    const label = document.getElementById('pw-strength-label');
    if (!fill || !label) return;

    const levels = ['', 'Weak', 'Fair', 'Good', 'Strong', 'Very Strong'];
    const colors = ['', '#ef4444', '#f97316', '#eab308', '#10b981', '#06b6d4'];
    fill.style.width = `${(score/5)*100}%`;
    fill.style.background = colors[score] || '#ef4444';
    label.textContent = levels[score] || '';
    label.style.color = colors[score] || '#ef4444';
}

function togglePassword(id) {
    const inp = document.getElementById(id);
    if (inp) inp.type = inp.type === 'password' ? 'text' : 'password';
}

// ============================================================
// LOGOUT
// ============================================================
async function handleLogout() {
    try {
        if (state.refreshToken) {
            await fetch(API_BASE + '/auth/logout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken: state.refreshToken })
            });
        }
    } catch {}
    doLogout();
}

function doLogout() {
    state.accessToken = null;
    state.refreshToken = null;
    state.user = null;
    localStorage.removeItem('ivdr_access');
    localStorage.removeItem('ivdr_refresh');
    localStorage.removeItem('ivdr_user');
    if (state.stompClient) { try { state.stompClient.disconnect(); } catch {} }
    showScreen('screen-login');
    showToast('Signed out successfully', 'info');
}

// ============================================================
// COPY USER ID
// ============================================================
function copyUserId() {
    if (!state.user?.userId) return;
    navigator.clipboard.writeText(state.user.userId).then(() => {
        showToast('User ID copied to clipboard!', 'success', 2000);
    });
}

// ============================================================
// INIT APP
// ============================================================
function initApp() {
    if (!state.user) return;
    
    // Populate user profile in sidebar
    const nameEl = document.getElementById('user-display-name');
    const roleEl = document.getElementById('user-display-role');
    const idEl = document.getElementById('user-display-id');
    const avatar = document.getElementById('user-avatar');
    
    if (nameEl) nameEl.textContent = state.user.fullName || state.user.email;
    if (roleEl) {
        roleEl.textContent = state.user.role;
        roleEl.className = `user-role-badge role-${(state.user.role||'').toLowerCase()}`;
    }
    if (idEl) {
        const shortId = state.user.userId ? state.user.userId.substring(0, 8) + '...' : '';
        idEl.textContent = shortId;
        idEl.title = state.user.userId || '';
    }
    if (avatar) avatar.textContent = (state.user.fullName || 'U')[0].toUpperCase();
    
    loadWorkspaces();
    connectWebSocket();
}

// ============================================================
// WORKSPACES
// ============================================================
async function loadWorkspaces() {
    try {
        const data = await request('GET', '/workspaces?page=0&size=50');
        if (!data) return;
        const workspaces = data.data?.content || [];
        renderWorkspaceCards(workspaces);
        
        const badge = document.getElementById('ws-badge');
        if (badge) {
            badge.textContent = workspaces.length;
            badge.style.display = workspaces.length > 0 ? 'inline-flex' : 'none';
        }
    } catch (err) {
        showToast('Failed to load workspaces: ' + (err.message || ''), 'error');
    }
}

function renderWorkspaceCards(workspaces) {
    const container = document.getElementById('workspace-list-cards');
    if (!container) return;
    
    if (workspaces.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">📁</div>
                <h3>No Workspaces Yet</h3>
                <p>Create your first Deal Room workspace to get started</p>
                <button class="btn btn-primary" onclick="openModal('modal-workspace')" style="width:auto;padding:.8rem 1.5rem;margin-top:1rem">
                    + Create Workspace
                </button>
            </div>
        `;
        return;
    }

    container.innerHTML = workspaces.map(ws => `
        <div class="workspace-card glass card-3d" onclick="openWorkspace('${ws.id}', '${escapeHtml(ws.name)}', '${escapeHtml(ws.description||'')}')">
            <div class="ws-card-header">
                <div class="ws-icon">${ws.isPrivate ? '🔒' : '📁'}</div>
                <div class="ws-meta">
                    ${ws.isPrivate ? '<span class="badge badge-private">Private</span>' : '<span class="badge badge-public">Public</span>'}
                </div>
            </div>
            <h3 class="ws-card-title">${escapeHtml(ws.name)}</h3>
            <p class="ws-card-desc">${escapeHtml(ws.description || 'No description')}</p>
            <div class="ws-card-footer">
                <span class="ws-member-count">👥 ${ws.memberCount || 0} members</span>
                <span class="ws-date">${formatDate(ws.createdAt)}</span>
            </div>
        </div>
    `).join('');
    
    // Add 3D tilt effect to cards
    initCardTilt();
}

function openWorkspace(wsId, name, desc) {
    state.currentWorkspaceId = wsId;
    state.currentWorkspacePasswordVerified = false;
    
    // Update UI
    const nameEl = document.getElementById('active-ws-name');
    const descEl = document.getElementById('active-ws-desc');
    if (nameEl) nameEl.textContent = name;
    if (descEl) descEl.textContent = desc || 'No description';
    
    // Clear previous state
    const docBody = document.getElementById('document-list-body');
    if (docBody) docBody.innerHTML = '';
    
    navigateTab('tab-workspace-detail');
    loadWorkspaceDocuments(wsId);
    loadWorkspaceMembers(wsId);
    connectWorkspaceWebSocket(wsId);
    renderWatermarkCanvas(name);
}

async function handleCreateWorkspace(event) {
    event.preventDefault();
    const name = document.getElementById('ws-name').value.trim();
    const description = document.getElementById('ws-desc').value.trim();
    const isPrivate = document.getElementById('ws-private').checked;
    const password = document.getElementById('ws-password').value;

    try {
        const res = await request('POST', '/workspaces', { name, description, isPrivate });
        if (!res) return;
        
        closeModal('modal-workspace');
        document.getElementById('form-create-workspace').reset();
        showToast('Workspace created!', 'success');
        
        // Store workspace password in memory if set
        if (password && res.data?.id) {
            localStorage.setItem(`ws_pw_${res.data.id}`, btoa(password));
        }
        
        loadWorkspaces();
    } catch (err) {
        showToast(err.message || 'Failed to create workspace', 'error');
    }
}

// ============================================================
// WORKSPACE DOCUMENTS
// ============================================================
async function loadWorkspaceDocuments(wsId) {
    try {
        const data = await request('GET', `/workspaces/${wsId}/documents`);
        if (!data) return;
        state.documents = data.data?.content || [];
        renderDocumentTable(state.documents);
    } catch (err) {
        showToast('Failed to load documents: ' + (err.message || ''), 'error');
    }
}

function filterDocuments(query) {
    if (!query) {
        renderDocumentTable(state.documents);
        return;
    }
    const q = query.toLowerCase();
    const filtered = state.documents.filter(d =>
        (d.name || '').toLowerCase().includes(q) ||
        (d.description || '').toLowerCase().includes(q)
    );
    renderDocumentTable(filtered);
}

function renderDocumentTable(docs) {
    const tbody = document.getElementById('document-list-body');
    if (!tbody) return;

    const userRole = getCurrentWorkspaceMemberRole();
    const canDelete = userRole === 'OWNER' || userRole === 'EDITOR';
    const canUpload = userRole === 'OWNER' || userRole === 'EDITOR';

    // Show/hide upload button based on role
    const uploadBtn = document.getElementById('btn-upload-trigger');
    if (uploadBtn) uploadBtn.style.display = canUpload ? '' : 'none';
    const inviteBtn = document.getElementById('btn-invite-member');
    if (inviteBtn) inviteBtn.style.display = userRole === 'OWNER' ? '' : 'none';

    if (docs.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align:center;color:var(--text-secondary);padding:2rem">No documents uploaded yet</td></tr>`;
        return;
    }

    tbody.innerHTML = docs.map(doc => `
        <tr onclick="selectDocument('${doc.id}')" class="doc-row" data-id="${doc.id}">
            <td>
                <div class="doc-name-cell">
                    <span class="doc-type-icon">${getFileIcon(doc.contentType)}</span>
                    <span>${escapeHtml(doc.name)}</span>
                </div>
            </td>
            <td><span class="content-type-badge">${getFileExtension(doc.contentType)}</span></td>
            <td>${formatBytes(doc.fileSizeBytes)}</td>
            <td>${formatDate(doc.createdAt)}</td>
            <td class="action-cell" onclick="event.stopPropagation()">
                <button class="action-btn action-preview" onclick="previewDocument('${doc.id}')" title="Preview">👁</button>
                <button class="action-btn action-download" onclick="downloadDocument('${doc.id}', '${escapeHtml(doc.name)}')" title="Download">⬇</button>
                ${canDelete ? `<button class="action-btn action-delete" onclick="deleteDocument('${doc.id}', '${escapeHtml(doc.name)}')" title="Delete">🗑</button>` : ''}
            </td>
        </tr>
    `).join('');
}

function getCurrentWorkspaceMemberRole() {
    const members = window._workspaceMembers || [];
    const me = members.find(m => m.userId === state.user?.userId);
    return me?.role || 'VIEWER';
}

// ============================================================
// DOCUMENT OPERATIONS
// ============================================================
function selectDocument(docId) {
    const doc = state.documents.find(d => d.id === docId);
    if (!doc) return;
    
    state.selectedDoc = doc;
    
    // Highlight selected row
    document.querySelectorAll('.doc-row').forEach(r => r.classList.remove('selected'));
    const row = document.querySelector(`[data-id="${docId}"]`);
    if (row) row.classList.add('selected');
    
    // Show detail panel
    const panel = document.getElementById('selected-doc-panel');
    if (panel) panel.style.display = 'block';
    
    const nameEl = document.getElementById('selected-doc-name');
    const descEl = document.getElementById('selected-doc-desc');
    const tagsEl = document.getElementById('selected-doc-tags');
    
    if (nameEl) nameEl.textContent = doc.name;
    if (descEl) descEl.textContent = doc.description || 'No description';
    if (tagsEl) {
        tagsEl.innerHTML = (doc.tags || []).map(t =>
            `<span class="tag">${escapeHtml(t)}</span>`
        ).join('');
    }

    if (doc.aiSummary) {
        const summaryBox = document.getElementById('ai-summary-box');
        if (summaryBox) summaryBox.textContent = doc.aiSummary;
    }

    // Load preview
    loadDocumentPreview(doc);
}

async function loadDocumentPreview(doc) {
    const previewBox = document.getElementById('doc-preview-box');
    if (!previewBox) return;
    
    previewBox.innerHTML = `<div class="preview-loading">Loading preview...</div>`;

    try {
        const wsId = state.currentWorkspaceId;
        const data = await request('GET', `/workspaces/${wsId}/documents/${doc.id}/preview-url`);
        if (!data?.data?.url) {
            previewBox.innerHTML = `<span class="preview-placeholder">Preview not available</span>`;
            return;
        }
        
        const url = data.data.url;
        const ct = (doc.contentType || '').toLowerCase();
        
        if (ct.includes('image/')) {
            previewBox.innerHTML = `<img src="${url}" style="max-width:100%;max-height:300px;border-radius:8px;object-fit:contain" alt="Preview">`;
        } else if (ct.includes('pdf')) {
            previewBox.innerHTML = `<iframe src="${url}" width="100%" height="300px" style="border:none;border-radius:8px"></iframe>`;
        } else if (ct.includes('text') || ct.includes('json') || ct.includes('xml')) {
            const resp = await fetch(url);
            const text = await resp.text();
            previewBox.innerHTML = `<pre class="code-preview">${escapeHtml(text.substring(0, 2000))}${text.length > 2000 ? '\n...(truncated)' : ''}</pre>`;
        } else {
            previewBox.innerHTML = `
                <div class="preview-no-preview">
                    <div style="font-size:2rem">${getFileIcon(doc.contentType)}</div>
                    <p>Preview not available for this file type</p>
                    <button class="btn btn-outline" style="width:auto;padding:.5rem 1rem;margin-top:.5rem" onclick="downloadDocument('${doc.id}','${escapeHtml(doc.name)}')">⬇ Download to View</button>
                </div>`;
        }
    } catch {
        previewBox.innerHTML = `<span class="preview-placeholder">Preview unavailable</span>`;
    }
}

async function previewDocument(docId) {
    const doc = state.documents.find(d => d.id === docId);
    if (!doc) return;
    selectDocument(docId);
    // Scroll to preview
    document.getElementById('selected-doc-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function downloadDocument(docId, docName) {
    const wsId = state.currentWorkspaceId;
    try {
        showToast(`Preparing download for "${docName}"...`, 'info', 2000);
        const data = await request('GET', `/workspaces/${wsId}/documents/${docId}/download-url`);
        if (!data?.data?.url) throw new Error('No download URL received');

        // Force actual file download using anchor element (not window.open which just navigates)
        const a = document.createElement('a');
        a.href = data.data.url;
        a.download = docName || 'document';
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        showToast(`Download started: "${docName}"`, 'success');
        
        // Refresh analytics after a short delay
        setTimeout(() => {
            if (document.getElementById('tab-analytics')?.classList.contains('active')) {
                loadAnalytics();
            }
        }, 1500);
    } catch (err) {
        showToast('Download failed: ' + (err.message || ''), 'error');
    }
}

async function deleteDocument(docId, docName) {
    const userRole = getCurrentWorkspaceMemberRole();
    if (userRole === 'VIEWER') {
        showToast('VIEWER role cannot delete documents. Contact an OWNER or EDITOR.', 'error');
        return;
    }
    
    if (!confirm(`Delete "${docName}"? This action can be undone within 30 days via audit recovery.`)) return;

    const wsId = state.currentWorkspaceId;
    try {
        await request('DELETE', `/workspaces/${wsId}/documents/${docId}`);
        showToast(`"${docName}" deleted`, 'info');
        state.documents = state.documents.filter(d => d.id !== docId);
        renderDocumentTable(state.documents);
        
        const panel = document.getElementById('selected-doc-panel');
        if (panel && state.selectedDoc?.id === docId) {
            panel.style.display = 'none';
            state.selectedDoc = null;
        }
    } catch (err) {
        showToast('Delete failed: ' + (err.message || ''), 'error');
    }
}

// ============================================================
// UPLOAD DOCUMENT
// ============================================================
let uploadQueue = [];

function handleDragOver(e) {
    e.preventDefault();
    document.getElementById('drop-zone')?.classList.add('drag-over');
}

function handleDragLeave() {
    document.getElementById('drop-zone')?.classList.remove('drag-over');
}

function handleDrop(e) {
    e.preventDefault();
    document.getElementById('drop-zone')?.classList.remove('drag-over');
    const files = Array.from(e.dataTransfer.files);
    if (files.length) addFilesToQueue(files);
}

document.addEventListener('DOMContentLoaded', () => {
    const fileInput = document.getElementById('doc-file');
    const folderInput = document.getElementById('doc-folder');
    if (fileInput) fileInput.addEventListener('change', () => addFilesToQueue(Array.from(fileInput.files)));
    if (folderInput) folderInput.addEventListener('change', () => addFilesToQueue(Array.from(folderInput.files)));
});

function addFilesToQueue(files) {
    uploadQueue = [...uploadQueue, ...files];
    renderFilePreviewList();
    
    // Auto-fill name if single file
    if (files.length === 1 && !document.getElementById('doc-name').value) {
        document.getElementById('doc-name').value = files[0].name.replace(/\.[^.]+$/, '');
    }
}

function renderFilePreviewList() {
    const list = document.getElementById('file-preview-list');
    if (!list) return;
    if (uploadQueue.length === 0) { list.innerHTML = ''; return; }
    
    list.innerHTML = uploadQueue.map((f, i) => `
        <div class="file-preview-item">
            <span>${getFileIcon(f.type)} ${escapeHtml(f.name)}</span>
            <span style="color:var(--text-secondary);font-size:.8rem">${formatBytes(f.size)}</span>
            <button type="button" onclick="removeFromQueue(${i})" style="background:none;border:none;color:var(--danger);cursor:pointer;padding:0 .5rem">✕</button>
        </div>
    `).join('');
}

function removeFromQueue(index) {
    uploadQueue.splice(index, 1);
    renderFilePreviewList();
}

async function handleUploadDocument(event) {
    event.preventDefault();
    if (uploadQueue.length === 0) {
        showToast('Please select at least one file', 'warning');
        return;
    }

    const wsId = state.currentWorkspaceId;
    const name = document.getElementById('doc-name').value.trim();
    const description = document.getElementById('doc-desc').value.trim();
    const tagsRaw = document.getElementById('doc-tags').value.trim();
    const tags = tagsRaw ? tagsRaw.split(',').map(t => t.trim()).filter(Boolean) : [];

    const progressBar = document.getElementById('upload-progress-bar');
    const progressFill = document.getElementById('upload-progress-fill');
    const progressText = document.getElementById('upload-progress-text');
    if (progressBar) progressBar.style.display = 'block';

    let uploaded = 0;
    for (const file of uploadQueue) {
        try {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('name', uploadQueue.length === 1 ? name : file.name);
            formData.append('description', description);
            if (tags.length) formData.append('tags', tags.join(','));

            const headers = {};
            if (state.accessToken) headers['Authorization'] = `Bearer ${state.accessToken}`;
            
            const xhr = new XMLHttpRequest();
            await new Promise((resolve, reject) => {
                xhr.upload.onprogress = (e) => {
                    if (e.lengthComputable) {
                        const pct = Math.round((uploaded / uploadQueue.length + (e.loaded / e.total) / uploadQueue.length) * 100);
                        if (progressFill) progressFill.style.width = `${pct}%`;
                        if (progressText) progressText.textContent = `${pct}%`;
                    }
                };
                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 300) resolve();
                    else reject(new Error(`Upload failed: ${xhr.status}`));
                };
                xhr.onerror = () => reject(new Error('Upload error'));
                xhr.open('POST', `${API_BASE}/workspaces/${wsId}/documents/upload`);
                Object.entries(headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
                xhr.send(formData);
            });
            uploaded++;
        } catch (err) {
            showToast(`Failed to upload "${file.name}": ${err.message}`, 'error');
        }
    }

    if (progressBar) progressBar.style.display = 'none';
    if (progressFill) progressFill.style.width = '0%';
    
    uploadQueue = [];
    renderFilePreviewList();
    document.getElementById('form-upload-doc').reset();
    closeModal('modal-upload');
    
    showToast(`${uploaded} document(s) uploaded successfully!`, 'success');
    loadWorkspaceDocuments(wsId);
}

// ============================================================
// WATERMARK PROTECTION
// ============================================================
function renderWatermarkCanvas(wsName) {
    const wsId = state.currentWorkspaceId;
    const storedPw = localStorage.getItem(`ws_pw_${wsId}`);
    
    if (!storedPw) {
        // No password set, show without watermark
        const overlay = document.getElementById('watermark-overlay');
        if (overlay) overlay.style.display = 'none';
        return;
    }
    
    if (!state.currentWorkspacePasswordVerified) {
        openModal('modal-watermark-pw');
        renderWatermarkPattern(wsName);
        const overlay = document.getElementById('watermark-overlay');
        if (overlay) overlay.style.display = 'flex';
    }
}

function renderWatermarkPattern(wsName) {
    const canvas = document.getElementById('watermark-canvas');
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    
    ctx.fillStyle = 'rgba(0,0,0,0.7)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    
    ctx.font = '20px Inter, sans-serif';
    ctx.fillStyle = 'rgba(255,255,255,0.08)';
    ctx.textAlign = 'center';
    
    const text = `CONFIDENTIAL — ${wsName} — ${state.user?.email || ''} — ${new Date().toLocaleDateString()}`;
    
    for (let y = 0; y < canvas.height; y += 100) {
        for (let x = 0; x < canvas.width; x += 400) {
            ctx.save();
            ctx.translate(x, y);
            ctx.rotate(-Math.PI / 6);
            ctx.fillText(text, 0, 0);
            ctx.restore();
        }
    }
    
    // Central lock icon
    ctx.font = '48px sans-serif';
    ctx.fillStyle = 'rgba(255,255,255,0.5)';
    ctx.textAlign = 'center';
    ctx.fillText('🔐', canvas.width / 2, canvas.height / 2 - 30);
    ctx.font = '18px Inter, sans-serif';
    ctx.fillStyle = 'rgba(255,255,255,0.6)';
    ctx.fillText('This workspace is password-protected', canvas.width / 2, canvas.height / 2 + 20);
}

function verifyWatermarkPassword() {
    const wsId = state.currentWorkspaceId;
    const entered = document.getElementById('watermark-pw-input')?.value;
    const stored = localStorage.getItem(`ws_pw_${wsId}`);
    
    if (!stored || btoa(entered) === stored) {
        state.currentWorkspacePasswordVerified = true;
        const overlay = document.getElementById('watermark-overlay');
        if (overlay) overlay.style.display = 'none';
        closeModal('modal-watermark-pw');
        showToast('Workspace unlocked', 'success');
    } else {
        showToast('Incorrect password', 'error');
        document.getElementById('watermark-pw-input').value = '';
    }
}

// ============================================================
// WORKSPACE MEMBERS
// ============================================================
async function loadWorkspaceMembers(wsId) {
    try {
        const data = await request('GET', `/workspaces/${wsId}/members`);
        if (!data) return;
        const members = data.data || [];
        window._workspaceMembers = members;
        renderMembersList(members);
        
        // Find and display current user's role
        const myMembership = members.find(m => m.userId === state.user?.userId);
        const roleBadge = document.getElementById('ws-member-role-badge');
        if (roleBadge && myMembership) {
            roleBadge.innerHTML = `<span class="permission-badge permission-${(myMembership.role||'').toLowerCase()}">Your Role: ${myMembership.role}</span>`;
        }
        
        // Re-render documents to apply role-based action visibility
        renderDocumentTable(state.documents);
    } catch {}
}

function renderMembersList(members) {
    const list = document.getElementById('workspace-members-list');
    if (!list) return;
    
    list.innerHTML = members.map(m => `
        <li class="member-item">
            <div class="member-avatar">${(m.email || m.userId || 'U')[0].toUpperCase()}</div>
            <div class="member-info">
                <span class="member-email">${escapeHtml(m.email || m.userId?.substring(0,8) || 'Unknown')}</span>
                <span class="permission-badge permission-${(m.role||'viewer').toLowerCase()}">${m.role}</span>
            </div>
        </li>
    `).join('');
}

async function handleAddMember(event) {
    event.preventDefault();
    const wsId = state.currentWorkspaceId;
    const userId = document.getElementById('member-user-id').value.trim();
    const role = document.querySelector('input[name="member-role"]:checked')?.value || 'VIEWER';

    try {
        await request('POST', `/workspaces/${wsId}/members`, { userId, role });
        closeModal('modal-member');
        document.getElementById('form-add-member').reset();
        showToast('Member invited successfully!', 'success');
        loadWorkspaceMembers(wsId);
    } catch (err) {
        showToast(err.message || 'Failed to add member', 'error');
    }
}

// ============================================================
// FILE EDITOR
// ============================================================
let currentEditorTab = 'code';
let monacoInstance = null;
let quillInstance = null;

function openFileEditor() {
    if (!state.selectedDoc) return;
    
    const doc = state.selectedDoc;
    const nameEl = document.getElementById('editor-doc-name');
    if (nameEl) nameEl.textContent = doc.name;
    
    openModal('modal-file-editor');
    
    // Load Monaco
    initMonacoEditor(doc);
}

function initMonacoEditor(doc) {
    require.config({ paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.44.0/min/vs' } });
    require(['vs/editor/editor.main'], () => {
        const container = document.getElementById('monaco-editor-container');
        if (!container) return;
        
        if (monacoInstance) monacoInstance.dispose();
        
        const language = getMonacoLanguage(doc.contentType, doc.name);
        monacoInstance = monaco.editor.create(container, {
            value: '// Loading content...',
            language,
            theme: 'vs-dark',
            fontSize: 14,
            fontFamily: "'Fira Code', monospace",
            minimap: { enabled: false },
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
            wordWrap: 'on',
            automaticLayout: true,
        });
    });
    
    // Init Quill if not done
    if (!quillInstance) {
        const el = document.getElementById('quill-editor');
        if (el) {
            quillInstance = new Quill('#quill-editor', {
                theme: 'snow',
                placeholder: 'Start typing...',
                modules: { toolbar: true }
            });
        }
    }
}

function switchEditorTab(tab) {
    currentEditorTab = tab;
    document.querySelectorAll('.editor-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.editor-pane').forEach(p => p.style.display = 'none');
    
    const btn = document.querySelector(`.editor-tab[onclick*="${tab}"]`);
    if (btn) btn.classList.add('active');
    
    const pane = document.getElementById(`editor-${tab}`);
    if (pane) pane.style.display = 'flex';
    
    if (tab === 'code' && monacoInstance) monacoInstance.layout();
}

function getMonacoLanguage(contentType, filename) {
    const ext = (filename || '').split('.').pop()?.toLowerCase();
    const map = {
        'js': 'javascript', 'ts': 'typescript', 'json': 'json', 'html': 'html',
        'css': 'css', 'java': 'java', 'py': 'python', 'md': 'markdown',
        'xml': 'xml', 'yaml': 'yaml', 'yml': 'yaml', 'sql': 'sql',
        'sh': 'shell', 'c': 'c', 'cpp': 'cpp', 'rs': 'rust', 'go': 'go'
    };
    if (map[ext]) return map[ext];
    if ((contentType || '').includes('json')) return 'json';
    if ((contentType || '').includes('xml')) return 'xml';
    if ((contentType || '').includes('html')) return 'html';
    return 'plaintext';
}

async function saveEditorContent() {
    showToast('Saving... (editor integration with save endpoint in progress)', 'info');
    closeModal('modal-file-editor');
}

// ============================================================
// AI SUMMARY
// ============================================================
async function triggerAiSummary() {
    if (!state.selectedDoc) return;
    const wsId = state.currentWorkspaceId;
    const docId = state.selectedDoc.id;
    const summaryBox = document.getElementById('ai-summary-box');
    const btn = document.getElementById('btn-generate-summary');
    
    if (summaryBox) summaryBox.innerHTML = '<div class="ai-loading"><div class="ai-dots"><span></span><span></span><span></span></div> Generating AI summary...</div>';
    if (btn) btn.disabled = true;

    try {
        const data = await request('POST', `/workspaces/${wsId}/documents/${docId}/ai-summary`, {});
        if (data?.data?.summary) {
            if (summaryBox) summaryBox.textContent = data.data.summary;
        } else {
            if (summaryBox) summaryBox.textContent = 'AI summary generated. Check document metadata.';
        }
        showToast('AI summary generated!', 'success');
    } catch (err) {
        if (summaryBox) summaryBox.textContent = 'AI summary unavailable: ' + (err.message || 'Service error');
        showToast('AI analysis failed: ' + (err.message || ''), 'error');
    } finally {
        if (btn) btn.disabled = false;
    }
}

// ============================================================
// AI CHATBOT
// ============================================================
async function sendChatMessage() {
    const input = document.getElementById('chat-input');
    if (!input || !input.value.trim()) return;
    
    const message = input.value.trim();
    input.value = '';
    
    appendChatBubble('user', message);
    appendChatBubble('ai', '...', true); // typing indicator

    const context = state.selectedDoc 
        ? `Current document: ${state.selectedDoc.name}. Workspace: ${document.getElementById('active-ws-name')?.textContent || ''}.`
        : `Workspace: ${document.getElementById('active-ws-name')?.textContent || ''}`;

    try {
        const data = await request('POST', `/ai/explain`, {
            content: message,
            context,
            model: 'compliance-assistant'
        });
        
        // Remove typing indicator
        const typing = document.querySelector('.chat-bubble.typing');
        if (typing) typing.parentElement.remove();
        
        const reply = data?.data?.explanation || data?.data?.response || 'Unable to generate response.';
        appendChatBubble('ai', reply);
    } catch (err) {
        const typing = document.querySelector('.chat-bubble.typing');
        if (typing) typing.parentElement.remove();
        appendChatBubble('ai', 'AI service unavailable: ' + (err.message || ''));
    }
}

function appendChatBubble(role, content, isTyping = false) {
    const container = document.getElementById('chat-messages');
    if (!container) return;
    
    const div = document.createElement('div');
    div.className = `chat-message ${role}`;
    div.innerHTML = `
        <div class="chat-bubble ${isTyping ? 'typing' : ''}">${isTyping ? '<span class="dot"></span><span class="dot"></span><span class="dot"></span>' : escapeHtml(content)}</div>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function handleChatKeydown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendChatMessage();
    }
}

// ============================================================
// WORKSPACE CHAT (Team Chat)
// ============================================================
let chatSubscription = null;

function sendWorkspaceChat() {
    const input = document.getElementById('workspace-chat-input');
    if (!input || !input.value.trim()) return;
    
    const message = input.value.trim();
    input.value = '';
    
    if (!state.stompClient?.connected) {
        showToast('Not connected to chat server', 'error');
        return;
    }
    
    state.stompClient.send(`/app/chat/${state.currentWorkspaceId}`, {}, JSON.stringify({
        userId: state.user?.userId,
        userName: state.user?.fullName,
        message,
        timestamp: new Date().toISOString()
    }));
}

function appendWorkspaceChatMessage(msg) {
    const container = document.getElementById('workspace-chat-messages');
    if (!container) return;
    
    const isMe = msg.userId === state.user?.userId;
    const div = document.createElement('div');
    div.className = `wc-message ${isMe ? 'wc-mine' : 'wc-other'}`;
    div.innerHTML = `
        ${!isMe ? `<span class="wc-name">${escapeHtml(msg.userName || 'User')}</span>` : ''}
        <div class="wc-bubble">${escapeHtml(msg.message)}</div>
        <span class="wc-time">${new Date(msg.timestamp).toLocaleTimeString()}</span>
    `;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function toggleChatExpand() {
    const panel = document.querySelector('.workspace-chat-panel');
    if (panel) panel.classList.toggle('expanded');
}

// ============================================================
// WEBSOCKET
// ============================================================
function connectWebSocket() {
    const socket = new SockJS('/ws');
    state.stompClient = Stomp.over(socket);
    state.stompClient.debug = null;
    state.stompClient.connect(
        { Authorization: `Bearer ${state.accessToken}` },
        () => {
            if (state.currentWorkspaceId) connectWorkspaceWebSocket(state.currentWorkspaceId);
        },
        () => {
            setTimeout(connectWebSocket, 5000);
        }
    );
}

function connectWorkspaceWebSocket(wsId) {
    if (!state.stompClient?.connected) return;
    
    // Subscribe to presence
    state.stompClient.subscribe(`/topic/presence/${wsId}`, (msg) => {
        try {
            const users = JSON.parse(msg.body);
            renderPresenceList(users);
        } catch {}
    });
    
    // Subscribe to document events (real-time updates)
    state.stompClient.subscribe(`/topic/workspace/${wsId}/documents`, (msg) => {
        try {
            const event = JSON.parse(msg.body);
            if (event.type === 'DOCUMENT_UPLOADED' || event.type === 'DOCUMENT_DELETED') {
                loadWorkspaceDocuments(wsId);
            }
        } catch {}
    });
    
    // Subscribe to workspace chat
    if (chatSubscription) { try { chatSubscription.unsubscribe(); } catch {} }
    chatSubscription = state.stompClient.subscribe(`/topic/chat/${wsId}`, (msg) => {
        try {
            const chatMsg = JSON.parse(msg.body);
            appendWorkspaceChatMessage(chatMsg);
        } catch {}
    });
    
    // Join presence
    state.stompClient.send('/app/presence/join', {}, JSON.stringify({
        workspaceId: wsId,
        userId: state.user?.userId,
        userName: state.user?.fullName
    }));
}

function renderPresenceList(users) {
    const list = document.getElementById('presence-user-list');
    if (!list) return;
    
    if (!Array.isArray(users) || users.length === 0) {
        list.innerHTML = '<li class="presence-empty">No users online</li>';
        return;
    }
    
    list.innerHTML = users.map(u => `
        <li class="presence-item">
            <div class="presence-avatar">${(u.userName || u.userId || 'U')[0].toUpperCase()}</div>
            <span class="presence-name">${escapeHtml(u.userName || u.userId?.substring(0,8) || 'Unknown')}</span>
            <span class="presence-dot"></span>
        </li>
    `).join('');
}

// ============================================================
// ANALYTICS
// ============================================================
async function loadAnalytics() {
    const wsId = state.currentWorkspaceId;
    if (!wsId) {
        // Load global stats if no workspace selected
        showToast('Select a workspace first for detailed analytics', 'info', 2000);
        renderEmptyAnalytics();
        return;
    }

    try {
        const data = await request('GET', `/analytics/workspace/${wsId}`);
        if (!data?.data) { renderEmptyAnalytics(); return; }
        
        const stats = data.data;
        
        const el = (id, val) => {
            const e = document.getElementById(id);
            if (e) e.textContent = val ?? '-';
        };
        
        el('stats-total-docs', stats.totalDocuments ?? '-');
        el('stats-total-downloads', stats.totalDownloads ?? '-');
        el('stats-active-users', stats.activeUsers ?? '-');
        el('stats-storage', formatBytes(stats.totalStorageBytes || 0));
        
        renderTimelineChart(stats.dailyActivity || []);
        renderHeatmapChart(stats.downloadByDocument || []);
    } catch (err) {
        showToast('Analytics error: ' + (err.message || ''), 'error');
        renderEmptyAnalytics();
    }
}

function renderEmptyAnalytics() {
    ['stats-total-docs','stats-total-downloads','stats-active-users','stats-storage'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.textContent = '-';
    });
}

function renderTimelineChart(data) {
    const ctx = document.getElementById('chart-timeline');
    if (!ctx) return;
    if (state.timelineChart) state.timelineChart.destroy();

    const labels = data.map(d => d.date || d.day || '');
    const uploads = data.map(d => d.uploads || d.upload_count || 0);
    const downloads = data.map(d => d.downloads || d.download_count || 0);

    state.timelineChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [
                {
                    label: 'Uploads',
                    data: uploads,
                    borderColor: '#10b981',
                    backgroundColor: 'rgba(16,185,129,0.1)',
                    fill: true,
                    tension: 0.4,
                    borderWidth: 2
                },
                {
                    label: 'Downloads',
                    data: downloads,
                    borderColor: '#3b82f6',
                    backgroundColor: 'rgba(59,130,246,0.1)',
                    fill: true,
                    tension: 0.4,
                    borderWidth: 2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: '#f0f9ff' } }
            },
            scales: {
                x: { ticks: { color: '#94a3b8' }, grid: { color: 'rgba(255,255,255,0.05)' } },
                y: { ticks: { color: '#94a3b8' }, grid: { color: 'rgba(255,255,255,0.05)' }, beginAtZero: true }
            }
        }
    });
}

function renderHeatmapChart(data) {
    const ctx = document.getElementById('chart-heatmap');
    if (!ctx) return;
    if (state.heatmapChart) state.heatmapChart.destroy();

    const labels = data.map(d => (d.documentName || d.name || 'Doc').substring(0, 20));
    const counts = data.map(d => d.downloadCount || d.count || 0);

    state.heatmapChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Downloads',
                data: counts,
                backgroundColor: counts.map((c, i) => {
                    const colors = ['rgba(16,185,129,0.7)', 'rgba(59,130,246,0.7)', 'rgba(139,92,246,0.7)', 'rgba(236,72,153,0.7)', 'rgba(6,182,212,0.7)'];
                    return colors[i % colors.length];
                }),
                borderRadius: 8,
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            indexAxis: 'y',
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: { ticks: { color: '#94a3b8' }, grid: { color: 'rgba(255,255,255,0.05)' }, beginAtZero: true },
                y: { ticks: { color: '#94a3b8' }, grid: { color: 'rgba(255,255,255,0.05)' } }
            }
        }
    });
}

// ============================================================
// AUDIT LOGS
// ============================================================
async function loadAuditLogs(anomalyOnly) {
    state.logsAnomalyOnly = anomalyOnly;
    const wsId = state.currentWorkspaceId;
    if (!wsId) {
        showToast('Select a workspace to view its audit logs', 'info', 2000);
        return;
    }

    try {
        let path = `/audit/logs?workspaceId=${wsId}&page=${state.logsPage}&size=20`;
        if (anomalyOnly) path += '&anomalyOnly=true';
        
        const data = await request('GET', path);
        if (!data) return;
        
        const logs = data.data?.content || [];
        const total = data.data?.totalElements || 0;
        const totalPages = data.data?.totalPages || 1;
        
        renderLogsTable(logs);
        
        const pageInfo = document.getElementById('logs-page-info');
        if (pageInfo) pageInfo.textContent = `Page ${state.logsPage + 1} of ${totalPages} (${total} entries)`;
        
        const prevBtn = document.getElementById('btn-logs-prev');
        const nextBtn = document.getElementById('btn-logs-next');
        if (prevBtn) prevBtn.disabled = state.logsPage <= 0;
        if (nextBtn) nextBtn.disabled = state.logsPage >= totalPages - 1;
    } catch (err) {
        showToast('Failed to load audit logs: ' + (err.message || ''), 'error');
    }
}

function renderLogsTable(logs) {
    const tbody = document.getElementById('logs-list-body');
    if (!tbody) return;

    if (logs.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;color:var(--text-secondary);padding:2rem">No audit logs found</td></tr>`;
        return;
    }

    tbody.innerHTML = logs.map(log => {
        const typeClass = getEventTypeClass(log.eventType);
        const typeLabel = log.eventType?.replace('_', ' ') || '-';
        const isAnomaly = log.anomalyFlag === true;
        
        return `<tr class="${isAnomaly ? 'log-anomaly' : ''}">
            <td><span class="event-badge ${typeClass}">${typeLabel}</span></td>
            <td class="mono-cell">${shortId(log.actorId)}</td>
            <td>${escapeHtml(log.resourceType || '-')}</td>
            <td class="mono-cell">${shortId(log.resourceId)}</td>
            <td>${isAnomaly ? '<span class="anomaly-badge">⚠ ANOMALY</span>' : '<span class="ok-badge">✓ OK</span>'}</td>
            <td>${formatDateTime(log.timestamp)}</td>
            <td><button class="action-btn" onclick="explainAnomalyLog('${escapeHtml(JSON.stringify(log))}')">🤖 Explain</button></td>
        </tr>`;
    }).join('');
}

function getEventTypeClass(eventType) {
    const map = {
        'DOCUMENT_UPLOADED': 'event-upload',
        'DOCUMENT_DOWNLOADED': 'event-download',
        'DOCUMENT_DELETED': 'event-delete',
        'USER_LOGIN': 'event-login',
        'USER_REGISTERED': 'event-login',
        'LOGIN_FAILED': 'event-warning',
        'ACCOUNT_LOCKED': 'event-danger',
        'WORKSPACE_CREATED': 'event-upload',
        'MEMBER_ADDED': 'event-info'
    };
    return map[eventType] || 'event-info';
}

function paginateLogs(direction) {
    state.logsPage = Math.max(0, state.logsPage + direction);
    loadAuditLogs(state.logsAnomalyOnly);
}

async function explainAnomalyLog(logJson) {
    openModal('modal-ai-explain');
    const textEl = document.getElementById('ai-explanation-text');
    if (textEl) textEl.textContent = 'Analyzing event with AI...';
    
    try {
        let log;
        try { log = JSON.parse(logJson); } catch { log = {}; }
        
        const data = await request('POST', '/ai/explain', {
            content: `Explain this security audit event: ${JSON.stringify(log)}`,
            context: 'Security audit event analysis'
        });
        
        if (textEl) textEl.textContent = data?.data?.explanation || data?.data?.response || 'No explanation generated.';
    } catch (err) {
        if (textEl) textEl.textContent = 'AI analysis unavailable: ' + (err.message || '');
    }
}

// ============================================================
// 3D CARD TILT EFFECT
// ============================================================
function initCardTilt() {
    document.querySelectorAll('.card-3d').forEach(card => {
        card.addEventListener('mousemove', e => {
            const rect = card.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            const centerX = rect.width / 2;
            const centerY = rect.height / 2;
            const rotateX = (y - centerY) / centerY * -8;
            const rotateY = (x - centerX) / centerX * 8;
            card.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale3d(1.02,1.02,1.02)`;
        });
        card.addEventListener('mouseleave', () => {
            card.style.transform = '';
        });
    });
}

// ============================================================
// THREE.JS PARTICLE BACKGROUND
// ============================================================
function initParticleBackground() {
    const canvas = document.getElementById('particle-canvas');
    if (!canvas || !window.THREE) return;
    
    const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(75, window.innerWidth / window.innerHeight, 0.1, 1000);
    camera.position.z = 5;
    
    // Create particles
    const count = 1200;
    const positions = new Float32Array(count * 3);
    for (let i = 0; i < count * 3; i++) {
        positions[i] = (Math.random() - 0.5) * 20;
    }
    
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    
    const material = new THREE.PointsMaterial({
        color: 0x10b981,
        size: 0.04,
        transparent: true,
        opacity: 0.6,
        sizeAttenuation: true
    });
    
    const particles = new THREE.Points(geometry, material);
    scene.add(particles);
    
    let animId;
    function animate() {
        animId = requestAnimationFrame(animate);
        particles.rotation.y += 0.0003;
        particles.rotation.x += 0.0001;
        renderer.render(scene, camera);
    }
    animate();
    
    window.addEventListener('resize', () => {
        camera.aspect = window.innerWidth / window.innerHeight;
        camera.updateProjectionMatrix();
        renderer.setSize(window.innerWidth, window.innerHeight);
    });
}

// ============================================================
// HELPERS
// ============================================================
function escapeHtml(str) {
    if (typeof str !== 'string') return String(str || '');
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

function formatBytes(bytes) {
    if (!bytes) return '0 B';
    const units = ['B','KB','MB','GB','TB'];
    let i = 0;
    let b = Number(bytes);
    while (b >= 1024 && i < units.length - 1) { b /= 1024; i++; }
    return `${b.toFixed(1)} ${units[i]}`;
}

function formatDate(dateStr) {
    if (!dateStr) return '-';
    try { return new Date(dateStr).toLocaleDateString(); } catch { return dateStr; }
}

function formatDateTime(dateStr) {
    if (!dateStr) return '-';
    try { return new Date(dateStr).toLocaleString(); } catch { return dateStr; }
}

function shortId(id) {
    if (!id) return '-';
    return id.length > 8 ? id.substring(0, 8) + '...' : id;
}

function getFileIcon(contentType) {
    const ct = (contentType || '').toLowerCase();
    if (ct.includes('pdf')) return '📄';
    if (ct.includes('image')) return '🖼️';
    if (ct.includes('video')) return '🎥';
    if (ct.includes('audio')) return '🎵';
    if (ct.includes('zip') || ct.includes('compressed')) return '📦';
    if (ct.includes('spreadsheet') || ct.includes('excel') || ct.includes('csv')) return '📊';
    if (ct.includes('word') || ct.includes('document')) return '📝';
    if (ct.includes('presentation')) return '📊';
    if (ct.includes('text') || ct.includes('json') || ct.includes('xml')) return '📋';
    return '📎';
}

function getFileExtension(contentType) {
    const ct = (contentType || '').toLowerCase();
    if (ct.includes('pdf')) return 'PDF';
    if (ct.includes('jpeg') || ct.includes('jpg')) return 'JPG';
    if (ct.includes('png')) return 'PNG';
    if (ct.includes('zip')) return 'ZIP';
    if (ct.includes('excel') || ct.includes('spreadsheet')) return 'XLSX';
    if (ct.includes('word')) return 'DOCX';
    if (ct.includes('json')) return 'JSON';
    if (ct.includes('xml')) return 'XML';
    if (ct.includes('text/plain')) return 'TXT';
    return ct.split('/')[1]?.toUpperCase() || 'FILE';
}

// ============================================================
// BOOTSTRAP
// ============================================================
window.addEventListener('DOMContentLoaded', () => {
    // Close all modals and hide watermark overlay on load to prevent cached DOM states
    document.querySelectorAll('.modal-overlay').forEach(m => m.classList.remove('active'));
    const watermark = document.getElementById('watermark-overlay');
    if (watermark) watermark.style.display = 'none';

    // Init particle background
    initParticleBackground();
    
    // Try to restore session
    loadPersistedTokens();
    
    if (state.accessToken && state.user) {
        showScreen('screen-app');
        initApp();
    } else {
        showScreen('screen-login');
    }
    
    // Show register step 1 by default
    showRegisterStep(1);
    
    // Global keyboard shortcuts
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            document.querySelectorAll('.modal-overlay.active').forEach(m => m.classList.remove('active'));
        }
    });
    
    showToast('IVDR Portal loaded', 'info', 2000);
});
