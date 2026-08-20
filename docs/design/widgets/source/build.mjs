// Собирает рабочие артборды из src/*.dc.html, подставляя общий CSS и общие JS-хелперы.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, '_base.css'), 'utf8').replace(/\s+$/, '');
const helpers = readFileSync(join(here, '_helpers.js'), 'utf8').replace(/\s+$/, '');

const src = join(here, 'src');
const names = readdirSync(src).filter((f) => f.endsWith('.dc.html')).sort();
for (const name of names) {
  const body = readFileSync(join(src, name), 'utf8');
  if (!body.includes('/*BASE_CSS*/')) throw new Error(name + ': нет маркера /*BASE_CSS*/');
  if (!body.includes('/*HELPERS*/')) throw new Error(name + ': нет маркера /*HELPERS*/');
  // Замена через функцию: иначе $-последовательности в подставляемом коде
  // трактуются как спецшаблоны String.replace.
  const out = body.replace('/*BASE_CSS*/', () => css).replace('/*HELPERS*/', () => helpers);
  writeFileSync(join(here, name), out);
  console.log('собран ' + name + ' (' + out.length + ' байт)');
}
