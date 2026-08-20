  dec(x) {
    const s = x.toFixed(1).replace('.', ',');
    return s.endsWith(',0') ? s.slice(0, -2) : s;
  }

  dec2(x) {
    return x.toFixed(2).replace('.', ',');
  }

  compact(n) {
    if (n < 1000) return String(Math.round(n));
    if (n < 1000000) return this.dec(n / 1000) + 'K';
    return this.dec(n / 1000000) + 'M';
  }

  usd(x) {
    return '$' + this.dec2(x);
  }

  signed(p) {
    const r = Math.round(p);
    if (r === 0) return '±0%';
    return (r > 0 ? '+' : '') + r + '%';
  }

  plural(n, one, few, many) {
    const a = Math.abs(n) % 100;
    const b = a % 10;
    if (a > 10 && a < 20) return many;
    if (b > 1 && b < 5) return few;
    if (b === 1) return one;
    return many;
  }

  niceCeil(v) {
    if (v <= 0) return 1;
    const steps = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (let i = 0; i < steps.length; i++) {
      if (v <= steps[i] * p + 1e-9) return steps[i] * p;
    }
    return 10 * p;
  }

  lum(hex) {
    const ch = [1, 3, 5].map((i) => parseInt(hex.substr(i, 2), 16) / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
  }

  mix(hex, toHex, t) {
    const a = [1, 3, 5].map((i) => parseInt(hex.substr(i, 2), 16));
    const b = [1, 3, 5].map((i) => parseInt(toHex.substr(i, 2), 16));
    const out = a.map((v, i) => Math.round(v + (b[i] - v) * t));
    return '#' + out.map((v) => ('0' + v.toString(16)).slice(-2)).join('').toUpperCase();
  }

  // Подъём яркости бренда до читаемого на текущей поверхности — как readableOn в приложении.
  readable(hex, dark) {
    const l = this.lum(hex);
    if (dark && l < 0.16) return this.mix(hex, '#FFFFFF', 0.62);
    if (!dark && l > 0.82) return this.mix(hex, '#000000', 0.35);
    return hex;
  }

  // Цвет монограммы на брендовой подложке: светлый бренд получает тёмные буквы.
  inkOn(hex) {
    return this.lum(hex) > 0.42 ? '#06121F' : '#FFFFFF';
  }

  pressure(remaining) {
    if (remaining === null || remaining === undefined) return 'unknown';
    if (remaining <= 0.05) return 'critical';
    if (remaining <= 0.15) return 'conserve';
    if (remaining <= 0.40) return 'normal';
    return 'free';
  }

  pressureLabel(p) {
    return { free: 'Свободно', normal: 'Нормально', conserve: 'Экономить', critical: 'Критично', unknown: 'Нет данных' }[p];
  }

  pressureChip(p) {
    return { free: 'succ', normal: 'info', conserve: 'warn', critical: 'err', unknown: 'neu' }[p];
  }

  polar(cx, cy, r, deg) {
    const a = ((deg - 90) * Math.PI) / 180;
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  }

  arc(cx, cy, r, from, to) {
    const span = Math.min(Math.max(to - from, 0), 359.9);
    // Нулевая дуга не рисуется вовсе: круглый торец превратил бы её в точку,
    // а точка на кольце читается как «немного израсходовано».
    if (span <= 0) return '';
    const p0 = this.polar(cx, cy, r, from);
    const p1 = this.polar(cx, cy, r, from + span);
    const large = span > 180 ? 1 : 0;
    return 'M' + p0[0] + ',' + p0[1] + ' A' + r + ',' + r + ' 0 ' + large + ' 1 ' + p1[0] + ',' + p1[1];
  }

  linePath(pts) {
    let d = '';
    for (let i = 0; i < pts.length; i++) {
      d += (i === 0 ? 'M' : ' L') + pts[i][0] + ',' + pts[i][1];
    }
    return d;
  }

  areaPath(pts, baseY) {
    if (!pts.length) return '';
    return this.linePath(pts) + ' L' + pts[pts.length - 1][0] + ',' + baseY + ' L' + pts[0][0] + ',' + baseY + ' Z';
  }

  roundTop(x, y, w, h, r) {
    const rr = Math.min(r, h, w / 2);
    return 'M' + x + ',' + (y + h) + ' L' + x + ',' + (y + rr) +
      ' Q' + x + ',' + y + ' ' + (x + rr) + ',' + y +
      ' L' + (x + w - rr) + ',' + y +
      ' Q' + (x + w) + ',' + y + ' ' + (x + w) + ',' + (y + rr) +
      ' L' + (x + w) + ',' + (y + h) + ' Z';
  }
