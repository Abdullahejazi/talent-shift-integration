function getHeaders() {
  const token = localStorage.getItem('auth_token');
  if (!token) throw new Error('Not authenticated');
  return {
    'Authorization': 'Basic ' + token,
    'Content-Type': 'application/json'
  };
}

export function login(username, password) {
  const token = btoa(`${username}:${password}`);
  localStorage.setItem('auth_token', token);
}

export function logout() {
  localStorage.removeItem('auth_token');
}

export function isAuthenticated() {
  return !!localStorage.getItem('auth_token');
}

export async function fetchImports() {
  const res = await fetch('/api/v1/imports', { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch imports');
  return { content: await res.json() };
}

export async function triggerImport(systemKey) {
  const res = await fetch('/api/v1/imports', { 
    method: 'POST', 
    headers: getHeaders(),
    body: JSON.stringify({ systemKey })
  });
  if (!res.ok) throw new Error('Failed to start import');
  return res.json();
}

export async function triggerMockImport() {
  const res = await fetch('/api/v1/imports/mock', { method: 'POST', headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to trigger mock import');
  return res.json();
}

export async function fetchCanonicalJobs(params = {}) {
  const query = new URLSearchParams(params).toString();
  const res = await fetch(`/api/v1/canonical/jobs${query ? `?${query}` : ''}`, { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch jobs');
  return { content: await res.json() };
}

export async function fetchCanonicalSources(params = {}) {
  const query = new URLSearchParams(params).toString();
  const res = await fetch(`/api/v1/canonical/sources${query ? `?${query}` : ''}`, { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch sources');
  return { content: await res.json() };
}

export async function fetchPendingReviews() {
  const res = await fetch('/api/v1/reviews?status=PENDING', { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch reviews');
  return { content: await res.json() };
}

export async function approveReview(id) {
  const res = await fetch(`/api/v1/reviews/${id}/approve`, { method: 'POST', headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to approve');
}

export async function rejectReview(id) {
  const res = await fetch(`/api/v1/reviews/${id}/reject`, { method: 'POST', headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to reject');
}

export async function updateSourceType(id, type) {
  const res = await fetch(`/api/v1/canonical/sources/${id}/type`, {
    method: 'PATCH',
    headers: getHeaders(),
    body: JSON.stringify({ type })
  });
  if (!res.ok) throw new Error('Failed to update source type');
}

export async function fetchAuditLogs() {
  const res = await fetch('/api/v1/audit', { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch audit logs');
  return { content: await res.json() };
}

export async function fetchMergeHistory() {
  const res = await fetch('/api/v1/canonical/merges', { headers: getHeaders() });
  if (!res.ok) throw new Error('Failed to fetch merges');
  return { content: await res.json() };
}

export async function reverseMerge(id, reason) {
  const res = await fetch(`/api/v1/canonical/merges/${id}/reverse`, { 
    method: 'POST', 
    headers: getHeaders(),
    body: JSON.stringify({ reason })
  });
  if (!res.ok) throw new Error('Failed to reverse merge');
}
