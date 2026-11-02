import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');
const sources = path.join(root, 'docs/diagrams');
const images = path.join(root, 'docs/images/diagrams');
const guide = await fs.readFile(path.join(root, 'docs/DeveloperGuide.md'), 'utf8');
const stems = (await fs.readdir(sources)).filter(name => name.endsWith('.puml')
  && name !== 'course-style.puml').map(name => name.slice(0, -5));

for (const stem of stems) {
  test(`${stem}: reference-sheet notation and linked exports`, async () => {
    const source = await fs.readFile(path.join(sources, `${stem}.puml`), 'utf8');
    const svg = await fs.readFile(path.join(images, `${stem}.svg`), 'utf8');
    const png = await fs.readFile(path.join(images, `${stem}.png`));
    const [, width, height] = svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/);
    assert.equal(png.readUInt32BE(16), Math.ceil(Number(width)) * 2);
    assert.equal(png.readUInt32BE(20), Math.ceil(Number(height)) * 2);
    for (const extension of ['puml', 'svg', 'png']) {
      assert.ok(guide.includes(`/${stem}.${extension})`), `Missing DG link to ${extension}`);
    }

    const notes = [...source.matchAll(/^\s*note\b/gm)];
    const bodies = [...svg.matchAll(/<path\b[^>]*data-uml-note="bottom-right"[^>]*>/g)];
    assert.equal(bodies.length, notes.length, 'Every note must have a bottom-right fold');
    for (const [body] of bodies) {
      const [, x, y, right, foldY, foldX, bottom] = body.match(
        /d="M([\d.]+),([\d.]+) H([\d.]+) V([\d.]+) L([\d.]+),([\d.]+) H[\d.]+ Z"/);
      assert.ok(Number(right) > Number(foldX) && Number(foldX) > Number(x));
      assert.ok(Number(bottom) > Number(foldY) && Number(foldY) > Number(y));
    }
    assert.doesNotMatch(svg, /stroke-dasharray[=:][" ]*1,\s*3/);

    if (source.includes('participant ')) {
      const connectors = [...svg.matchAll(/<g class="uml-note-link"[^>]*>(.*?)<\/g>/g)];
      assert.equal(connectors.length, notes.length);
      for (const [, connector] of connectors) {
        assert.doesNotMatch(connector, /polygon|polyline|marker-end/);
        assert.equal([...connector.matchAll(/<path\b/g)].length, 1);
        assert.match(connector, /stroke-dasharray="6,4"/);
      }
      assert.doesNotMatch(svg, /stroke-dasharray:\s*2,\s*2/);
      const returns = [...source.matchAll(/^.*-->(?:.*) -- :|^\[<--.* -- :/gm)];
      const below = [...svg.matchAll(/<text\b[^>]*data-uml-return-label="below"[^>]*>/g)];
      assert.ok(below.length >= returns.length, 'Every return value must be below its arrow');
      for (const [label] of below) assert.match(label, /font-style="italic"/);
    } else {
      const links = [...svg.matchAll(/<g class="link"[^>]*>(.*?)<\/g>/g)].map(match => match[1]);
      for (const link of links) assert.doesNotMatch(link, /<polygon fill="#000000"/);
      for (const [, name] of source.matchAll(/^note as (\w+)/gm)) {
        const connector = svg.match(new RegExp(`<g class="link"[^>]*data-entity-1="${name}"[^>]*>(.*?)</g>`))[1];
        assert.doesNotMatch(connector, /polygon|polyline|marker-end/);
        assert.match(connector, /stroke-dasharray:7,7/);
      }
      const roles = [...source.matchAll(/--> "[^"\n]+\\n[^"\n]+"/g)].length;
      assert.equal([...svg.matchAll(/data-uml-label="role"/g)].length, roles);
      assert.equal([...svg.matchAll(/data-uml-label="multiplicity"/g)].length, roles);
      for (const [, entity] of svg.matchAll(/<g class="entity"[^>]*>(.*?)<\/g>/g)) {
        if (entity.includes('«interface»')) assert.doesNotMatch(entity, /font-style="italic"/);
      }
      const realizations = [...source.matchAll(/<\|\.\./g)].length;
      assert.equal(links.filter(link => link.includes('<polygon fill="none"')).length, realizations);
    }
  });
}

test('Console command values connect to their producers and consumers', async () => {
  const svg = await fs.readFile(path.join(images, 'ka-how-console-classes.svg'), 'utf8');
  const relationships = [
    ['CommandParser', 'Command', 'returns'],
    ['CommandDispatcher', 'Command', 'accepts'],
    ['CommandDispatcher', 'CommandResponse', 'creates / returns'],
    ['App', 'CommandResponse', 'reads'],
  ];
  for (const [from, to, label] of relationships) {
    const link = svg.match(new RegExp(
      `<g class="link"[^>]*data-entity-1="${from}" data-entity-2="${to}"[^>]*>(.*?)</g>`));
    assert.ok(link, `Missing dependency from ${from} to ${to}`);
    assert.match(link[1], /stroke-dasharray:7,7/);
    assert.match(link[1], /<polyline[^>]*fill="none"/);
    const renderedLabel = [...link[1].matchAll(/<text\b[^>]*>(.*?)<\/text>/g)]
      .map(match => match[1]).join('').replaceAll('\u00a0', ' ');
    assert.equal(renderedLabel, label);
  }
});

test('Guide and PPP Markdown diagram links resolve to existing files', async () => {
  const files = [path.join(root, 'docs/DeveloperGuide.md'), path.join(root, 'docs/UserGuide.md'),
    ...(await fs.readdir(path.join(root, 'docs/team'))).filter(name => name.endsWith('.md'))
      .map(name => path.join(root, 'docs/team', name))];
  for (const file of files) {
    const markdown = await fs.readFile(file, 'utf8');
    for (const [, target] of markdown.matchAll(/\]\(([^)]+(?:images\/diagrams|diagrams\/)[^)]+)\)/g)) {
      const relative = target.replace(/^https:\/\/github\.com\/[^/]+\/tp\/(?:blob|raw)\/master\//, '');
      const local = relative === target ? path.resolve(path.dirname(file), target) : path.join(root, relative);
      assert.ok((await fs.stat(local)).isFile(), `${file}: broken link ${target}`);
    }
  }
});
