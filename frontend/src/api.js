let csrfToken = null;

async function decode(response) {
  const text = await response.text();
  let body = null;
  if (text) { try { body = JSON.parse(text); } catch { body = text; } }
  if (!response.ok) throw new Error(body?.message || body?.detail || `Request failed (${response.status})`);
  return body;
}

export async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  const method = (options.method || 'GET').toUpperCase();
  if (!['GET','HEAD','OPTIONS'].includes(method)) {
    if (!csrfToken) {
      const response = await fetch('/api/auth/csrf', { credentials: 'include' });
      if (response.ok) csrfToken = await response.json();
    }
    if (csrfToken) headers[csrfToken.headerName] = csrfToken.token;
  }
  return decode(await fetch(path, { credentials: 'include', ...options, headers }));
}

export const isAuthenticated = () => sessionStorage.getItem('talentshift_authenticated') === 'true';
export async function getMe() { return request('/api/auth/me'); }
export async function login(email, password) {
  const user = await request('/api/auth/login', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({email,password}) });
  sessionStorage.setItem('talentshift_authenticated','true');
  return user;
}
export async function register(fullName, email, password) {
  const user = await request('/api/auth/register', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify({role:'CANDIDATE',fullName,email,password})
  });
  sessionStorage.setItem('talentshift_authenticated','true');
  return user;
}
export async function logout() { try { await request('/api/auth/logout',{method:'POST'}); } finally { sessionStorage.removeItem('talentshift_authenticated'); csrfToken=null; } }

const query = values => {
  const params = new URLSearchParams();
  Object.entries(values || {}).forEach(([k,v]) => { if (v !== '' && v !== null && v !== undefined) params.set(k,v); });
  return params.toString();
};

export const fetchJobs = filters => request(`/api/jobs?${query(filters)}`);
export const fetchJobCount = () => request('/api/jobs/count');
export const fetchRecommendedJobs = () => request('/api/jobs/recommended');
export const fetchJob = id => request(`/api/jobs/${id}`);
export const fetchCompanies = (keyword='') => request(`/api/companies?${query({keyword,page:0,size:100})}`);
export const fetchCompany = slug => request(`/api/companies/${slug}`);
export const fetchProfile = () => request('/api/profile');
export const updateProfile = data => request('/api/profile',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
export const fetchSavedJobs = () => request('/api/workspace/saved-jobs');
export const saveJob = id => request(`/api/workspace/saved-jobs/${id}`,{method:'POST'});
export const unsaveJob = id => request(`/api/workspace/saved-jobs/${id}`,{method:'DELETE'});
export const fetchApplications = () => request('/api/workspace/applications');
export const trackApplication = id => request(`/api/workspace/applications/${id}`,{method:'POST'});
export const updateApplication = (id,status) => request(`/api/workspace/applications/${id}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({status})});
export const fetchMeetings = () => request('/api/workspace/meetings');
export const createMeeting = data => request('/api/workspace/meetings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
export const deleteMeeting = id => request(`/api/workspace/meetings/${id}`,{method:'DELETE'});
export const fetchConversations = () => request('/api/workspace/conversations');
export const fetchMessages = id => request(`/api/workspace/conversations/${id}/messages`);
export const sendMessage = (id,body) => request(`/api/workspace/conversations/${id}/messages`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({body})});



export const approveSource = (source) => request('/api/v1/admin/sources/approve', { 
  method: 'POST', 
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(source) 
});

export const fetchSources = () => request('/api/admin/job-sources');
export const fetchOperations = () => request('/api/admin/job-sources/status');
export const fetchDailyMetrics = () => request('/api/admin/job-sources/daily-metrics');
export const fetchSourcePerformance = () => request('/api/admin/job-sources/performance');
export const fetchDiscoveryHistory = () => request('/api/admin/job-sources/discovery-history');
export const collectJobs = () => request('/api/admin/jobs/collect',{method:'POST'});
export const searchNewJobs = () => request('/api/admin/jobs/agent-search',{method:'POST'});
export const searchSeedJobs = () => request('/api/admin/jobs/agent-seed-search',{method:'POST'});
export const recheckSources = () => request('/api/admin/job-sources/recheck',{method:'POST'});
export const verifyJobLinks = () => request('/api/admin/job-sources/verify-jobs',{method:'POST'});
export const expireJobs = () => request('/api/admin/job-sources/expire-jobs',{method:'POST'});
export const deduplicateJobs = () => request('/api/admin/job-sources/deduplicate-jobs',{method:'POST'});
export const discoverCareers = () => request('/api/admin/job-sources/discover-careers',{method:'POST'});
export const toggleSource = (id,enabled) => request(`/api/admin/job-sources/${id}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled})});

export const fetchSystems = () => request('/api/integration/connected-systems');
export const fetchImports = () => request('/api/integration/imports');
export const fetchRawJobs = () => request('/api/integration/raw/jobs');
export const fetchLineage = () => request('/api/integration/lineage');
export const fetchReviews = status => request(`/api/integration/reviews?${query({status})}`);
export const decideReview = (id,decision,note='') => request(`/api/integration/reviews/${id}/${decision}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({note})});
export const fetchCheckpoints = () => request('/api/integration/checkpoints');
export const fetchOutbox = () => request('/api/integration/outbox');
export const fetchAttempts = id => request(`/api/integration/outbox/${id}/attempts`);
export const retryOutbox = id => request(`/api/integration/outbox/${id}/retry`,{method:'POST'});
export const sendReady = () => request('/api/integration/outbox/send-ready',{method:'POST'});
export const enqueueJob = id => request(`/api/integration/outbox/jobs/${id}`,{method:'POST'});
export const fetchAudit = () => request('/api/integration/audit');
export const fetchMerges = () => request('/api/integration/merges');
export const fetchAiStatus = () => request('/api/integration/ai/status');
export const runAiDiscovery = mode => request('/api/integration/ai/discover',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode})});
