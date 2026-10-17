/* Inline visual components for the backend's {type,title,columns,rows} response. */
const A2UIRegistry = (() => {
  const esc = value => String(value ?? '—').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const slug = value => String(value ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '_');
  const fmt = value => {
    if (value === null || value === undefined || value === '') return '—';
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    if (typeof value === 'number') return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined,{maximumFractionDigits:2});
    return String(value);
  };
  const colIdx = (columns, tests) => columns.findIndex(c => tests.some(t => slug(c).includes(t)));
  const shell = (visual, kind, body, subtitle='') => `<section class="answer-card"><div class="visual-heading"><div class="visual-title">${esc(visual.title || 'Field intelligence')}</div><span class="visual-kind">${esc(kind)}</span></div>${subtitle?`<div class="visual-subtitle">${esc(subtitle)}</div>`:''}<div class="visual-body">${body}</div></section>`;

  function statusPill(value) {
    const down = value === false || /false|down|stopped|shut.?in|inactive/i.test(String(value));
    return `<span class="status-pill ${down?'down':''}"><i></i>${down?'Not producing':'Producing'}</span>`;
  }
  function card(v) {
    const columns=v.columns||[], row=(v.rows||[])[0]; if(!row) return '';
    const pi=colIdx(columns,['producing','is_producing']);
    const tiles=columns.map((name,i)=>{
      const key=slug(name), val=row[i];
      if(key.includes('producing')) return `<div class="status-tile"><div class="tile-label">${esc(name)}</div><div class="tile-value">${statusPill(val)}</div></div>`;
      const highlight=/well|asset/.test(key);
      return `<div class="status-tile"><div class="tile-label">${esc(name.replaceAll('_',' '))}</div><div class="tile-value ${highlight?'small':''}">${esc(fmt(val))}</div></div>`;
    }).join('');
    const headline=pi>=0?statusPill(row[pi]):'';
    return shell(v,'WELL STATUS',`<div style="display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px"><span class="status-tile tile-value small" style="padding:6px 9px;margin:0">${esc(fmt(row[0]))}</span>${headline}</div><div class="status-grid">${tiles}</div>`);
  }
  function table(v) {
    const columns=v.columns||[], rows=v.rows||[]; if(!columns.length||!rows.length) return '<div class="map-caption">No matching rows to display.</div>';
    const pi=colIdx(columns,['producing']);
    return shell(v,'RECORDS',`<div class="data-table-wrap"><table class="data-table"><thead><tr>${columns.map(c=>`<th>${esc(c.replaceAll('_',' '))}</th>`).join('')}</tr></thead><tbody>${rows.map(r=>`<tr>${columns.map((_,i)=>`<td class="${i===0?'mono':''}">${i===pi?statusPill(r[i]):esc(fmt(r[i]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`,`${rows.length} ${rows.length===1?'record':'records'}`);
  }
  function bar(v) {
    const columns=v.columns||[],rows=v.rows||[]; if(!columns.length||!rows.length)return table(v);
    let vi=columns.findIndex((c,i)=>i>0&&rows.some(r=>typeof r[i]==='number'));
    if(vi<0)vi=columns.length-1;
    const max=Math.max(...rows.map(r=>Number(r[vi])||0),1);
    const ai=colIdx(columns,['well','area','equipment','month','type']); const li=ai>=0?ai:0;
    const items=rows.map(r=>{const n=Math.max(0,Number(r[vi])||0),w=Math.max(n?2:0,n/max*100);return `<div class="bar-row"><div class="bar-name" title="${esc(r[li])}">${esc(fmt(r[li]))}</div><div class="bar-track"><div class="bar-fill" style="width:${w.toFixed(1)}%"></div></div><div class="bar-value">${esc(fmt(r[vi]))}</div></div>`}).join('');
    return shell(v,columns[vi].replaceAll('_',' ').toUpperCase(),`<div class="bar-list">${items}</div>`,`${rows.length} items · ranked by ${columns[vi].replaceAll('_',' ')}`);
  }
  function line(v) {
    const columns=v.columns||[],rows=v.rows||[]; if(rows.length<2||!columns.length)return table(v);
    const series=columns.map((name,i)=>({name,i})).filter(x=>x.i>0&&rows.some(r=>typeof r[x.i]==='number')).slice(0,3);
    if(!series.length)return table(v);
    const all=rows.flatMap(r=>series.map(s=>Number(r[s.i])).filter(Number.isFinite));let min=Math.min(...all),max=Math.max(...all);if(min===max){min-=1;max+=1;}
    const W=720,H=220,L=52,R=18,T=15,B=30, iw=W-L-R,ih=H-T-B;
    const x=i=>L+(rows.length===1?iw/2:i/(rows.length-1)*iw), y=n=>T+(max-n)/(max-min)*ih;
    const grid=[0,.5,1].map(p=>{const yy=T+p*ih,val=max-p*(max-min);return `<line class="chart-grid" x1="${L}" x2="${W-R}" y1="${yy}" y2="${yy}"/><text class="chart-axis" x="${L-8}" y="${yy+3}" text-anchor="end">${esc(fmt(val))}</text>`}).join('');
    const paths=series.map((s,k)=>{const points=rows.map((r,i)=>`${x(i)},${y(Number(r[s.i])||0)}`).join(' ');const color=['#c4e88d','#8ebfe5','#e3aa84'][k];return `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>${rows.map((r,i)=>`<circle class="chart-dot" cx="${x(i)}" cy="${y(Number(r[s.i])||0)}" r="2.4" style="fill:${color}"><title>${esc(r[0])}: ${esc(r[s.i])}</title></circle>`).join('')}`}).join('');
    const labels=[rows[0]?.[0],rows[Math.floor((rows.length-1)/2)]?.[0],rows.at(-1)?.[0]];
    const axis=labels.map((label,j)=>`<text class="chart-axis" x="${[L,x(Math.floor((rows.length-1)/2)),W-R][j]}" y="${H-7}" text-anchor="${['start','middle','end'][j]}">${esc(label)}</text>`).join('');
    const legend=series.map((s,k)=>`<span><i class="legend-swatch" style="background:${['#c4e88d','#8ebfe5','#e3aa84'][k]}"></i>${esc(s.name.replaceAll('_',' '))}</span>`).join('');
    return shell(v,'TREND',`<div class="chart-wrap"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(v.title||'Trend chart')}">${grid}${paths}${axis}</svg></div><div class="chart-legend">${legend}</div>`,`${rows.length} observations`);
  }
  function map(v) {
    const columns=v.columns||[],rows=v.rows||[],latI=colIdx(columns,['lat']),lonI=colIdx(columns,['lon']);if(latI<0||lonI<0||!rows.length)return table(v);
    const points=rows.map((r,index)=>({r,index,lat:Number(r[latI]),lon:Number(r[lonI])})).filter(p=>p.r[latI]!==null&&p.r[latI]!==''&&p.r[lonI]!==null&&p.r[lonI]!==''&&Number.isFinite(p.lat)&&Number.isFinite(p.lon));if(!points.length)return table(v);
    const W=760,H=380,L=76,R=28,T=24,B=58,centerLat=points.reduce((s,p)=>s+p.lat,0)/points.length,cosLat=Math.max(.05,Math.cos(centerLat*Math.PI/180));
    // Correct longitude distances by latitude so east-west spacing is not exaggerated.
    points.forEach(p=>p.gx=p.lon*cosLat);const minX=Math.min(...points.map(p=>p.gx)),maxX=Math.max(...points.map(p=>p.gx)),minY=Math.min(...points.map(p=>p.lat)),maxY=Math.max(...points.map(p=>p.lat));
    const spanX=Math.max(maxX-minX,.001),spanY=Math.max(maxY-minY,.001),x0=minX-spanX*.08,x1=maxX+spanX*.08,y0=minY-spanY*.08,y1=maxY+spanY*.08;
    const x=n=>L+(n-x0)/(x1-x0)*(W-L-R),y=n=>H-B-(n-y0)/(y1-y0)*(H-T-B),coord=(n,suffix)=>`${Math.abs(n).toFixed(3)}°${n<0?(suffix==='E'?'W':'S'):suffix}`;
    const labelI=colIdx(columns,['well','asset','inspection','event','record']),areaI=colIdx(columns,['area','region']),statusI=colIdx(columns,['producing','status']),volumeI=columns.findIndex((c,i)=>/bbl|volume|spill/i.test(c)&&rows.some(r=>Number.isFinite(Number(r[i]))));
    const statusValue=p=>statusI<0?'':String(p.r[statusI]??'').toLowerCase();
    const areaColors=['#8fd1c1','#9ec7f1','#e8bc78','#d6a5e9','#e99d8b','#c4e88d','#e889bb','#99b5bd'];
    const areas=areaI>=0?[...new Set(points.map(p=>String(p.r[areaI]??'Unknown area')))].sort():[];
    const color=p=>{const s=statusValue(p);if(statusI>=0){if(p.r[statusI]===false||/down|stopped|shut|inactive|not producing/i.test(s))return '#ef927b';if(p.r[statusI]===true||/producing|active|online/i.test(s))return '#a7d987';return '#c5c6a8'}if(areaI>=0)return areaColors[Math.max(0,areas.indexOf(String(p.r[areaI]??'Unknown area')))%areaColors.length];return '#9fc7ed'};
    const maxVolume=volumeI>=0?Math.max(...points.map(p=>Number(p.r[volumeI])||0),0):0;
    const grouped=new Map();points.forEach(p=>{const key=`${p.lat.toFixed(5)},${p.lon.toFixed(5)}`;if(!grouped.has(key))grouped.set(key,[]);grouped.get(key).push(p)});const markers=[...grouped.values()],labels=markers.length<=22;
    const dots=markers.map(members=>{const p=members[0],baseRadius=volumeI>=0?4.5+5*Math.sqrt(Math.max(0,Number(p.r[volumeI])||0)/(maxVolume||1)):6,radius=baseRadius+Math.min(8,(members.length-1)*1.8);const label=String(p.r[labelI>=0?labelI:0]??`Point ${p.index+1}`)+(members.length>1?` +${members.length-1}`:'');const detail=members.map(item=>columns.map((c,i)=>`${c.replaceAll('_',' ')}: ${fmt(item.r[i])}`).join(' · ')).join(' | ');return `<g class="map-point"><circle class="map-dot" cx="${x(p.gx)}" cy="${y(p.lat)}" r="${radius.toFixed(1)}" style="--point-color:${color(p)}"><title>${esc(detail)}</title></circle>${members.length>1?`<text class="map-cluster-count" x="${x(p.gx)}" y="${y(p.lat)+3}" text-anchor="middle">${members.length}</text>`:''}${labels?`<text class="map-point-label" x="${x(p.gx)+radius+3}" y="${y(p.lat)-radius-2}">${esc(label)}</text>`:''}</g>`}).join('');
    const grid=Array.from({length:5},(_,i)=>{const gx=L+i*(W-L-R)/4,gy=T+i*(H-T-B)/4,lon=((x0+i*(x1-x0)/4)/cosLat),lat=y1-i*(y1-y0)/4;return `<line class="map-grid" x1="${gx}" x2="${gx}" y1="${T}" y2="${H-B}"/><line class="map-grid" x1="${L}" x2="${W-R}" y1="${gy}" y2="${gy}"/><text class="map-axis-label" x="${gx}" y="${H-B+19}" text-anchor="middle">${coord(lon,'E')}</text><text class="map-axis-label" x="${L-9}" y="${gy+3}" text-anchor="end">${coord(lat,'N')}</text>`}).join('');
    const legend=statusI>=0?`<span><i class="map-legend-dot producing"></i>Producing</span><span><i class="map-legend-dot stopped"></i>Not producing</span><span><i class="map-legend-dot unknown"></i>Status unknown</span>`:areaI>=0?areas.map((a,i)=>`<span><i class="map-legend-dot" style="background:${areaColors[i%areaColors.length]}"></i>${esc(a)}</span>`).join(''):`<span><i class="map-legend-dot location"></i>Recorded location</span>`;
    const volumeLegend=volumeI>=0?`<span class="map-volume-legend">Marker size: ${esc(columns[volumeI].replaceAll('_',' '))} (${esc(fmt(Math.min(...points.map(p=>Number(p.r[volumeI])||0))))}–${esc(fmt(maxVolume))})</span>`:'';
    const north=`<g class="map-north" transform="translate(${W-R-18},${T+25})"><text x="0" y="-8" text-anchor="middle">N</text><path d="M0 0 L-6 15 L0 12 L6 15 Z"/></g>`;
    const details=`<details class="map-record-details"><summary>Location records · ${points.length}</summary><div class="data-table-wrap"><table class="data-table"><thead><tr>${columns.map(c=>`<th>${esc(c.replaceAll('_',' '))}</th>`).join('')}</tr></thead><tbody>${points.map(p=>`<tr>${columns.map((_,i)=>`<td>${esc(fmt(p.r[i]))}</td>`).join('')}</tr>`).join('')}</tbody></table></div></details>`;
    return shell(v,'FIELD MAP',`<div class="map-wrap"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Geographic coordinate map showing ${points.length} recorded locations">${grid}<line class="map-axis" x1="${L}" x2="${W-R}" y1="${H-B}" y2="${H-B}"/><line class="map-axis" x1="${L}" x2="${L}" y1="${T}" y2="${H-B}"/>${dots}${north}<text class="map-axis-title" x="${(L+W-R)/2}" y="${H-8}" text-anchor="middle">LONGITUDE</text><text class="map-axis-title" transform="translate(15 ${(T+H-B)/2}) rotate(-90)" text-anchor="middle">LATITUDE</text></svg></div><div class="map-legend">${legend}${volumeLegend}</div><div class="map-caption">${points.length} plotted records · WGS84 coordinates · schematic view, not a street or satellite basemap · hover a marker for its source values</div>${details}`);
  }
  function heatmap(v) {
    const columns=v.columns||[],rows=v.rows||[];if(columns.length<2||!rows.length)return table(v);
    const xFound=colIdx(columns,['month','date']),yFound=colIdx(columns,['area','region']);
    const xI=xFound>=0?xFound:(yFound===0?1:0),yI=yFound>=0?yFound:(xI===0?1:0);
    const valueI=columns.findIndex((_,i)=>i!==xI&&i!==yI&&rows.some(r=>typeof r[i]==='number'));
    const groups=new Map(),xs=new Set(),ys=new Set();
    rows.forEach(r=>{const x=fmt(r[xI]),y=fmt(r[yI]),key=`${x}\u0000${y}`;xs.add(x);ys.add(y);groups.set(key,valueI>=0?Number(r[valueI])||0:(groups.get(key)||0)+1)});
    const xLabels=[...xs],yLabels=[...ys];
    const values=[...groups.entries()].filter(([key])=>!key.endsWith('\u0000Total')).map(([,n])=>n);
    const max=Math.max(...values,1);
    const body=`<div class="heatmap-scroll"><table class="heatmap"><thead><tr><th>${esc(columns[yI])} / ${esc(columns[xI])}</th>${xLabels.map(x=>`<th>${esc(x)}</th>`).join('')}</tr></thead><tbody>${yLabels.map(y=>`<tr><th>${esc(y)}</th>${xLabels.map(x=>{const n=groups.get(`${x}\u0000${y}`)||0,alpha=n?(.16+.68*Math.min(1,n/max)):.035;return `<td title="${esc(x)} · ${esc(y)}: ${n}" style="background:rgba(190,231,135,${alpha})">${n||'·'}</td>`}).join('')}</tr>`).join('')}</tbody></table></div>`;
    return shell(v,'DISTRIBUTION',body,`${rows.length} cells · ${valueI>=0?`values from ${columns[valueI].replaceAll('_',' ')}`:`grouped by ${columns[xI].replaceAll('_',' ')} and ${columns[yI].replaceAll('_',' ')}`}`);
  }
  function render(v) {
    if(!v||!Array.isArray(v.rows)||!Array.isArray(v.columns)||!v.rows.length)return '';
    const type=String(v.type||'table').toLowerCase();
    if(type==='card'||type==='status_card')return card(v);
    if(type==='bar'||type==='bar_chart')return bar(v);
    if(type==='line'||type==='line_chart')return line(v);
    if(type==='map')return map(v);
    if(type==='heatmap')return heatmap(v);
    return table(v);
  }
  return {render,escape:esc};
})();
