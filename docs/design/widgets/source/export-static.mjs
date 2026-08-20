// Превращает артборды дизайн-канваса в самостоятельные HTML-страницы:
// вычисляет renderVals() в Node и раскрывает шаблон (holes, sc-for, sc-if) в готовую разметку.
// Интерактив (onClick) вырезается — страницы статические, годятся для докуменации и скриншотов.
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = process.argv[2] || join(here, 'static');
mkdirSync(outDir, { recursive: true });

class DCLogic {
  constructor(props) {
    this.props = props || {};
    this.state = {};
  }
  setState() {}
  forceUpdate() {}
}

function defaultProps(json) {
  const out = {};
  for (const [k, v] of Object.entries(json)) {
    if (k.startsWith('$')) continue;
    if (v && typeof v === 'object' && 'default' in v) out[k] = v.default;
  }
  return out;
}

function lookup(path, scopes) {
  const parts = path.trim().split('.');
  if (parts[0] === 'true') return true;
  if (parts[0] === 'false') return false;
  if (parts[0] === 'null') return null;
  let cur;
  let found = false;
  for (let i = scopes.length - 1; i >= 0; i--) {
    if (scopes[i] && Object.prototype.hasOwnProperty.call(scopes[i], parts[0])) {
      cur = scopes[i][parts[0]];
      found = true;
      break;
    }
  }
  if (!found) return undefined;
  for (let i = 1; i < parts.length; i++) {
    if (cur === null || cur === undefined) return undefined;
    cur = cur[parts[i]];
  }
  return cur;
}

const escapeHtml = (s) =>
  String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

// Находит закрывающий тег для блока, учитывая вложенность одноимённых тегов.
function blockEnd(src, tag, from) {
  const open = new RegExp('<' + tag + '[\\s>]', 'g');
  const close = new RegExp('</' + tag + '>', 'g');
  let depth = 1;
  let i = from;
  while (depth > 0) {
    open.lastIndex = i;
    close.lastIndex = i;
    const o = open.exec(src);
    const c = close.exec(src);
    if (!c) throw new Error('нет закрывающего </' + tag + '>');
    if (o && o.index < c.index) { depth++; i = o.index + 1; continue; }
    depth--;
    i = c.index + 1;
    if (depth === 0) return { inner: [from, c.index], after: c.index + tag.length + 3 };
  }
  throw new Error('не сошлось');
}

function render(src, scopes) {
  let out = '';
  let pos = 0;
  while (pos < src.length) {
    const forAt = src.indexOf('<sc-for', pos);
    const ifAt = src.indexOf('<sc-if', pos);
    const next = [forAt, ifAt].filter((x) => x >= 0).sort((a, b) => a - b)[0];
    if (next === undefined) { out += expand(src.slice(pos), scopes); break; }
    out += expand(src.slice(pos, next), scopes);

    const isFor = next === forAt;
    const tag = isFor ? 'sc-for' : 'sc-if';
    const tagEnd = src.indexOf('>', next);
    const attrs = src.slice(next, tagEnd);
    const { inner, after } = blockEnd(src, tag, tagEnd + 1);
    const body = src.slice(inner[0], inner[1]);

    if (isFor) {
      const list = lookup((attrs.match(/list="\{\{([^}]+)\}\}"/) || [])[1] || '', scopes);
      const as = (attrs.match(/as="([^"]+)"/) || [])[1] || 'item';
      if (Array.isArray(list)) {
        list.forEach((item, index) => {
          out += render(body, scopes.concat([{ [as]: item, $index: index }]));
        });
      }
    } else {
      const val = lookup((attrs.match(/value="\{\{([^}]+)\}\}"/) || [])[1] || '', scopes);
      if (val) out += render(body, scopes);
    }
    pos = after;
  }
  return out;
}

// Раскрывает {{дырки}} и убирает обработчики событий.
function expand(chunk, scopes) {
  return chunk
    .replace(/\s+onClick="\{\{[^}]+\}\}"/g, '')
    .replace(/\{\{([^}]+)\}\}/g, (_, path) => {
      const v = lookup(path, scopes);
      if (v === undefined || v === null) return '';
      return escapeHtml(v);
    });
}

const files = readdirSync(here).filter((f) => f.endsWith('.dc.html')).sort();
const pages = [];
for (const file of files) {
  const raw = readFileSync(join(here, file), 'utf8');
  const style = (raw.match(/<helmet>\s*<style>([\s\S]*?)<\/style>/) || [])[1] || '';
  const tpl = raw.slice(raw.indexOf('<x-dc>') + 6, raw.indexOf('</x-dc>'));
  const scriptTag = raw.match(/<script data-dc-script data-props='([^']*)'>([\s\S]*?)<\/script>/);
  const props = defaultProps(JSON.parse(scriptTag[1]));
  const Component = new Function('DCLogic', scriptTag[2] + '\nreturn Component;')(DCLogic);
  const instance = new Component(props);
  const vals = instance.renderVals();

  const body = render(tpl, [vals]);
  const name = file.replace('.dc.html', '');
  const title = (raw.match(/<div class="h1">([^<]+)<\/div>/) || [])[1] || name;
  const html = `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>${style}
    html { background: #F1F4F9; }
    body { display: flex; justify-content: center; }
</style>
</head>
<body>
${body}
</body>
</html>
`;
  writeFileSync(join(outDir, name + '.html'), html);
  pages.push({ name, title, file: name + '.html' });
  console.log('экспортирован ' + name + '.html (' + html.length + ' байт)');
}

const index = `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AiGate — макеты виджетов</title>
<style>
  :root { --f: Roboto, "Helvetica Neue", system-ui, sans-serif; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: var(--f); background: #F1F4F9; color: #16233A; padding: 40px 32px 64px; }
  .wrap { max-width: 720px; margin: 0 auto; }
  h1 { font: 700 24px/32px var(--f); margin: 0 0 8px; }
  p { font: 400 14px/20px var(--f); color: #4A5A70; margin: 0 0 24px; }
  ul { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
  a { display: block; background: #FFFFFF; border: 1px solid rgba(46, 111, 224, 0.102);
      border-radius: 16px; padding: 14px 16px; text-decoration: none; color: #16233A;
      font: 500 16px/22px var(--f); }
  a:hover { border-color: #2E6FE0; color: #1E50B5; }
  a span { display: block; font: 400 12px/16px var(--f); color: #4A5A70; margin-top: 2px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>AiGate — макеты виджетов домашнего экрана</h1>
  <p>Статические выгрузки дизайн-канваса: каждая страница показывает виджеты в светлой и тёмной темах на обоях. Размеры соответствуют сетке телефона 412 dp: 2×1 = 184 × 86, 4×1 = 380 × 86, 2×2 = 184 × 184, 4×2 = 380 × 184, 4×4 = 380 × 380.</p>
  <ul>
${pages.map((p) => `    <li><a href="${p.file}">${escapeHtml(p.title)}<span>${p.file}</span></a></li>`).join('\n')}
  </ul>
</div>
</body>
</html>
`;
writeFileSync(join(outDir, 'index.html'), index);
console.log('экспортирован index.html');
