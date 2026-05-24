import http from 'k6/http';
import { check, sleep } from 'k6';

// k6 Options: configure load progression
export const options = {
    stages: [
        { duration: '30s', target: 20 }, // Ramp-up to 20 virtual users (VUs)
        { duration: '1m', target: 20 },  // Stay at 20 VUs
        { duration: '30s', target: 0 },  // Ramp-down to 0 VUs
    ],
    thresholds: {
        http_req_failed: ['rate<0.01'],   // Error rate should be less than 1%
        http_req_duration: ['p(95)<800'], // 95% of requests must complete below 800ms
    },
};

const BASE_URL = 'http://localhost:8080/api/v1';

// Helper to generate random strings for uniqueness
function randomString(length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < length; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
}

export default function () {
    const uniqueId = randomString(6);
    const orgName = `Org-${uniqueId}`;
    const email = `user-${uniqueId}@example.com`;
    const password = 'SecurePassword123!';
    const fullName = `LoadTester-${uniqueId}`;

    // --- Scenario 1: Tenant Registration ---
    const registerPayload = JSON.stringify({
        organizationName: orgName,
        fullName: fullName,
        email: email,
        password: password,
    });

    const regParams = {
        headers: { 'Content-Type': 'application/json' },
    };

    const regRes = http.post(`${BASE_URL}/auth/register`, registerPayload, regParams);
    
    const regCheck = check(regRes, {
        'registration status is 201': (r) => r.status === 201,
        'has accessToken': (r) => r.json('data.accessToken') !== undefined,
    });

    if (!regCheck) {
        // If registration fails (e.g., container down), sleep and abort iteration
        sleep(1);
        return;
    }

    const token = regRes.json('data.accessToken');
    const authHeaders = {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
    };

    sleep(1);

    // --- Scenario 2: Create Workspace ---
    const wsPayload = JSON.stringify({
        name: `Deal Room ${uniqueId}`,
        description: 'Load test temporary workspace',
        isPrivate: false,
    });

    const wsRes = http.post(`${BASE_URL}/workspaces`, wsPayload, authHeaders);
    
    check(wsRes, {
        'create workspace status is 201': (r) => r.status === 201,
        'has workspace ID': (r) => r.json('data.id') !== undefined,
    });

    const workspaceId = wsRes.json('data.id');

    sleep(1);

    // --- Scenario 3: List Workspaces ---
    const listRes = http.get(`${BASE_URL}/workspaces?page=0&size=10`, authHeaders);
    check(listRes, {
        'list workspaces status is 200': (r) => r.status === 200,
    });

    sleep(1);

    // --- Scenario 4: Upload Document ---
    // k6 uses multipart construction for file uploads
    const dummyFileContent = 'Hello World from k6 load testing utility. Standard text payload.';
    
    // We send req JSON alongside the file binary
    const uploadData = {
        file: http.file(dummyFileContent, 'dummy-spec.txt', 'text/plain'),
        req: JSON.stringify({
            name: `Spec-${uniqueId}.txt`,
            description: 'Load test upload file description',
            tags: ['load-test', 'k6']
        }),
    };

    // Multipart headers (exclude Content-Type, HTTP client generates it automatically with boundary)
    const uploadParams = {
        headers: {
            'Authorization': `Bearer ${token}`,
        },
    };

    const uploadRes = http.post(
        `${BASE_URL}/workspaces/${workspaceId}/documents/upload`,
        uploadData,
        uploadParams
    );

    check(uploadRes, {
        'upload document status is 201': (r) => r.status === 201,
        'has document ID': (r) => r.json('data.id') !== undefined,
    });

    const docId = uploadRes.json('data.id');

    sleep(1);

    // --- Scenario 5: Download Document (triggers Redis Rate Limiting) ---
    // Make multiple requests quickly to stress test rate limiters
    for (let i = 0; i < 3; i++) {
        const downloadRes = http.get(
            `${BASE_URL}/workspaces/${workspaceId}/documents/${docId}/download-url`,
            authHeaders
        );
        
        check(downloadRes, {
            'download status is 200 or 429': (r) => r.status === 200 || r.status === 429,
        });
        
        sleep(0.2); // short delay to represent fast client clicks
    }

    sleep(2);
}
