import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown, Search, X } from 'lucide-react';

/**
 * Dropdown with a search box. Options look like { value, label?, hint?, count? }.
 * - serverSearch=false: loadOptions('') runs once on first open and the list is filtered locally.
 * - serverSearch=true: loadOptions(query) runs (debounced) every time the search text changes.
 */
export default function SearchableSelect({
  label, allLabel, placeholder, value, onChange, loadOptions,
  serverSearch = false, emptyText = 'No matches found',
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [options, setOptions] = useState([]);
  const [status, setStatus] = useState('idle');
  const [error, setError] = useState('');
  const [active, setActive] = useState(0);
  const rootRef = useRef(null);
  const triggerRef = useRef(null);
  const inputRef = useRef(null);
  const listRef = useRef(null);
  const loaded = useRef(false);
  const listId = useId();

  useEffect(() => {
    if (!open || (!serverSearch && loaded.current)) return undefined;
    let cancelled = false;
    const timer = setTimeout(async () => {
      setStatus('loading');
      setError('');
      try {
        const result = await loadOptions(serverSearch ? query.trim() : '');
        if (cancelled) return;
        setOptions(Array.isArray(result) ? result : []);
        setStatus('ready');
        loaded.current = true;
      } catch (e) {
        if (cancelled) return;
        setStatus('error');
        setError(e?.message || 'Could not load this list. Try again.');
      }
    }, serverSearch ? 250 : 0);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [open, query, serverSearch]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (open) inputRef.current?.focus();
    else setQuery('');
  }, [open]);

  useEffect(() => { setActive(0); }, [query, open]);

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = event => {
      if (rootRef.current && !rootRef.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (serverSearch || !q) return options;
    return options.filter(option =>
      String(option.label || option.value).toLowerCase().includes(q) ||
      String(option.value).toLowerCase().includes(q) ||
      String(option.hint || '').toLowerCase().includes(q));
  }, [options, query, serverSearch]);

  const items = useMemo(() => [{ value: '', label: allLabel, all: true }, ...visible], [visible, allLabel]);

  useEffect(() => {
    if (!open) return;
    listRef.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [active, open]);

  const choose = item => {
    onChange(item.value);
    setOpen(false);
    triggerRef.current?.focus();
  };

  const onKeyDown = event => {
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive(i => Math.min(i + 1, items.length - 1)); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(i => Math.max(i - 1, 0)); }
    else if (event.key === 'Enter') { event.preventDefault(); if (items[active]) choose(items[active]); }
    else if (event.key === 'Escape') { event.preventDefault(); setOpen(false); triggerRef.current?.focus(); }
    else if (event.key === 'Tab') setOpen(false);
  };

  const shown = value ? (options.find(option => option.value === value)?.label || value) : allLabel;

  return <div className={`ss${open ? ' open' : ''}${value ? ' has-value' : ''}`} ref={rootRef}>
    <button type="button" ref={triggerRef} className="ss-trigger" aria-haspopup="listbox" aria-expanded={open}
      onClick={() => setOpen(isOpen => !isOpen)} title={value ? `${label}: ${shown}` : label}>
      <span className="ss-text"><small>{label}</small><span>{shown}</span></span>
      <ChevronDown className="ss-caret" aria-hidden="true"/>
    </button>
    {value && <button type="button" className="ss-clear" aria-label={`Clear ${label}`} title={`Clear ${label}`}
      onClick={() => onChange('')}><X aria-hidden="true"/></button>}
    {open && <div className="ss-popover">
      <div className="ss-search">
        <Search aria-hidden="true"/>
        <input ref={inputRef} role="combobox" aria-expanded="true" aria-controls={listId} aria-autocomplete="list"
          aria-activedescendant={`${listId}-${active}`} value={query} placeholder={placeholder}
          onChange={event => setQuery(event.target.value)} onKeyDown={onKeyDown}/>
      </div>
      <ul className="ss-list" id={listId} role="listbox" aria-label={label} ref={listRef}>
        {items.map((item, index) => <li key={item.all ? '__all__' : item.value} id={`${listId}-${index}`} data-index={index}
          role="option" aria-selected={item.value === value}
          className={`ss-option${index === active ? ' active' : ''}${item.value === value ? ' selected' : ''}`}
          onMouseEnter={() => setActive(index)} onMouseDown={event => event.preventDefault()} onClick={() => choose(item)}>
          <span className="ss-option-main">
            <span className="ss-option-label">{item.label || item.value}</span>
            {item.hint && <small>{item.hint}</small>}
          </span>
          {!item.all && typeof item.count === 'number' &&
            <span className={`ss-count${item.count === 0 ? ' zero' : ''}`}>{item.count.toLocaleString()}</span>}
          {item.value === value && <Check className="ss-check" aria-hidden="true"/>}
        </li>)}
      </ul>
      {status === 'loading' && <div className="ss-status">Loading…</div>}
      {status === 'error' && <div className="ss-status error" role="alert">{error}</div>}
      {status === 'ready' && visible.length === 0 && <div className="ss-status">{emptyText}</div>}
    </div>}
  </div>;
}
