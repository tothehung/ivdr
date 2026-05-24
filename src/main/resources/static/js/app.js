// ==========================================
// IVDR Dashboard Core Logic
// ==========================================

const API_BASE = "/api/v1";
let authState = {
    accessToken: localStorage.getItem("accessToken"),
    refreshToken: localStorage.getItem("refreshToken"),
    user: JSON.parse(localStorage.getItem("user") || "null")
};

// WebSocket state variables
let stompClient = null;
let activeWorkspaceId = null;
let activeDocumentId = null;
let presenceSubscriptions = [];
let heartbeatTimer = null;

// Pagination logs state
let logsPage = 0;
let logsPageSize = 10;
let logsAnomaliesOnly = false;

// Visual Analytics Chart references
let timelineChart = null;
let heatmapChart = null;

// ==========================================
// Screen Navigation
// ==========================================

function showScreen(screenId) {
    document.querySelectorAll(".auth-container, .app-layout").forEach(el => {
        el.classList.remove("active");
        el.style.display = "none";
    });
    const activeScreen = document.getElementById(screenId);
    activeScreen.style.display = (screenId === "screen-app") ? "grid" : "flex";
    // Trigger transition
    setTimeout(() => activeScreen.classList.add("active"), 50);
}

function navigateTab(tabId) {
    document.querySelectorAll(".main-content > .view-section").forEach(el => {
        el.classList.remove("active");
    });
    document.getElementById(tabId).classList.add("active");

    // Sidebar items sync
    document.querySelectorAll(".nav-item").forEach(el => {
        el.classList.remove("active");
    });
    
    if (tabId === "tab-workspaces" || tabId === "tab-workspace-detail") {
        document.querySelector(".nav-menu li:first-child").classList.add("active");
    } else if (tabId === "tab-analytics") {
        document.getElementById("nav-analytics").classList.add("active");
        loadAnalytics();
    } else if (tabId === "tab-logs") {
        document.getElementById("nav-logs").classList.add("active");
        loadAuditLogs(false);
    }

    // Stop document viewing when leaving workspace details
    if (tabId !== "tab-workspace-detail" && activeWorkspaceId) {
        leaveWorkspaceWebSocket();
    }
}

// Modals controller
function openModal(modalId) {
    document.getElementById(modalId).classList.add("active");
}
function closeModal(modalId) {
    document.getElementById(modalId).classList.remove("active");
}

// ==========================================
// Toast Notifications
// ==========================================

function showToast(title, message, type = "info") {
    const container = document.getElementById("toast-container");
    const toast = document.createElement("div");
    toast.className = `toast toast-${type}`;
    toast.innerHTML = `
        <div class="toast-header">
            <span>${title}</span>
            <span style="cursor:pointer;" onclick="this.parentElement.parentElement.remove()">&times;</span>
        </div>
        <div class="toast-body">${message}</div>
    `;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 6000);
}

// ==========================================
// API REST Request Wrapper (with JWT auto-refresh)
// ==========================================

async function request(path, options = {}) {
    if (!options.headers) options.headers = {};
    if (authState.accessToken) {
        options.headers["Authorization"] = `Bearer ${authState.accessToken}`;
    }
    if (!(options.body instanceof FormData) && !options.headers["Content-Type"]) {
        options.headers["Content-Type"] = "application/json";
    }

    let response = await fetch(API_BASE + path, options);

    // If unauthorized, attempt token refresh once
    if (response.status === 401 && authState.refreshToken) {
        console.warn("Access token expired, attempting rotation...");
        const refreshed = await rotateTokens();
        if (refreshed) {
            options.headers["Authorization"] = `Bearer ${authState.accessToken}`;
            response = await fetch(API_BASE + path, options);
        } else {
            handleLogout();
            throw new Error("Session expired. Please log in again.");
        }
    }

    if (response.status === 204) {
        return null;
    }

    const json = await response.json();
    if (!response.ok) {
        throw new Error(json.message || "An unexpected API error occurred.");
    }
    return json.data;
}

async function rotateTokens() {
    try {
        const response = await fetch(API_BASE + "/auth/refresh", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken: authState.refreshToken })
        });
        if (!response.ok) return false;
        
        const data = (await response.json()).data;
        saveAuth(data);
        return true;
    } catch (e) {
        return false;
    }
}

function saveAuth(data) {
    authState.accessToken = data.accessToken;
    authState.refreshToken = data.refreshToken;
    authState.user = data.user;
    localStorage.setItem("accessToken", data.accessToken);
    localStorage.setItem("refreshToken", data.refreshToken);
    localStorage.setItem("user", JSON.stringify(data.user));
}

// ==========================================
// Authentication Handlers
// ==========================================

async function handleRegister(e) {
    e.preventDefault();
    const organizationName = document.getElementById("reg-org").value;
    const fullName = document.getElementById("reg-name").value;
    const email = document.getElementById("reg-email").value;
    const password = document.getElementById("reg-pass").value;

    try {
        const response = await fetch(API_BASE + "/auth/register", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ organizationName, fullName, email, password })
        });
        const json = await response.json();
        if (!response.ok) throw new Error(json.message);
        
        saveAuth(json.data);
        showToast("Success", "Tenant Organization created successfully!", "success");
        initApp();
    } catch (err) {
        showToast("Registration Error", err.message, "danger");
    }
}

async function handleLogin(e) {
    e.preventDefault();
    const email = document.getElementById("login-email").value;
    const password = document.getElementById("login-pass").value;

    try {
        const response = await fetch(API_BASE + "/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email, password })
        });
        const json = await response.json();
        if (!response.ok) throw new Error(json.message);
        
        saveAuth(json.data);
        showToast("Success", "Authenticated successfully!", "success");
        initApp();
    } catch (err) {
        showToast("Access Denied", err.message, "danger");
    }
}

function handleLogout() {
    if (authState.refreshToken) {
        fetch(API_BASE + "/auth/logout", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken: authState.refreshToken })
        }).catch(() => {});
    }
    
    // Revoke local tokens
    authState = { accessToken: null, refreshToken: null, user: null };
    localStorage.clear();
    disconnectWebSocket();
    showScreen("screen-login");
}

// ==========================================
// WebSocket Connection and Event Handling
// ==========================================

function connectWebSocket() {
    if (stompClient && stompClient.connected) return;

    // Connect to /ws handshake endpoint with SockJS fallback
    const socket = new SockJS(API_BASE + "/ws");
    stompClient = Stomp.over(socket);
    
    // Disable console logging overhead in production
    stompClient.debug = null;

    stompClient.connect(
        { "Authorization": `Bearer ${authState.accessToken}` },
        function (frame) {
            console.log("WebSocket connected!");
            
            // Subscribe to personal anomaly alerts queue
            stompClient.subscribe("/user/queue/alerts", function (message) {
                const alert = JSON.parse(message.body);
                showToast(`🚨 Security Alert: ${alert.alertType}`, alert.description, "danger");
            });

            // Re-join active workspace if navigating back/session reconnected
            if (activeWorkspaceId) {
                joinWorkspaceWebSocket(activeWorkspaceId);
            }
        },
        function (error) {
            console.error("STOMP connection error: ", error);
            // Reconnect after 5 seconds
            setTimeout(connectWebSocket, 5000);
        }
    );
}

function disconnectWebSocket() {
    leaveWorkspaceWebSocket();
    if (stompClient) {
        stompClient.disconnect();
        stompClient = null;
    }
}

function joinWorkspaceWebSocket(workspaceId) {
    if (!stompClient || !stompClient.connected) return;

    activeWorkspaceId = workspaceId;

    // 1. Subscribe to presence updates broadcast channel
    const sub = stompClient.subscribe(`/topic/presence/${workspaceId}`, function (message) {
        const event = JSON.parse(message.body);
        
        if (event.type === "JOIN") {
            showToast("Collaborator Entered", `User ${event.userId.substring(0,8)} joined the Deal Room.`, "info");
        } else if (event.type === "LEAVE") {
            showToast("Collaborator Left", `User ${event.userId.substring(0,8)} disconnected.`, "info");
        } else if (event.type === "DOWNLOAD") {
            showToast("Document Downloaded", `User ${event.userId.substring(0,8)} downloaded document ${event.documentId.substring(0,8)}.`, "success");
        }
        
        // Refresh connected list on any state change
        loadPresenceList(workspaceId);
    });
    
    presenceSubscriptions.push(sub);

    // 2. Publish JOIN event
    stompClient.send(`/app/workspace/${workspaceId}/join`, {}, {});

    // 3. Start Heartbeats (every 15s)
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => {
        if (stompClient && stompClient.connected) {
            stompClient.send(`/app/workspace/${workspaceId}/heartbeat`, {}, {});
        }
    }, 15000);

    // Refresh connected list immediately
    loadPresenceList(workspaceId);
}

function leaveWorkspaceWebSocket() {
    if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }

    if (stompClient && stompClient.connected && activeWorkspaceId) {
        stompClient.send(`/app/workspace/${activeWorkspaceId}/leave`, {}, {});
    }

    // Cancel active subscriptions
    presenceSubscriptions.forEach(sub => sub.unsubscribe());
    presenceSubscriptions = [];
    activeWorkspaceId = null;
    activeDocumentId = null;
}

function publishDocViewWebSocket(documentId) {
    if (stompClient && stompClient.connected && activeWorkspaceId) {
        activeDocumentId = documentId;
        stompClient.send(`/app/workspace/${activeWorkspaceId}/view`, {}, JSON.stringify({ documentId }));
    }
}

// ==========================================
// Business Operations (REST Clients)
// ==========================================

// --- tab-workspaces ---

async function loadWorkspaces() {
    try {
        const data = await request("/workspaces?page=0&size=50");
        const listCards = document.getElementById("workspace-list-cards");
        listCards.innerHTML = "";

        if (data.content.length === 0) {
            listCards.innerHTML = `
                <div style="grid-column: 1/-1; text-align: center; color: var(--text-secondary); padding: 3rem;">
                    No Deal Rooms created yet. Click "+ New Workspace" to create one.
                </div>
            `;
            return;
        }

        data.content.forEach(ws => {
            const card = document.createElement("div");
            card.className = "card-premium";
            card.innerHTML = `
                <div class="card-title">${ws.isPrivate ? "🔒 Private Workspace" : "🌐 Public Workspace"}</div>
                <div class="card-value" style="font-size: 1.5rem; margin-top: 0.5rem; margin-bottom: 0.5rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
                    ${ws.name}
                </div>
                <div class="card-desc" style="margin-bottom: 1.5rem;">${ws.description || "No description provided."}</div>
                <div style="display:flex; justify-content:space-between; align-items:center; font-size:0.8rem; color:var(--text-secondary);">
                    <span>👥 ${ws.memberCount} Members</span>
                    <button class="btn btn-outline" style="width:auto; padding: 0.4rem 1rem; font-size:0.75rem;" onclick="enterWorkspace('${ws.id}', '${ws.name.replace(/'/g, "\\'")}', '${(ws.description || '').replace(/'/g, "\\'")}')">Enter</button>
                </div>
            `;
            listCards.appendChild(card);
        });
    } catch (err) {
        showToast("Error loading workspaces", err.message, "danger");
    }
}

async function handleCreateWorkspace(e) {
    e.preventDefault();
    const name = document.getElementById("ws-name").value;
    const description = document.getElementById("ws-desc").value;
    const isPrivate = document.getElementById("ws-private").checked;

    try {
        await request("/workspaces", {
            method: "POST",
            body: JSON.stringify({ name, description, isPrivate })
        });
        showToast("Success", "Workspace deal room created!", "success");
        closeModal("modal-workspace");
        document.getElementById("form-create-workspace").reset();
        loadWorkspaces();
    } catch (err) {
        showToast("Error", err.message, "danger");
    }
}

// --- tab-workspace-detail ---

function enterWorkspace(id, name, desc) {
    activeWorkspaceId = id;
    document.getElementById("active-ws-name").textContent = name;
    document.getElementById("active-ws-desc").textContent = desc;

    showSectionDetails();
    navigateTab("tab-workspace-detail");
    
    // Connect websocket presence
    joinWorkspaceWebSocket(id);
    loadDocuments(id);
}

function showSectionDetails() {
    document.getElementById("selected-doc-panel").style.display = "none";
    document.getElementById("document-list-body").innerHTML = "";
    document.getElementById("presence-user-list").innerHTML = "";
}

async function loadDocuments(workspaceId) {
    try {
        const data = await request(`/workspaces/${workspaceId}/documents?page=0&size=50`);
        const tbody = document.getElementById("document-list-body");
        tbody.innerHTML = "";

        if (data.content.length === 0) {
            tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--text-secondary);">No documents uploaded yet.</td></tr>`;
            return;
        }

        data.content.forEach(doc => {
            const tr = document.createElement("tr");
            tr.style.cursor = "pointer";
            tr.onclick = (e) => {
                // If user clicked download or delete button, do not select doc view
                if (e.target.closest("button")) return;
                selectDocument(doc);
            };

            const sizeKB = (doc.fileSizeBytes / 1024).toFixed(1);
            const dateStr = new Date(doc.createdAt).toLocaleDateString();

            tr.innerHTML = `
                <td style="font-weight: 500; display:flex; align-items:center; gap:0.5rem;">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                    ${doc.name}
                </td>
                <td>${sizeKB} KB</td>
                <td><span class="tag tag-active" style="padding: 0.1rem 0.4rem; font-size:0.7rem;">${doc.status}</span></td>
                <td style="font-size:0.8rem; color:var(--text-secondary);">${doc.uploadedBy.substring(0,8)}</td>
                <td>${dateStr}</td>
                <td>
                    <div style="display:flex; gap:0.5rem;">
                        <button class="btn btn-outline" style="width:auto; padding: 0.3rem 0.8rem; font-size:0.75rem;" onclick="downloadDocument('${doc.id}')">Download</button>
                        <button class="btn btn-danger" style="width:auto; padding: 0.3rem 0.8rem; font-size:0.75rem; background:transparent; border-color:rgba(239,68,68,0.2); color:var(--danger);" onclick="deleteDocument('${doc.id}')">Delete</button>
                    </div>
                </td>
            `;
            tbody.appendChild(tr);
        });
    } catch (err) {
        showToast("Error", err.message, "danger");
    }
}

async function handleUploadDocument(e) {
    e.preventDefault();
    if (!activeWorkspaceId) return;

    const fileInput = document.getElementById("doc-file");
    const name = document.getElementById("doc-name").value;
    const description = document.getElementById("doc-desc").value;
    const tagsStr = document.getElementById("doc-tags").value;

    const file = fileInput.files[0];
    if (!file) return;

    const tags = tagsStr.split(",").map(t => t.trim()).filter(t => t.length > 0);

    const formData = new FormData();
    formData.append("file", file);
    // Spring expects request part or fields inside UploadRequest parameter
    formData.append("req", new Blob([JSON.stringify({ name, description, tags })], {
        type: "application/json"
    }));

    try {
        await request(`/workspaces/${activeWorkspaceId}/documents/upload`, {
            method: "POST",
            body: formData,
            headers: {
                // Must not specify Content-Type header so browser boundary gets correctly appended
            }
        });
        showToast("Success", "Document uploaded successfully!", "success");
        closeModal("modal-upload");
        document.getElementById("form-upload-doc").reset();
        loadDocuments(activeWorkspaceId);
    } catch (err) {
        showToast("Upload Failed", err.message, "danger");
    }
}

async function downloadDocument(docId) {
    if (!activeWorkspaceId) return;
    try {
        const data = await request(`/workspaces/${activeWorkspaceId}/documents/${docId}/download-url`);
        showToast("Download Initialized", "Secure presigned download link generated.", "success");
        window.open(data.presignedUrl, "_blank");
    } catch (err) {
        showToast("Download Blocked", err.message, "danger");
    }
}

async function deleteDocument(docId) {
    if (!activeWorkspaceId) return;
    if (!confirm("Are you sure you want to soft delete this document?")) return;

    try {
        await request(`/workspaces/${activeWorkspaceId}/documents/${docId}`, {
            method: "DELETE"
        });
        showToast("Success", "Document soft deleted.", "success");
        loadDocuments(activeWorkspaceId);
    } catch (err) {
        showToast("Deletion Blocked", err.message, "danger");
    }
}

async function handleAddMember(e) {
    e.preventDefault();
    if (!activeWorkspaceId) return;

    const userId = document.getElementById("member-user-id").value;
    const role = document.getElementById("member-role").value;

    try {
        await request(`/workspaces/${activeWorkspaceId}/members`, {
            method: "POST",
            body: JSON.stringify({ userId, role })
        });
        showToast("Success", "User added to workspace!", "success");
        closeModal("modal-member");
        document.getElementById("form-add-member").reset();
    } catch (err) {
        showToast("Failed to Add Member", err.message, "danger");
    }
}

// --- Presence updates display ---

async function loadPresenceList(workspaceId) {
    try {
        const details = await request(`/workspaces/${workspaceId}/presence/details`);
        const userList = document.getElementById("presence-user-list");
        userList.innerHTML = "";

        if (details.length === 0) {
            userList.innerHTML = `<li style="color:var(--text-secondary); font-size:0.8rem;">No active collaborators.</li>`;
            return;
        }

        details.forEach(p => {
            const isSelf = p.userId === authState.user.userId;
            const li = document.createElement("li");
            li.className = "presence-user-item";

            // If viewing a document, show it
            let docInfo = "";
            if (p.documentId) {
                docInfo = `<span class="view-indicator">Viewing document: ${p.documentId.substring(0,8)}</span>`;
            }

            li.innerHTML = `
                <div class="avatar" style="width:30px; height:30px; font-size:0.75rem;">
                    ${isSelf ? "ME" : "U"}
                </div>
                <div>
                    <span style="font-size:0.85rem; font-weight:500;">
                        User ${p.userId.substring(0,8)} ${isSelf ? "(You)" : ""}
                    </span>
                    ${docInfo}
                </div>
            `;
            userList.appendChild(li);
        });
    } catch (err) {
        console.error("Failed loading presence: ", err);
    }
}

// --- Select document detail and AI Summary ---

let currentSelectedDoc = null;
function selectDocument(doc) {
    currentSelectedDoc = doc;
    document.getElementById("selected-doc-panel").style.display = "block";
    document.getElementById("selected-doc-name").textContent = doc.name;
    document.getElementById("selected-doc-desc").textContent = doc.description || "No description.";
    
    // Broadcast websocket viewing state
    publishDocViewWebSocket(doc.id);
    
    // Render Tags
    const tagsBox = document.getElementById("selected-doc-tags");
    tagsBox.innerHTML = "";
    if (doc.tags && doc.tags.length > 0) {
        doc.tags.forEach(t => {
            const tag = document.createElement("span");
            tag.className = "tag";
            tag.textContent = t;
            tagsBox.appendChild(tag);
        });
    }

    // AI Summary display
    const summaryBox = document.getElementById("ai-summary-box");
    if (doc.aiSummary) {
        summaryBox.textContent = doc.aiSummary;
    } else {
        summaryBox.textContent = "No summary generated yet. Click the button below to generate one.";
    }
}

async function triggerAiSummary() {
    if (!currentSelectedDoc) return;
    const summaryBox = document.getElementById("ai-summary-box");
    summaryBox.textContent = "Asking Claude/OpenAI service to summarize files... please wait...";
    
    try {
        const summary = await request(`/ai/summarize/${currentSelectedDoc.id}`, {
            method: "POST"
        });
        summaryBox.textContent = summary;
        currentSelectedDoc.aiSummary = summary;
    } catch (err) {
        summaryBox.textContent = "Error generating AI summary: " + err.message;
    }
}

// --- tab-logs ---

async function loadAuditLogs(anomaliesOnly = false) {
    logsAnomaliesOnly = anomaliesOnly;
    try {
        const path = logsAnomaliesOnly 
            ? `/audit/anomalies?page=${logsPage}&size=${logsPageSize}`
            : `/audit/logs?page=${logsPage}&size=${logsPageSize}`;

        const data = await request(path);
        const tbody = document.getElementById("logs-list-body");
        tbody.innerHTML = "";

        if (data.content.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--text-secondary);">No logs found.</td></tr>`;
            return;
        }

        data.content.forEach(logRow => {
            const tr = document.createElement("tr");
            const dateStr = new Date(logRow.createdAt).toLocaleString();
            
            // Check signature integrity indicator
            let sigLabel = `<span class="tag tag-active" style="border-color:var(--primary); color:#6ee7b7;">✓ OK</span>`;
            if (logRow.isAnomaly) {
                sigLabel = `<span class="tag" style="border-color:var(--danger); color:var(--danger);">⚠️ ANOMALY</span>`;
            }

            // Anomaly explainer action button
            let actionBtn = "";
            if (logRow.isAnomaly) {
                actionBtn = `<button class="btn btn-primary" style="width:auto; padding:0.2rem 0.6rem; font-size:0.7rem;" onclick="explainAnomaly('${logRow.id}')">Explain AI</button>`;
            }

            tr.innerHTML = `
                <td style="font-weight:600; color:${logRow.isAnomaly ? 'var(--danger)' : 'inherit'}">${logRow.eventType}</td>
                <td style="font-size:0.75rem; color:var(--text-secondary);">${logRow.userId ? logRow.userId.substring(0,8) : "SYSTEM"}</td>
                <td>${logRow.resourceType}</td>
                <td style="font-size:0.75rem; color:var(--text-secondary);">${logRow.resourceId ? logRow.resourceId.substring(0,8) : "-"}</td>
                <td>${sigLabel}</td>
                <td>${dateStr}</td>
                <td>${actionBtn}</td>
            `;
            tbody.appendChild(tr);
        });

        // Pagination buttons check
        document.getElementById("btn-logs-prev").disabled = logsPage === 0;
        document.getElementById("btn-logs-next").disabled = data.last;

    } catch (err) {
        showToast("Audit Trail Error", err.message, "danger");
    }
}

function paginateLogs(delta) {
    logsPage += delta;
    if (logsPage < 0) logsPage = 0;
    loadAuditLogs(logsAnomaliesOnly);
}

async function explainAnomaly(logId) {
    openModal("modal-ai-explain");
    const explainBox = document.getElementById("ai-explanation-text");
    explainBox.textContent = "AI Model is analyzing security telemetry logs to explain the anomaly... please wait...";
    
    try {
        const text = await request(`/ai/explain-anomaly/${logId}`, {
            method: "POST"
        });
        explainBox.textContent = text;
    } catch (err) {
        explainBox.textContent = "AI Analysis failed: " + err.message;
    }
}

// --- tab-analytics ---

async function loadAnalytics() {
    if (!activeWorkspaceId) {
        showToast("Notice", "Please select a Deal Room workspace from Tab 1 to populate analytics.", "info");
        return;
    }

    try {
        // 1. Load Stats
        const stats = await request(`/analytics/workspace/${activeWorkspaceId}/stats`);
        document.getElementById("stats-total-docs").textContent = stats.totalDocs || 0;
        document.getElementById("stats-total-downloads").textContent = stats.totalDownloads || 0;
        document.getElementById("stats-active-users").textContent = stats.activeUsers || 0;

        // 2. Load Activity timeline and render Chart.js
        const timeline = await request(`/analytics/users/${authState.user.userId}/timeline`);
        renderTimelineChart(timeline);

        // 3. Load Heatmap data
        const heatmap = await request(`/analytics/workspace/${activeWorkspaceId}/heatmap`);
        renderHeatmapChart(heatmap);

    } catch (err) {
        showToast("Analytics Error", err.message, "danger");
    }
}

function renderTimelineChart(dataPoints) {
    const ctx = document.getElementById("chart-timeline").getContext("2d");
    
    if (timelineChart) timelineChart.destroy();

    // Map labels & datasets
    const labels = dataPoints.map(p => p.date);
    const counts = dataPoints.map(p => p.count);

    timelineChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels.length ? labels : ["No data"],
            datasets: [{
                label: 'Activity logs',
                data: counts.length ? counts : [0],
                borderColor: '#3b82f6',
                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                tension: 0.3,
                fill: true
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                y: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#94a3b8' } },
                x: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#94a3b8' } }
            },
            plugins: {
                legend: { display: false }
            }
        }
    });
}

function renderHeatmapChart(heatmapMap) {
    const ctx = document.getElementById("chart-heatmap").getContext("2d");

    if (heatmapChart) heatmapChart.destroy();

    const labels = Object.keys(heatmapMap).map(k => k.substring(0,8));
    const counts = Object.values(heatmapMap);

    heatmapChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels.length ? labels : ["No data"],
            datasets: [{
                label: 'Downloads count',
                data: counts.length ? counts : [0],
                backgroundColor: '#10b981',
                borderRadius: 8
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                y: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#94a3b8' } },
                x: { grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#94a3b8' } }
            },
            plugins: {
                legend: { display: false }
            }
        }
    });
}

// ==========================================
// App Initialization
// ==========================================

function initApp() {
    // Populate profile details
    document.getElementById("user-avatar").textContent = authState.user.fullName.substring(0,2).toUpperCase();
    document.getElementById("user-display-name").textContent = authState.user.fullName;
    document.getElementById("user-display-role").textContent = authState.user.role;

    // Show/hide security elements based on roles
    const isAdmin = authState.user.role === "ADMIN" || authState.user.role === "MANAGER";
    document.getElementById("nav-logs").style.display = isAdmin ? "flex" : "none";
    document.getElementById("nav-analytics").style.display = isAdmin ? "flex" : "none";

    showScreen("screen-app");
    navigateTab("tab-workspaces");
    loadWorkspaces();

    // Connect real-time alerts WebSocket
    connectWebSocket();
}

// Main entrance router
document.addEventListener("DOMContentLoaded", () => {
    if (authState.accessToken && authState.user) {
        initApp();
    } else {
        showScreen("screen-login");
    }
});
