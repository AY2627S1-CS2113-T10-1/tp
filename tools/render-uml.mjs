import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { parseArgs } from 'node:util';

// PlantUML cannot connect sequence notes to lifelines. Add those annotations
// to its SVG, then rasterize that same SVG so both exports contain the arrows.
const { values } = parseArgs({ options: {
  plantuml: { type: 'string' },
  chrome: { type: 'string' },
  output: { type: 'string', default: 'docs/images' },
} });
if (!values.plantuml) {
  throw new Error('Usage: node tools/render-uml.mjs --plantuml PATH [--chrome PATH] [--output DIR]');
}
const { chromium } = createRequire(import.meta.url)('playwright');
const root = process.cwd();
const sources = path.join(root, 'docs/diagrams');
const output = path.resolve(values.output);
const jar = path.resolve(values.plantuml);
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true,
  ...(values.chrome ? { executablePath: values.chrome } : {}) });

try {
  const files = (await fs.readdir(sources)).filter(name => name.endsWith('.puml')
    && name !== 'course-style.puml').sort();
  for (const file of files) {
    const source = await fs.readFile(path.join(sources, file), 'utf8');
    const rendered = spawnSync('java', ['-jar', jar, '-charset', 'UTF-8', '-failfast2', '-tsvg', '-pipe'],
      { cwd: sources, input: source, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
    if (rendered.error || rendered.status !== 0 || !rendered.stdout.includes('<svg')) {
      throw new Error(`Cannot render ${file}: ${rendered.error ?? rendered.stderr}`);
    }
    const participants = Object.fromEntries([...source.matchAll(
      /^(?:create )?participant "([^"]+)" as (\w+)/gm)].map(match => [match[2], match[1]]));
    const notes = [...source.matchAll(/^[ \t]*note right of (\w+)(?: : ([^\r\n]+)|\r?\n[ \t]*([^\r\n]+))/gm)]
      .map(match => ({ target: match[1], firstLine: (match[2] ?? match[3]).trim() }));
    if (Object.keys(participants).length && notes.length !== [...source.matchAll(/^\s*note\b/gm)].length) {
      throw new Error(`${file}: sequence notes must use 'note right of PARTICIPANT'`);
    }
    const context = await browser.newContext({ deviceScaleFactor: 2 });
    try {
      const page = await context.newPage();
      await page.setContent(rendered.stdout);
      await page.evaluate(() => document.fonts.ready);
      const size = await page.evaluate(({ participants, notes }) => {
        const svg = document.querySelector('svg');
        const ns = svg.namespaceURI;
        const elements = [...svg.querySelectorAll('path')];
        // A folded note's outer path has six corners; its fold has four.
        const bodies = elements.filter(element => {
          const p = element.getAttribute('d').match(/-?\d+(?:\.\d+)?/g)?.map(Number) ?? [];
          return p.length === 12 && p[0] === p[2] && p[3] === p[5]
            && p[0] === p[10] && p[1] === p[11];
        });
        if (notes.length && bodies.length !== notes.length) {
          throw new Error(`Expected ${notes.length} sequence notes, found ${bodies.length}`);
        }
        let width = svg.viewBox.baseVal.width;
        for (const [index, note] of notes.entries()) {
          const body = bodies[index];
          const label = [...svg.querySelectorAll('text')].find(text =>
            text.textContent === participants[note.target]);
          if (!label) throw new Error(`Unknown participant: ${note.target}`);
          const header = label.getBBox();
          const lifeline = header.x + header.width / 2;
          const box = body.getBBox();
          const y = box.y + box.height / 2;
          const activations = [...svg.querySelectorAll('rect')].filter(rect => {
            const b = rect.getBBox();
            return b.width === 10 && Math.abs(b.x - lifeline) < 25
              && b.y <= y && b.y + b.height >= y;
          });
          const target = Math.max(lifeline, ...activations.map(rect => {
            const b = rect.getBBox();
            return b.x + b.width;
          }));
          const offset = Math.max(0, target + 45 - box.x);
          const group = document.createElementNS(ns, 'g');
          group.setAttribute('class', 'uml-note');
          group.setAttribute('data-note-target', note.target);
          body.before(group);
          let next = body;
          const texts = [];
          while (next && ['path', 'text'].includes(next.localName)) {
            const bounds = next.getBBox();
            if (bounds.x < box.x || bounds.y < box.y
                || bounds.x + bounds.width > box.x + box.width + 1
                || bounds.y + bounds.height > box.y + box.height + 1) break;
            const following = next.nextElementSibling;
            if (next.localName === 'text') texts.push(next.textContent);
            group.append(next);
            next = following;
          }
          if (texts[0] !== note.firstLine) {
            throw new Error(`Note order changed: expected '${note.firstLine}', found '${texts[0]}'`);
          }
          group.setAttribute('transform', `translate(${offset},0)`);
          const arrow = document.createElementNS(ns, 'g');
          arrow.setAttribute('class', 'uml-note-link');
          arrow.setAttribute('data-note-target', note.target);
          const line = document.createElementNS(ns, 'path');
          line.setAttribute('d', `M${box.x + offset},${y} H${target}`);
          line.setAttribute('stroke-dasharray', '1,3');
          const head = document.createElementNS(ns, 'path');
          head.setAttribute('d', `M${target + 7},${y - 4} L${target},${y} L${target + 7},${y + 4}`);
          for (const part of [line, head]) {
            part.setAttribute('stroke', '#000000');
            part.setAttribute('stroke-width', '1.2');
            part.setAttribute('fill', 'none');
            arrow.append(part);
          }
          group.after(arrow);
          width = Math.max(width, box.x + offset + box.width + 5);
        }
        const height = svg.viewBox.baseVal.height;
        svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
        svg.setAttribute('width', `${width}px`);
        svg.setAttribute('height', `${height}px`);
        svg.style.width = `${width}px`;
        svg.style.height = `${height}px`;
        document.body.style.margin = '0';
        return { width: Math.ceil(width), height: Math.ceil(height) };
      }, { participants, notes });
      await page.setViewportSize(size);
      const stem = path.basename(file, '.puml');
      await fs.writeFile(path.join(output, `${stem}.svg`), await page.locator('svg')
        .evaluate(svg => new XMLSerializer().serializeToString(svg)));
      await page.locator('svg').screenshot({ path: path.join(output, `${stem}.png`) });
      console.log(`${stem}: SVG and 2x PNG; ${notes.length} sequence note arrow(s)`);
    } finally {
      await context.close();
    }
  }
} finally {
  await browser.close();
}
