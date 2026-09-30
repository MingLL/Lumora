type Row = Record<string, string | number | null>;
type Summary = { from: string; to: string; totals: Row; trend: Row[]; articles: Row[]; sources: Row[]; visitors: Row[] };
type Visits = { total: number; page: number; pageSize: number; rows: Row[] };
type Geo = { location: string; status: string; latitude?: string; longitude?: string };
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const input = (id: string) => el<HTMLInputElement>(id);
const titles: Record<string, string> = JSON.parse(document.querySelector<HTMLElement>('[data-titles]')!.dataset.titles!);
const source = (value: Row[string]) => value == null ? '未知（未采集）' : value === '' ? '直接访问 / 无来源' : String(value);
const pageTitle = (path: Row[string]) => titles[String(path).replace(/\/$/, '')] ?? String(path || '/');
let generation = 0;
let visitGeneration = 0;
let controller = new AbortController();
let activeKey = '';
let activeRange = new URLSearchParams();
let currentPage = 0;
let totalVisits = 0;
let visitLoading = false;
let locations = new Map<string, Geo>();
let visitors: Row[] = [];
const date = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' });
input('to').value = date;
const start = new Date(`${date}T00:00:00Z`); start.setUTCDate(start.getUTCDate() - 6);
input('from').value = start.toISOString().slice(0, 10);

async function api<T>(path: string, params = activeRange): Promise<T> {
  const response = await fetch(`/api/analytics/${path}?${params}`, {
    headers: { 'X-Lumora-Admin-Key': activeKey, 'X-Request-Id': crypto.randomUUID() },
    cache: 'no-store', signal: controller.signal
  });
  if (!response.ok) throw new Error(response.status === 401 ? '管理员密钥不正确或未配置。' : response.status === 400 ? '请检查日期范围（最多 93 天）及 IP 格式。' : `查询失败（${response.status}），请稍后重试。`);
  return response.json();
}
function textCell(value: unknown) { const td = document.createElement('td'); td.textContent = String(value ?? '未知'); return td; }
function ipButton(ip: string) {
  const button = document.createElement('button'); button.type = 'button'; button.textContent = ip;
  button.addEventListener('click', () => { input('ip').value = ip; void loadVisits(0); el('visit-panel').scrollIntoView({ behavior: 'smooth' }); });
  return button;
}
function table(id: string, rows: Row[], columns: ((row: Row) => unknown)[]) {
  const body = el(id); body.replaceChildren();
  for (const row of rows) {
    const tr = document.createElement('tr');
    for (const column of columns) { const value = column(row); const td = value instanceof Node ? document.createElement('td') : textCell(value); if (value instanceof Node) td.append(value); tr.append(td); }
    body.append(tr);
  }
  if (!rows.length) { const tr = document.createElement('tr'); const td = textCell('此时间范围暂无记录'); td.colSpan = columns.length; tr.append(td); body.append(tr); }
}
function renderTrend(data: Summary) {
  const rows = new Map(data.trend.map(row => [row.day, Number(row.pv)]));
  const max = Math.max(1, ...rows.values());
  el('trend').replaceChildren();
  for (let day = new Date(`${data.from}T00:00:00Z`); day <= new Date(`${data.to}T00:00:00Z`); day.setUTCDate(day.getUTCDate() + 1)) {
    const key = day.toISOString().slice(0, 10); const pv = rows.get(key) ?? 0;
    const column = document.createElement('div'); column.className = 'day'; column.title = `${key}：${pv} 次浏览`;
    const value = document.createElement('span'); value.textContent = String(pv);
    const bar = document.createElement('div'); bar.className = 'bar'; bar.style.height = `${pv / max * 115}px`;
    const label = document.createElement('span'); label.textContent = key.slice(5);
    column.append(value, bar, label); el('trend').append(column);
  }
}
function renderVisitors() {
  table('visitors', visitors, [row => ipButton(String(row.ip)), row => locations.get(String(row.ip))?.location ?? '等待查询', row => row.pv, row => row.articles, row => row.last_seen]);
}
function renderMap() {
  const groups = new Map<string, { latitude: number; longitude: number; rows: Row[]; pv: number; location: string }>();
  for (const row of visitors) {
    const geo = locations.get(String(row.ip));
    if (geo?.latitude == null || geo.longitude == null) continue;
    const latitude = Number(geo.latitude), longitude = Number(geo.longitude);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) continue;
    const key = `${latitude.toFixed(2)},${longitude.toFixed(2)}`;
    const group = groups.get(key) ?? { latitude, longitude, rows: [], pv: 0, location: geo.location };
    group.rows.push(row); group.pv += Number(row.pv); groups.set(key, group);
  }
  el('map-points').replaceChildren();
  for (const group of groups.values()) {
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('cx', String((group.longitude + 180) / 360 * 1000));
    circle.setAttribute('cy', String((90 - group.latitude) / 180 * 500));
    circle.setAttribute('r', String(Math.min(22, 4 + Math.sqrt(group.pv) * 1.7)));
    circle.setAttribute('tabindex', '0'); circle.setAttribute('role', 'button');
    const description = `${group.location} · ${group.pv} 次访问 · ${group.rows.length} 个 IP`;
    circle.setAttribute('aria-label', description);
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title'); title.textContent = description; circle.append(title);
    const select = () => {
      el('map-detail').replaceChildren(); const p = document.createElement('p'); p.textContent = description; el('map-detail').append(p);
      for (const row of group.rows) el('map-detail').append(ipButton(String(row.ip)));
      if (group.rows.length === 1) { input('ip').value = String(group.rows[0].ip); void loadVisits(0); }
    };
    circle.addEventListener('click', select); circle.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(); } });
    el('map-points').append(circle);
  }
  const resolved = visitors.filter(row => locations.has(String(row.ip))).length;
  el('map-status').textContent = visitors.length ? `已查询 ${resolved} / ${visitors.length} 个 IP，地图标出 ${groups.size} 个位置。${resolved < visitors.length ? '归属地按服务额度逐步加载，首次查询可能需要数分钟。' : '没有坐标的访问仍可在下方查看。'}` : '此时间范围没有可定位的 IP。';
}
async function loadLocations(run: number) {
  for (const row of visitors) {
    if (run !== generation) return;
    const ip = String(row.ip);
    while (run === generation && !locations.has(ip)) {
      try {
        const geo = await api<Geo>('location', new URLSearchParams({ ip }));
        if (run !== generation) return;
        if (geo.status === 'pending') { await new Promise(resolve => setTimeout(resolve, 11000)); continue; }
        locations.set(ip, geo);
      } catch (error) {
        if (run !== generation) return;
        locations.set(ip, { location: '查询失败', status: 'unavailable' });
      }
      renderVisitors(); renderMap();
    }
  }
}
function pagination() {
  el<HTMLButtonElement>('prev').disabled = visitLoading || currentPage === 0;
  el<HTMLButtonElement>('next').disabled = visitLoading || (currentPage + 1) * 50 >= totalVisits;
}
async function loadVisits(page: number) {
  const run = ++visitGeneration; const mainRun = generation;
  visitLoading = true; pagination(); el('visit-status').textContent = '正在加载访问明细…';
  const params = new URLSearchParams(activeRange); params.set('ip', input('ip').value.trim()); params.set('page', String(page));
  try {
    const data = await api<Visits>('visits', params);
    if (run !== visitGeneration || mainRun !== generation) return;
    currentPage = page; totalVisits = data.total;
    table('visits', data.rows, [row => row.visited_at, row => row.ip ? ipButton(String(row.ip)) : '未知（历史记录）', row => pageTitle(row.path), row => source(row.source)]);
    el('page-info').textContent = `${page + 1} / ${Math.max(1, Math.ceil(data.total / 50))} 页 · ${data.total} 条`;
    el('visit-status').textContent = params.get('ip') ? `IP ${params.get('ip')} 在所选时间内的访问记录` : '所选时间内的全部页面访问';
  } catch (error) {
    if (run !== visitGeneration || mainRun !== generation) return;
    el('visits').replaceChildren(); el('page-info').textContent = ''; totalVisits = 0;
    el('visit-status').textContent = error instanceof Error ? error.message : '加载失败';
  } finally { if (run === visitGeneration && mainRun === generation) { visitLoading = false; pagination(); } }
}
el('filters').addEventListener('submit', async event => {
  event.preventDefault(); controller.abort(); controller = new AbortController(); const run = ++generation;
  activeKey = input('key').value; activeRange = new URLSearchParams({ from: input('from').value, to: input('to').value });
  el('results').hidden = true; el('status').textContent = '正在查询访问统计…';
  try {
    const data = await api<Summary>('summary'); if (run !== generation) return;
    input('ip').value = ''; el('map-detail').replaceChildren();
    for (const [id, field] of [['pv', 'pv'], ['ips', 'ips'], ['article-views', 'article_views'], ['unknown', 'unknown_ip_views']]) el(id).textContent = Number(data.totals[field]).toLocaleString();
    table('articles', data.articles, [row => pageTitle(row.path), row => row.pv, row => row.ips]);
    table('sources', data.sources, [row => source(row.source), row => row.pv]);
    visitors = data.visitors; renderVisitors(); renderMap(); renderTrend(data);
    el('results').hidden = false; el('status').textContent = `${data.from} 至 ${data.to} · ${Number(data.totals.pv) ? '统计已更新' : '暂无访问记录'}`;
    void loadVisits(0); void loadLocations(run);
  } catch (error) { if (run === generation) el('status').textContent = error instanceof Error ? error.message : '加载失败'; }
});
el('ip-filter').addEventListener('submit', event => { event.preventDefault(); void loadVisits(0); });
el('all-visits').addEventListener('click', () => { input('ip').value = ''; void loadVisits(0); });
el('prev').addEventListener('click', () => void loadVisits(Math.max(0, currentPage - 1)));
el('next').addEventListener('click', () => void loadVisits(currentPage + 1));
el('logout').addEventListener('click', () => {
  generation++; visitGeneration++; controller.abort(); activeKey = ''; input('key').value = ''; locations = new Map(); visitors = [];
  el('results').hidden = true; for (const id of ['visitors', 'visits', 'articles', 'sources', 'map-points', 'map-detail', 'trend']) el(id).replaceChildren();
  el('status').textContent = '已清空密钥和统计结果。';
});
