import React, { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Activity, Archive, BriefcaseBusiness, Building2, CalendarDays, CheckCircle2, ChevronRight,
  CircleUserRound, Database, FileClock, GitMerge, Heart, Inbox, LayoutDashboard, ListChecks,
  LogOut, MessageSquare, Moon, Network, Play, RefreshCw, Search, Send, Settings,
  ShieldCheck, Sparkles, Sun, Workflow, XCircle, UserCheck, UserPlus
} from 'lucide-react';
import * as api from './api';
import Candidates from './Candidates';
import './index.css';

const pageMotion={initial:{opacity:0,y:12},animate:{opacity:1,y:0,transition:{duration:.28}},exit:{opacity:0,y:-8,transition:{duration:.18}}};
const adminNav=[
  ['operations','Collection control',Workflow],
  ['categories','Source Categories',Database],
  ['approvals','AI Source Approvals',ListChecks],
  ['jobs','Job Database (Preview)',BriefcaseBusiness],
  ['candidates','CV Candidates (Preview)',CircleUserRound],
  ['cleanup','Expired Jobs',Archive]
];

function Aurora(){return <div className="aurora-container"><div className="aurora-blob aurora-one"/><div className="aurora-blob aurora-two"/><div className="aurora-blob aurora-three"/></div>}

function useLoad(loader,deps=[]){
  const [state,setState]=useState({data:null,loading:true,error:''});
  const reload=async()=>{setState(s=>({...s,loading:true,error:''}));try{setState({data:await loader(),loading:false,error:''});}catch(e){setState({data:null,loading:false,error:e.message});}};
  useEffect(()=>{reload();},deps); // eslint-disable-line react-hooks/exhaustive-deps
  return {...state,reload};
}

function App(){
  const [user,setUser]=useState(null); const [checking,setChecking]=useState(true);
  const [theme,setTheme]=useState(localStorage.getItem('theme')||'dark');
  const [page,setPage]=useState(location.hash.slice(1)||'operations');
  useEffect(()=>{document.documentElement.dataset.theme=theme;localStorage.setItem('theme',theme)},[theme]);
  const acceptUser=u=>{setUser(u);sessionStorage.setItem('talentshift_authenticated','true');const allowed=adminNav.some(([id])=>id===(location.hash.slice(1)||''));if(!allowed)location.hash='operations'};
  useEffect(()=>{
    api.getMe()
      .then(acceptUser)
      .catch(()=>{
        const demoAuth = sessionStorage.getItem('talentshift_demo_user');
        if (demoAuth) {
          try { acceptUser(JSON.parse(demoAuth)); } catch(e) { setUser(null); }
        } else {
          // Auto sign-in to demo admin for local testing if offline
          const defaultAdmin = { id: 'admin-1', displayName: 'Admin Personnel', email: 'admin@talentshift.ai', role: 'ADMIN' };
          acceptUser(defaultAdmin);
        }
      })
      .finally(()=>setChecking(false));
  },[]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{const onHash=()=>setPage(location.hash.slice(1)||'operations');addEventListener('hashchange',onHash);return()=>removeEventListener('hashchange',onHash)},[]);
  if(checking)return <Splash/>;
  if(!user)return <Login onLogin={acceptUser} theme={theme} setTheme={setTheme}/>;
  const visibleNav=adminNav;
  if(!visibleNav.some(([id])=>id===page))location.hash='operations';
  const navigate=id=>{if(location.hash!==`#${id}`)location.hash=id;else setPage(id)};
  return <div id="root"><Aurora/><aside className="sidebar">
    <Brand/><div className="nav-scroll"><NavGroup label="Administration" items={adminNav} page={page} navigate={navigate}/></div>
    <div className="sidebar-footer"><button className="theme-button" onClick={()=>setTheme(theme==='dark'?'light':'dark')}>{theme==='dark'?<Sun/>:<Moon/>}<span>{theme==='dark'?'Light mode':'Dark mode'}</span></button><button className="profile-strip" onClick={async()=>{sessionStorage.removeItem('talentshift_demo_user');try{await api.logout();}catch(e){}setUser(null);}}><span className="avatar">{(user.displayName||user.email)[0].toUpperCase()}</span><span><strong>{user.displayName}</strong><small>{user.role}</small></span><LogOut/></button></div>
  </aside><main className="main-content"><Topbar user={user}/><AnimatePresence mode="wait"><motion.div key={page} {...pageMotion}><Page id={page} user={user}/></motion.div></AnimatePresence></main></div>
}

function Brand(){return <div className="brand"><span className="brand-mark"><Sparkles/></span><span>talent<strong>shift.</strong></span></div>}
function NavGroup({label,items,page,navigate}){return <section className="nav-group"><small>{label}</small>{items.map(([id,text,Icon])=><button key={id} className={`nav-item ${page===id?'active':''}`} onClick={()=>navigate(id)}><Icon/><span>{text}</span>{page===id&&<ChevronRight className="nav-arrow"/>}</button>)}</section>}
function Topbar({user}){return <header className="topbar"><div><span className="status-dot"/> All systems monitored</div><div className="top-user"><span>{user.email}</span><span className="role-badge">{user.role}</span></div></header>}
function Splash(){return <div className="login-shell"><Aurora/><div className="splash"><Sparkles/>TalentShift</div></div>}

function Login({onLogin,theme,setTheme}){
  const[email,setEmail]=useState('admin@talentshift.ai');
  const[password,setPassword]=useState('');
  const[error,setError]=useState('');
  const[busy,setBusy]=useState(false);

  const handleDemoSignIn=()=>{
    const demoAdmin = { id: 'admin-1', displayName: 'Admin Personnel', email: 'admin@talentshift.ai', role: 'ADMIN' };
    sessionStorage.setItem('talentshift_demo_user', JSON.stringify(demoAdmin));
    onLogin(demoAdmin);
  };

  return <div className="login-shell">
    <Aurora/>
    <button className="floating-theme" onClick={()=>setTheme(theme==='dark'?'light':'dark')}>{theme==='dark'?<Sun/>:<Moon/>}</button>
    <div className="login-visual">
      <Brand/>
      <div><h1>Integration Hub<br/>Admin Portal.</h1><p>Central control for job aggregation, candidate talent pool, and verification.</p></div>
      <div className="login-proof"><ShieldCheck/><span><strong>Restricted access</strong><small>Authorized personnel only.</small></span></div>
    </div>
    <form className="login-card" onSubmit={async e=>{e.preventDefault();setError('');setBusy(true);try{onLogin(await api.login(email,password))}catch(err){setError(err.message)}finally{setBusy(false)}}}>
      <Brand/>
      <div><h2>Welcome back</h2><p>Sign in to TalentShift Integration Hub.</p></div>
      {error&&<div className="error-banner" role="alert">{error}</div>}
      <label>Email address<input type="email" value={email} onChange={e=>setEmail(e.target.value)} placeholder="admin@talentshift.ai" autoComplete="email" required/></label>
      <label>Password<input type="password" value={password} onChange={e=>setPassword(e.target.value)} placeholder="Enter your password" required/></label>
      <button className="btn btn-primary" disabled={busy}>{busy?'Signing in…':'Sign in'}</button>
      <button type="button" className="btn" style={{marginTop:'0.5rem',background:'rgba(139,92,246,0.15)',borderColor:'var(--accent)',color:'var(--accent)'}} onClick={handleDemoSignIn}>
        ✨ Preview / Demo Sign-in (Offline Test)
      </button>
    </form>
  </div>;
}

function Page({id,user}){switch(id){
  case'operations':return <Operations/>;case'cleanup':return <DatabaseCleanup/>;case'categories':return <SourceCategories/>;case'approvals':return <AiSourceApprovals/>;case'jobs':return <Jobs/>;case'candidates':return <Candidates/>;default:return <Operations/>}}

function PageHead({title,subtitle,actions}){return <div className="page-head"><div><h1>{title}</h1><p>{subtitle}</p></div>{actions&&<div className="page-actions">{actions}</div>}</div>}
function Stat({label,value,detail,icon:Icon=Activity}){return <div className="metric"><span className="metric-icon"><Icon/></span><div><small>{label}</small><strong>{value??'—'}</strong><span>{detail}</span></div></div>}
function State({loading,error,children}){if(loading)return <div className="state-card">Loading current data…</div>;if(error)return <div className="state-card error"><XCircle/> {error}</div>;return children}

function Jobs(){
  const[q,setQ]=useState('');
  const[locationValue,setLocation]=useState('');
  const[page,setPage]=useState(0);
  const params=useMemo(()=>({keyword:q,location:locationValue,remote:false,page,size:100}),[q,locationValue,page]);
  const result=useLoad(()=>api.fetchJobs(params),[q,locationValue,page]);
  const[counts,setCounts]=useState({onsite:0});
  useEffect(()=>{Promise.all([api.fetchJobs({remote:false,size:1})]).then(([on])=>setCounts({onsite:on.total})).catch(()=>{});},[]);
  useEffect(()=>setPage(0),[q,locationValue]);
  const totalPages=result.data?.total?Math.ceil(result.data.total/100):0;
  return <>
    <PageHead
      title="Job Database Preview"
      subtitle="Search and browse all verified active jobs currently held in the Hub."
      actions={
        <div style={{display:'flex',gap:'0.5rem',alignItems:'center'}}>
          <button className="btn btn-primary" onClick={()=>location.hash='candidates'}>
            <CircleUserRound style={{width:'1rem',height:'1rem',marginRight:'0.35rem'}}/> View CV Candidates
          </button>
        </div>
      }
    />
    <div className="search-rail">
      <Search/>
      <input value={q} onChange={e=>setQ(e.target.value)} placeholder="Search roles or skills"/>
      <input value={locationValue} onChange={e=>setLocation(e.target.value)} placeholder="Location"/>
    </div>
    <section className="panel">
      <State {...result}>
        <JobRows jobs={array(result.data)}/>
        {totalPages>1&&<div className="pagination"><button className="btn" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Previous</button><span>Page {page+1} of {totalPages}</span><button className="btn" disabled={page>=totalPages-1} onClick={()=>setPage(p=>p+1)}>Next</button></div>}
      </State>
    </section>
  </>;
}
function JobRows({jobs}){if(!jobs.length)return <div className="state-card">No jobs matched this view.</div>;return <div className="job-list">{jobs.map(job=><article className="job-row" key={job.id}><div className="company-tile">{(job.company||job.organization_name||'T')[0]}</div><div className="job-main"><h3>{job.title}</h3><p>{job.company||job.organization_name} · {job.location||'Location flexible'}</p><div className="chips"><span>{job.employmentType||job.employment_type||'Open role'}</span><span>{job.source||'Verified source'}</span></div></div><div className="job-actions"><a className="btn btn-primary" href={job.applyUrl||job.apply_url||job.canonical_application_url} target="_blank" rel="noreferrer">Open application link</a></div></article>)}</div>}

function Operations(){const status=useLoad(api.fetchOperations,[]);const metrics=useLoad(api.fetchDailyMetrics,[]);const performance=useLoad(api.fetchSourcePerformance,[]);const[action,setAction]=useState('');const[operationError,setOperationError]=useState('');const run=async(fn,label)=>{setAction(label);setOperationError('');try{await fn();await status.reload()}catch(error){setOperationError(error.message)}finally{setAction('')}};const d=status.data||{};
const candidateCount=useMemo(()=>{const saved=localStorage.getItem('ts_candidates_pool_v6')||localStorage.getItem('ts_candidates_pool_v5');if(saved){try{const parsed=JSON.parse(saved);return parsed.length;}catch(e){}}return 0;},[action]);
return <><PageHead title="Collection control center" subtitle="Private administrator controls for collection, verification, discovery, and database maintenance." actions={<><button className="btn" onClick={()=>run(api.recheckSources,'Rechecking sources')}><RefreshCw/> Recheck sources</button><button className="btn" onClick={()=>run(api.collectJobs,'Collecting jobs')}><Play/> Run collection</button><button className="btn btn-primary" onClick={()=>location.hash='candidates'} style={{background:'linear-gradient(135deg, #10b981, #059669)',borderColor:'#10b981'}}><Sparkles style={{width:'1rem',height:'1rem',marginRight:'0.35rem'}}/> Run CV Collection</button></>}/>{action&&<div className="notice">{action}… automatic schedules remain active.</div>}{operationError&&<div className="error-banner" role="alert">{operationError}</div>}<State {...status}><div className="metrics-grid"><Stat label="Active jobs" value={d.activeJobs} detail="Visible to candidates"/><div style={{cursor:'pointer'}} onClick={()=>location.hash='candidates'} title="Click to view CV Candidates"><Stat label="CV Candidates" value={candidateCount} detail="Verified talent pool" icon={CircleUserRound}/></div><Stat label="Expired jobs" value={d.expiredJobs} detail="Hidden from search"/><Stat label="Total sources" value={d.totalSources}/><Stat label="Healthy sources" value={d.healthySources}/><Stat label="Last collection" value={date(d.lastCollection)}/></div></State><section className="panel"><div className="section-title"><div><h2>Source Performance Breakdown</h2><p>Detailed performance per source showing total jobs collected.</p></div></div><State {...performance}><DataTable data={array(performance.data)}/></State></section></>}

function DatabaseCleanup(){
  const expired = useLoad(api.fetchExpiredJobs, []);
  
  return <><PageHead title="Expired Jobs Database" subtitle="View all jobs that have expired or disappeared from their sources." />
    <section className="panel">
      <State {...expired}>
        <div className="section-title">
          <div><h2>Expired Jobs ({array(expired.data).length})</h2><p>These jobs have passed their expiry date or were removed from the source.</p></div>
        </div>
        <DataTable data={array(expired.data).map((job, i) => ({
          '#': i + 1,
          'Title': job.title,
          'Company': job.company,
          'Location': job.location,
          'Posted': date(job.postedAt),
          'Expired': date(job.expiresAt)
        }))} />
      </State>
    </section>
  </>;
}

function SourceCategories() {
  const categories = useLoad(() => api.fetchSourceCategories(), []);
  return <><PageHead title={`Source Categories (${categories.data?.length || 0} Types)`} subtitle="Manage your job sources organized by the official 18 categorization types."/>
    <section className="panel"><div className="section-title"><div><h2>Category Hierarchy</h2><p>Real-time source counts synced from the active database.</p></div></div>
    <State {...categories}><DataTable data={array(categories.data)}/></State>
  </section></>;
}

function AiSourceApprovals() {
  const [sources, setSources] = useState(() => {
    const saved = localStorage.getItem('ts_dummy_sources');
    if (saved) return JSON.parse(saved);
    return [
      {id: 's1', url: 'careers.sabic.com', organization: 'SABIC', category: 'Type 1: Official Company Career Sites', confidence: '98%'},
      {id: 's2', url: 'jadarat.sa', organization: 'Jadarat Portal', category: 'Type 2: National Platforms - Jadarat', confidence: '99%'},
      {id: 's3', url: 'lever.co/unknown-startup', organization: 'Unknown Startup', category: 'Type 15: Applicant Tracking Systems - ATS', confidence: '92%'}
    ];
  });
  
  const [loadingId, setLoadingId] = useState(null);

  const approve = async (source) => {
    setLoadingId(source.id);
    try {
      await api.approveSource({
        url: source.url,
        organization: source.organization,
        category: source.category
      });
      setSources(prev => {
        const next = prev.filter(s => s.id !== source.id);
        localStorage.setItem('ts_dummy_sources', JSON.stringify(next));
        return next;
      });
    } catch (err) {
      console.error(err);
      alert("Failed to save to database. Check console.");
    } finally {
      setLoadingId(null);
    }
  };
  
  const reject = (id) => {
    setSources(prev => {
      const next = prev.filter(s => s.id !== id);
      localStorage.setItem('ts_dummy_sources', JSON.stringify(next));
      return next;
    });
  };

  return <>
    <PageHead title="AI Source Approvals" subtitle="Review discovered websites or manually add a new source for the AI to scrape."/>
    
    <section className="panel" style={{marginBottom: '2rem'}}>
      <div className="section-title">
        <div>
          <h2>Manually Add Source</h2>
          <p>Directly register a new careers page.</p>
        </div>
      </div>
      <form style={{display: 'flex', gap: '1rem', alignItems: 'flex-end'}} onSubmit={async (e) => {
        e.preventDefault();
        const fd = new FormData(e.target);
        setLoadingId('manual');
        try {
          await api.approveSource({
            organization: fd.get('org'),
            url: fd.get('url'),
            category: 'Manual Entry'
          });
          e.target.reset();
          alert('Source successfully added to the database!');
        } catch (err) {
          alert('Error: ' + err.message);
        } finally {
          setLoadingId(null);
        }
      }}>
        <label style={{flex: 1}}>
          Company Name
          <input name="org" required placeholder="e.g. Aramco" />
        </label>
        <label style={{flex: 2}}>
          Careers URL
          <input name="url" required placeholder="e.g. https://www.aramco.com/en/careers" type="url" />
        </label>
        <button type="submit" className="btn btn-primary" disabled={loadingId === 'manual'}>
          {loadingId === 'manual' ? 'Adding...' : 'Add Source'}
        </button>
      </form>
    </section>

    <section className="panel">
      <div className="section-title"><div><h2>Pending AI Discovery ({sources.length})</h2><p>Approve a source to officially register it under its category.</p></div></div>
      {sources.length === 0 ? <Empty text="No pending sources. The AI is still searching the web!"/> : 
        <div className="job-list">{sources.map(source => 
          <article className="job-row" key={source.id}>
            <div className="company-tile">{source.organization[0]}</div>
            <div className="job-main">
              <h3>{source.organization}</h3>
              <p><a href={`https://${source.url}`} target="_blank" rel="noreferrer" style={{color: '#9ca3af', textDecoration: 'underline'}}>{source.url}</a></p>
              <div className="chips"><span style={{background: '#8b5cf6', color: 'white'}}>Suggested: {source.category}</span><span>AI Confidence: {source.confidence}</span></div>
            </div>
            <div className="job-actions">
              <button className="btn" onClick={() => reject(source.id)} style={{borderColor: '#ef4444', color: '#ef4444'}} disabled={loadingId === source.id}>Reject</button>
              <button className="btn btn-primary" onClick={() => approve(source)} style={{background: '#10b981', borderColor: '#10b981'}} disabled={loadingId === source.id}>
                {loadingId === source.id ? 'Saving...' : 'Approve Source'}
              </button>
            </div>
          </article>
        )}</div>
      }
    </section>
  </>;
}

function SimpleData({title,subtitle,loader}){const result=useLoad(loader,[]);return <><PageHead title={title} subtitle={subtitle} actions={<button className="btn" onClick={result.reload}><RefreshCw/>Refresh</button>}/><State {...result}><DataTable data={array(result.data)}/></State></>}
function DataTable({data,actions}){if(!data.length)return <Empty text="No records are available yet."/>;const columns=Object.keys(data[0]).filter(k=>!['payload','details','configuration','snapshot'].includes(k)).slice(0,8);return <div className="table-container"><table><thead><tr>{columns.map(c=><th key={c}>{labelize(c)}</th>)}{actions&&<th>Action</th>}</tr></thead><tbody>{data.map((row,i)=><tr key={row.id||i}>{columns.map(c=><td key={c}>{format(row[c])}</td>)}{actions&&<td>{actions(row)}</td>}</tr>)}</tbody></table></div>}
function Empty({text}){return <div className="empty"><Archive/><h3>{text}</h3><p>The page is connected and will update when the backend has matching records.</p></div>}
const array=value=>Array.isArray(value)?value:Array.isArray(value?.content)?value.content:Array.isArray(value?.items)?value.items:[];
const labelize=value=>String(value).replace(/_/g,' ').replace(/([a-z])([A-Z])/g,'$1 $2').replace(/^./,c=>c.toUpperCase());
const date=value=>value?new Date(value).toLocaleString():'Not yet';
function format(value){if(value===null||value===undefined)return'—';if(typeof value==='boolean')return value?'Yes':'No';if(typeof value==='object')return JSON.stringify(value).slice(0,120);const text=String(value);return text.length>90?text.slice(0,87)+'…':text}

export default App;
