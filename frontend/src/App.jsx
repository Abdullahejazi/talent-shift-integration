import React, { useState, useEffect, useMemo } from 'react';
import { 
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, Legend, ResponsiveContainer,
  PieChart, Pie, Cell, LineChart, Line
} from 'recharts';
import { 
  fetchImports, triggerMockImport, triggerImport, fetchCanonicalJobs, 
  fetchCanonicalSources, fetchPendingReviews, approveReview, rejectReview, 
  updateSourceType, fetchAuditLogs, fetchMergeHistory, reverseMerge, fetchSemanticJobs,
  login, logout, isAuthenticated as checkAuth
} from './api';
import './index.css';

const SOURCE_TYPES = [
  { id: 1, name: 'مواقع الشركات الرسمية (Official company websites)' },
  { id: 2, name: 'المنصة الوطنية الموحدة للتوظيف - جدارات' },
  { id: 3, name: 'منصات التوظيف الإقليمية (Regional platforms)' },
  { id: 4, name: 'صفحات وظائف الشركات (LinkedIn Jobs, etc)' },
  { id: 5, name: 'مواقع التوظيف العالمية (Global platforms)' },
  { id: 6, name: 'مواقع المشاريع الكبرى والجهات شبه الحكومية (Mega projects)' },
  { id: 7, name: 'مواقع الجهات الحكومية والهيئات (Government entities)' },
  { id: 8, name: 'منصات التوظيف الحكومية المتخصصة (Specialized gov platforms)' },
  { id: 9, name: 'مواقع شركات التوظيف والاستقطاب (Recruitment agencies)' },
  { id: 10, name: 'حسابات التواصل الاجتماعي المتخصصة بالتوظيف (Social media)' },
  { id: 11, name: 'مواقع الجامعات ومراكز الخريجين (Universities)' },
  { id: 12, name: 'معارض التوظيف والفعاليات المهنية (Career fairs)' },
  { id: 13, name: 'النشرات البريدية والتنبيهات (Newsletters)' },
  { id: 14, name: 'غرف التجارة والجمعيات المهنية (Chambers of commerce)' },
  { id: 15, name: 'مواقع Applicant Tracking Systems - ATS' },
  { id: 16, name: 'إعلانات الصحف والمواقع الإخبارية (Newspapers)' },
  { id: 17, name: 'العلاقات المباشرة مع الشركات (Direct relationships)' },
  { id: 18, name: 'برامج التدريب المنتهي بالتوظيف (Bootcamps/Training)' }
];

function App() {
  const [activeTab, setActiveTab] = useState('dashboard');
  const [authenticated, setAuthenticated] = useState(checkAuth());

  if (!authenticated) {
    return <Login onLogin={() => setAuthenticated(true)} />;
  }

  const handleLogout = () => {
    logout();
    setAuthenticated(false);
  };

  return (
    <div id="root">
      <div className="sidebar">
        <div>
          <h2>Integrations</h2>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Control Panel</p>
        </div>
        
        <nav style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', flex: 1 }}>
          <div className={`nav-item ${activeTab === 'dashboard' ? 'active' : ''}`} onClick={() => setActiveTab('dashboard')}>
            Dashboard
          </div>
          <div className={`nav-item ${activeTab === 'sources' ? 'active' : ''}`} onClick={() => setActiveTab('sources')}>
            Data Explorer (Sources)
          </div>
          <div className={`nav-item ${activeTab === 'jobs' ? 'active' : ''}`} onClick={() => setActiveTab('jobs')}>
            Data Explorer (Jobs)
          </div>
          <div className={`nav-item ${activeTab === 'reviews' ? 'active' : ''}`} onClick={() => setActiveTab('reviews')}>
            Review Hub
          </div>
          <div className={`nav-item ${activeTab === 'merges' ? 'active' : ''}`} onClick={() => setActiveTab('merges')}>
            Merge History
          </div>
          <div className={`nav-item ${activeTab === 'audit' ? 'active' : ''}`} onClick={() => setActiveTab('audit')}>
            Audit Logs
          </div>
        </nav>
        
        <div style={{ marginTop: 'auto' }}>
          <button className="btn btn-danger" style={{ width: '100%' }} onClick={handleLogout}>Log Out</button>
        </div>
      </div>

      <div className="main-content">
        {activeTab === 'dashboard' && <Dashboard />}
        {activeTab === 'sources' && <Explorer type="sources" />}
        {activeTab === 'jobs' && <Explorer type="jobs" />}
        {activeTab === 'reviews' && <ReviewHub />}
        {activeTab === 'merges' && <MergeHistory />}
        {activeTab === 'audit' && <AuditLogs />}
      </div>
    </div>
  );
}

function Login({ onLogin }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    try {
      login(username, password);
      onLogin();
    } catch (err) {
      setError('Failed to login. Check credentials.');
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh', background: 'var(--background)' }}>
      <form onSubmit={handleSubmit} className="card" style={{ width: '400px', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
        <h2 style={{ textAlign: 'center', color: 'var(--primary)', marginBottom: '1rem' }}>Admin Login 🔒</h2>
        {error && <div style={{ color: 'var(--danger)', background: 'rgba(239, 68, 68, 0.1)', padding: '0.5rem', borderRadius: '4px', textAlign: 'center' }}>{error}</div>}
        <div>
          <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)' }}>Username</label>
          <input type="text" value={username} onChange={e => setUsername(e.target.value)} required style={{ width: '100%', padding: '0.75rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--surface-light)', color: 'var(--text-main)' }} />
        </div>
        <div>
          <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)' }}>Password</label>
          <input type="password" value={password} onChange={e => setPassword(e.target.value)} required style={{ width: '100%', padding: '0.75rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--surface-light)', color: 'var(--text-main)' }} />
        </div>
        <button type="submit" className="btn btn-primary" style={{ marginTop: '1rem' }}>Sign In</button>
      </form>
    </div>
  );
}

function Dashboard() {
  const [imports, setImports] = useState([]);
  const [sources, setSources] = useState([]);
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [importKey, setImportKey] = useState('');

  useEffect(() => {
    loadDashboardData();
  }, []);

  const loadDashboardData = async () => {
    setLoading(true);
    try {
      const [importsData, sourcesData, jobsData] = await Promise.all([
        fetchImports(),
        fetchCanonicalSources(),
        fetchCanonicalJobs()
      ]);
      setImports(importsData.content || []);
      setSources(sourcesData.content || []);
      setJobs(jobsData.content || []);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const handleStartImport = async (e) => {
    e.preventDefault();
    if (!importKey) return;
    try {
      await triggerImport(importKey);
      alert(`Import for ${importKey} started successfully!`);
      setImportKey('');
      loadDashboardData();
    } catch (e) {
      alert('Failed to start import. Make sure the systemKey exists.');
    }
  };

  const handleMockImport = async () => {
    try {
      await triggerMockImport();
      alert('Mock import triggered!');
      loadDashboardData();
    } catch (e) {
      alert('Failed to trigger import');
    }
  };

  const sourceStats = useMemo(() => {
    return sources.map(source => {
      const jobCount = jobs.filter(job => job.canonical_source_id === source.id).length;
      return { ...source, jobCount };
    }).sort((a, b) => b.jobCount - a.jobCount);
  }, [sources, jobs]);

  const typeCounts = useMemo(() => {
    return SOURCE_TYPES.map(type => ({
      ...type,
      count: sources.filter(s => (s.source_type || 1) === type.id).length
    })).filter(t => t.count > 0);
  }, [sources]);

  const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#14b8a6'];

  return (
    <div>
      <h1>System Dashboard</h1>
      
      <div className="grid-3" style={{ marginBottom: '2rem' }}>
        <div className="card" style={{ textAlign: 'center' }}>
          <h2 style={{ fontSize: '3rem', color: 'var(--primary)', marginBottom: '0.5rem' }}>{sources.length}</h2>
          <p style={{ color: 'var(--text-muted)', fontWeight: '500' }}>Total Sources</p>
        </div>
        <div className="card" style={{ textAlign: 'center' }}>
          <h2 style={{ fontSize: '3rem', color: 'var(--accent)', marginBottom: '0.5rem' }}>{jobs.length}</h2>
          <p style={{ color: 'var(--text-muted)', fontWeight: '500' }}>Total Jobs</p>
        </div>
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1rem', justifyContent: 'center' }}>
          <h3 style={{ margin: 0 }}>Manual Import Trigger ⚙️</h3>
          <form onSubmit={handleStartImport} style={{ display: 'flex', gap: '0.5rem' }}>
            <input type="text" placeholder="System Key (e.g. linkedin)" value={importKey} onChange={e => setImportKey(e.target.value)} required style={{ flex: 1, padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--surface-light)', color: 'var(--text-main)' }} />
            <button type="submit" className="btn btn-primary">Run</button>
          </form>
          <button className="btn" style={{ background: 'var(--surface-light)', color: 'var(--text-muted)', border: '1px solid var(--border)' }} onClick={handleMockImport}>Run Mock Data Instead</button>
        </div>
      </div>

      <h2>Analytics & Data Distribution</h2>
      <div className="grid-2" style={{ marginBottom: '2rem' }}>
        <div className="card" style={{ height: '350px' }}>
          <h3>Sources by Category</h3>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={typeCounts} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
              <XAxis dataKey="id" stroke="var(--text-muted)" tick={{ fill: 'var(--text-muted)' }} />
              <YAxis stroke="var(--text-muted)" tick={{ fill: 'var(--text-muted)' }} />
              <RechartsTooltip cursor={{ fill: 'var(--surface-light)' }} contentStyle={{ backgroundColor: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: '8px' }} />
              <Bar dataKey="count" fill="var(--primary)" radius={[4, 4, 0, 0]} name="Source Count" />
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div className="card" style={{ height: '350px' }}>
          <h3>Top Job Providers</h3>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={sourceStats.slice(0, 7)}
                cx="50%"
                cy="50%"
                innerRadius={60}
                outerRadius={100}
                fill="#8884d8"
                paddingAngle={5}
                dataKey="jobCount"
                nameKey="display_name"
                label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                labelLine={false}
              >
                {sourceStats.slice(0, 7).map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                ))}
              </Pie>
              <RechartsTooltip contentStyle={{ backgroundColor: 'var(--bg-input)', border: '1px solid var(--border)', borderRadius: '8px' }} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>

      <h2>Jobs per Source</h2>
      <div className="card" style={{ padding: '0', overflow: 'hidden', marginBottom: '2rem' }}>
        <table style={{ margin: 0 }}>
          <thead>
            <tr>
              <th>Source Name</th>
              <th>Domain</th>
              <th style={{ textAlign: 'right' }}>Active Jobs</th>
            </tr>
          </thead>
          <tbody>
            {sourceStats.map(stat => (
              <tr key={stat.id}>
                <td style={{ fontWeight: '500' }}>{stat.display_name || 'Unknown'}</td>
                <td style={{ color: 'var(--text-muted)' }}>{stat.official_domain || '-'}</td>
                <td style={{ textAlign: 'right' }}>
                  <span className="badge" style={{ background: 'rgba(99, 102, 241, 0.15)', color: 'var(--primary)', border: '1px solid rgba(99, 102, 241, 0.3)', fontSize: '0.85rem' }}>
                    {stat.jobCount}
                  </span>
                </td>
              </tr>
            ))}
            {sourceStats.length === 0 && (
              <tr>
                <td colSpan="3" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>No sources found</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      
      <h2>Recent Imports</h2>
      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>System Key</th>
              <th>Status</th>
              <th>Started At</th>
            </tr>
          </thead>
          <tbody>
            {imports.map(imp => (
              <tr key={imp.id}>
                <td>{imp.id.slice(0, 8)}...</td>
                <td>{imp.systemKey}</td>
                <td>
                  <span className={`badge ${imp.status === 'COMPLETED' ? 'approved' : 'pending'}`}>
                    {imp.status}
                  </span>
                </td>
                <td>{new Date(imp.startedAt).toLocaleString()}</td>
              </tr>
            ))}
            {imports.length === 0 && (
              <tr>
                <td colSpan="4" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>No imports found</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Explorer({ type }) {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  
  // Search state
  const [searchOrg, setSearchOrg] = useState('');
  const [searchDomain, setSearchDomain] = useState(''); // Only for sources
  const [searchType, setSearchType] = useState(''); // Only for sources
  const [searchLocation, setSearchLocation] = useState(''); // Only for jobs
  const [searchTitle, setSearchTitle] = useState(''); // Added Job Title search
  const [offlineOnly, setOfflineOnly] = useState(false);

  // Expanded row state
  const [expandedSourceId, setExpandedSourceId] = useState(null);
  const [sourceJobs, setSourceJobs] = useState({});
  const [loadingJobsFor, setLoadingJobsFor] = useState(null);

  useEffect(() => {
    // Only load automatically if not searching by title to avoid double fetches, or handle it simply:
    loadData();
  }, [type]);

  const loadData = async (e) => {
    if (e) e.preventDefault();
    setLoading(true);
    try {
      let res;
      if (type === 'sources') {
        const params = {};
        if (searchOrg) params.organization = searchOrg;
        if (searchDomain) params.domain = searchDomain;
        res = await fetchCanonicalSources(params);
      } else {
        if (searchTitle) {
          // Use AI Semantic Search via Backend
          res = await fetchSemanticJobs(searchTitle);
        } else {
          const params = {};
          if (searchOrg) params.organization = searchOrg;
          if (searchLocation) params.location = searchLocation;
          res = await fetchCanonicalJobs(params);
        }
      }
      setData(res.content || []);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const handleSourceTypeChange = async (id, newType) => {
    try {
      await updateSourceType(id, parseInt(newType, 10));
      setData(prev => prev.map(item => item.id === id ? { ...item, source_type: parseInt(newType, 10) } : item));
    } catch (e) {
      console.error('Failed to update source type', e);
      alert('Failed to update source type');
    }
  };

  const handleToggleSource = async (sourceId) => {
    if (expandedSourceId === sourceId) {
      setExpandedSourceId(null);
      return;
    }
    setExpandedSourceId(sourceId);
    if (!sourceJobs[sourceId]) {
      setLoadingJobsFor(sourceId);
      try {
        const res = await fetchCanonicalJobs(); 
        const jobs = (res.content || []).filter(j => j.canonical_source_id === sourceId);
        setSourceJobs(prev => ({ ...prev, [sourceId]: jobs }));
      } catch(e) {
        console.error(e);
      }
      setLoadingJobsFor(null);
    }
  };

  // Client side filtering for "Offline Only" and "Source Type"
  const filteredData = data.filter(item => {
    if (offlineOnly && item.consecutive_check_failures === 0) return false;
    
    if (type === 'sources' && searchType) {
      if ((item.source_type || 1) !== parseInt(searchType, 10)) return false;
    }
    
    // Note: Job Title search is now handled purely via Backend Semantic Search (pgvector)
    
    return true;
  });

  return (
    <div>
      <h1>Canonical {type === 'sources' ? 'Sources' : 'Jobs'}</h1>
      
      <div className="card" style={{ marginBottom: '2rem', background: 'var(--surface-light)' }}>
        <form onSubmit={loadData} style={{ display: 'flex', gap: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: '200px', maxWidth: '300px' }}>
            <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>Company/Organization</label>
            <input type="text" placeholder="Search by name..." value={searchOrg} onChange={e => setSearchOrg(e.target.value)} style={{ width: '100%', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--background)', color: 'var(--text-main)' }} />
          </div>
          
          {type === 'sources' ? (
            <>
              <div style={{ flex: 1, minWidth: '200px', maxWidth: '300px' }}>
                <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>Domain</label>
                <input type="text" placeholder="e.g. linkedin.com" value={searchDomain} onChange={e => setSearchDomain(e.target.value)} style={{ width: '100%', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--background)', color: 'var(--text-main)' }} />
              </div>
              <div style={{ flex: 1, minWidth: '200px', maxWidth: '300px' }}>
                <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>Source Type</label>
                <select value={searchType} onChange={e => setSearchType(e.target.value)} style={{ width: '100%', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--background)', color: 'var(--text-main)' }}>
                  <option value="">All Types</option>
                  {SOURCE_TYPES.map(st => <option key={st.id} value={st.id}>{st.name}</option>)}
                </select>
              </div>
            </>
          ) : (
            <>
              <div style={{ flex: 1, minWidth: '200px', maxWidth: '300px' }}>
                <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>Job Title / Keywords</label>
                <input type="text" placeholder="e.g. software, chef..." value={searchTitle} onChange={e => setSearchTitle(e.target.value)} style={{ width: '100%', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--background)', color: 'var(--text-main)' }} />
              </div>
              <div style={{ flex: 1, minWidth: '200px', maxWidth: '300px' }}>
                <label style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-muted)', fontSize: '0.85rem' }}>Location</label>
                <input type="text" placeholder="e.g. Riyadh" value={searchLocation} onChange={e => setSearchLocation(e.target.value)} style={{ width: '100%', padding: '0.5rem', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--background)', color: 'var(--text-main)' }} />
              </div>
            </>
          )}

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', paddingBottom: '0.5rem', cursor: 'pointer' }} onClick={() => setOfflineOnly(!offlineOnly)}>
            <input type="checkbox" checked={offlineOnly} readOnly style={{ cursor: 'pointer' }} />
            <label style={{ cursor: 'pointer', color: 'var(--danger)' }}>Show Offline Only</label>
          </div>

          <button type="submit" className="btn btn-primary" style={{ padding: '0.5rem 1.5rem' }}>Search</button>
        </form>
      </div>

      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              {type === 'sources' ? (
                <>
                  <th>Name</th>
                  <th>Domain</th>
                  <th>URL</th>
                  <th>Status</th>
                  <th>Source Type</th>
                </>
              ) : (
                <>
                  <th>Title</th>
                  <th>Company</th>
                  <th>Location</th>
                  <th>Status</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {filteredData.map((item, index) => (
              <React.Fragment key={item.id}>
              <tr style={type === 'sources' ? { cursor: 'pointer', background: expandedSourceId === item.id ? 'var(--surface-light)' : 'transparent', transition: 'background 0.2s' } : {}} onClick={() => type === 'sources' && handleToggleSource(item.id)}>
                <td style={{ fontWeight: '600', color: 'var(--text-muted)' }}>{index + 1}</td>
                {type === 'sources' ? (
                  <>
                    <td style={{ fontWeight: '500' }}>{item.display_name || '-'} {expandedSourceId === item.id ? '▼' : '▶'}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{item.official_domain || '-'}</td>
                    <td>
                      {item.normalized_url ? (
                        <a href={item.normalized_url} target="_blank" rel="noopener noreferrer" style={{ color: 'var(--primary)', textDecoration: 'none', fontWeight: '500' }} onClick={e => e.stopPropagation()}>
                          {item.normalized_url}
                        </a>
                      ) : '-'}
                    </td>
                    <td>
                      {item.consecutive_check_failures > 0 ? (
                        <span className="badge pending" style={{ background: 'rgba(239, 68, 68, 0.15)', color: 'var(--danger)', border: '1px solid rgba(239, 68, 68, 0.3)' }}>Offline</span>
                      ) : (
                        <span className="badge approved">Online</span>
                      )}
                    </td>
                    <td>
                      <select 
                        value={item.source_type || 1} 
                        onChange={(e) => handleSourceTypeChange(item.id, e.target.value)}
                        onClick={e => e.stopPropagation()}
                        style={{ padding: '0.35rem 0.5rem', borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--bg-input)', color: 'var(--text-main)', fontSize: '0.85rem', width: '220px', cursor: 'pointer' }}
                      >
                        {SOURCE_TYPES.map(t => (
                          <option key={t.id} value={t.id}>{t.id} - {t.name}</option>
                        ))}
                      </select>
                    </td>
                  </>
                ) : (
                  <>
                    <td style={{ fontWeight: '500' }}>{item.title || '-'}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{item.organization_name || '-'}</td>
                    <td>{item.location || '-'}</td>
                    <td>
                      {item.consecutive_check_failures > 0 ? (
                        <span className="badge pending" style={{ background: 'rgba(239, 68, 68, 0.15)', color: 'var(--danger)', border: '1px solid rgba(239, 68, 68, 0.3)' }}>Offline</span>
                      ) : (
                        <span className="badge approved">Online</span>
                      )}
                    </td>
                  </>
                )}
              </tr>
              {type === 'sources' && expandedSourceId === item.id && (
                <tr style={{ background: 'rgba(0,0,0,0.1)' }}>
                  <td colSpan="6" style={{ padding: '1rem 2rem', borderBottom: '1px solid var(--border)' }}>
                    <h4 style={{ marginBottom: '1rem', color: 'var(--primary)' }}>Jobs from this source</h4>
                    {loadingJobsFor === item.id ? (
                      <p style={{ color: 'var(--text-muted)' }}>Loading jobs...</p>
                    ) : sourceJobs[item.id] && sourceJobs[item.id].length > 0 ? (
                      <table style={{ background: 'var(--bg-input)', borderRadius: '8px', margin: 0 }}>
                        <thead>
                          <tr>
                            <th>Job Title</th>
                            <th>Location</th>
                            <th>Status</th>
                          </tr>
                        </thead>
                        <tbody>
                          {sourceJobs[item.id].map(job => (
                            <tr key={job.id}>
                              <td style={{ fontWeight: '500' }}>{job.title}</td>
                              <td style={{ color: 'var(--text-muted)' }}>{job.location || '-'}</td>
                              <td>{job.consecutive_check_failures > 0 ? <span style={{ color: 'var(--danger)' }}>Offline</span> : <span style={{ color: 'var(--success)' }}>Online</span>}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <p style={{ color: 'var(--text-muted)' }}>No active jobs found for this source.</p>
                    )}
                  </td>
                </tr>
              )}
              </React.Fragment>
            ))}
            {!loading && filteredData.length === 0 && (
              <tr>
                <td colSpan="6" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>No data found matching your search.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ReviewHub() {
  const [reviews, setReviews] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadReviews();
  }, []);

  const loadReviews = async () => {
    setLoading(true);
    try {
      const data = await fetchPendingReviews();
      setReviews(data.content || []);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const handleApprove = async (id) => {
    await approveReview(id);
    loadReviews();
  };

  const handleReject = async (id) => {
    await rejectReview(id);
    loadReviews();
  };

  return (
    <div>
      <h1>Review Hub</h1>
      <p style={{ marginBottom: '2rem', color: 'var(--text-muted)' }}>
        Resolve uncertain deduplication matches by approving or rejecting them.
      </p>
      
      <div className="grid-2">
        {reviews.map(review => (
          <div key={review.id} className="card">
            <h3>{review.reviewType} Match</h3>
            <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginBottom: '1rem' }}>
              Reason: {review.reason}
            </p>
            <div className="flex-gap" style={{ marginTop: '1.5rem' }}>
              <button className="btn btn-success" style={{ flex: 1 }} onClick={() => handleApprove(review.id)}>
                Approve
              </button>
              <button className="btn btn-danger" style={{ flex: 1 }} onClick={() => handleReject(review.id)}>
                Reject
              </button>
            </div>
          </div>
        ))}
      </div>
      {!loading && reviews.length === 0 && (
        <div className="card" style={{ textAlign: 'center', padding: '3rem' }}>
          <h3>All caught up!</h3>
          <p style={{ color: 'var(--text-muted)', marginTop: '0.5rem' }}>There are no pending reviews at this time.</p>
        </div>
      )}
    </div>
  );
}

function MergeHistory() {
  const [merges, setMerges] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadMerges();
  }, []);

  const loadMerges = async () => {
    setLoading(true);
    try {
      const data = await fetchMergeHistory();
      setMerges(data.content || []);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const handleReverse = async (id) => {
    if (!window.confirm('Are you sure you want to reverse this merge? This will split the records apart.')) return;
    
    try {
      await reverseMerge(id, "Admin requested reversal from dashboard.");
      alert('Merge successfully reversed! ↩️');
      loadMerges();
    } catch (e) {
      console.error(e);
      alert('Failed to reverse merge.');
    }
  };

  return (
    <div>
      <h1>Merge History & Reversals ↩️</h1>
      <p style={{ marginBottom: '2rem', color: 'var(--text-muted)' }}>
        View past AI or manual deduplication merges and undo mistakes.
      </p>

      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Entity Type</th>
              <th>Winner ID</th>
              <th>Loser ID</th>
              <th>Reason</th>
              <th>Status</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {merges.map(merge => (
              <tr key={merge.id}>
                <td>{new Date(merge.mergedAt).toLocaleString()}</td>
                <td><span className="badge" style={{ background: 'var(--surface-light)', border: '1px solid var(--border)' }}>{merge.entityType}</span></td>
                <td style={{ fontSize: '0.8rem', color: 'var(--primary)' }}>{merge.winnerId.slice(0, 8)}...</td>
                <td style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{merge.loserId.slice(0, 8)}...</td>
                <td style={{ fontSize: '0.85rem' }}>{merge.reason}</td>
                <td>
                  <span className={`badge ${merge.status === 'APPLIED' ? 'approved' : 'pending'}`}>
                    {merge.status}
                  </span>
                </td>
                <td>
                  {merge.status === 'APPLIED' && (
                    <button className="btn btn-danger" style={{ padding: '0.25rem 0.75rem', fontSize: '0.8rem' }} onClick={() => handleReverse(merge.id)}>
                      Undo Merge
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {!loading && merges.length === 0 && (
              <tr>
                <td colSpan="7" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>No merges recorded yet.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function AuditLogs() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadLogs();
  }, []);

  const loadLogs = async () => {
    setLoading(true);
    try {
      const data = await fetchAuditLogs();
      setLogs(data.content || []);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  return (
    <div>
      <h1>System Audit Logs</h1>
      <p style={{ marginBottom: '2rem', color: 'var(--text-muted)' }}>
        A complete history of actions taken within the system.
      </p>
      <div className="table-container">
        <table>
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Actor</th>
              <th>Action</th>
              <th>Entity Type</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {logs.map(log => (
              <tr key={log.id}>
                <td>{new Date(log.createdAt).toLocaleString()}</td>
                <td style={{ fontWeight: '500' }}>{log.actor}</td>
                <td><span className="badge" style={{ background: 'var(--surface-light)', border: '1px solid var(--border)' }}>{log.action}</span></td>
                <td>{log.entityType}</td>
                <td style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{JSON.stringify(log.details)}</td>
              </tr>
            ))}
            {!loading && logs.length === 0 && (
              <tr>
                <td colSpan="5" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-muted)' }}>No audit logs found</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default App;
