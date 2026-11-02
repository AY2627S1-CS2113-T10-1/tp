// Runs inside the SVG document through Playwright. Keep this function self-contained.
export function applyReferenceStyle({ sequence }) {
  const svg = document.querySelector('svg');
  const ns = svg.namespaceURI;
  const numbers = element => element.getAttribute('d').match(/-?\d+(?:\.\d+)?/g)?.map(Number) ?? [];

  // PlantUML folds the top-right corner; page 7 of the sheet folds the bottom-right.
  for (const body of svg.querySelectorAll('path')) {
    const p = numbers(body);
    if (p.length !== 12 || p[0] !== p[2] || p[3] !== p[5]
        || p[0] !== p[10] || p[1] !== p[11]) continue;
    const fold = body.nextElementSibling;
    if (fold?.localName !== 'path' || numbers(fold).length !== 8) {
      throw new Error('Unrecognized PlantUML note fold');
    }
    const [x, y, , bottom, right] = p;
    const corner = right - p[8];
    body.setAttribute('data-uml-note', 'bottom-right');
    body.setAttribute('d', `M${x},${y} H${right} V${bottom - corner}`
      + ` L${right - corner},${bottom} H${x} Z`);
    fold.setAttribute('d', `M${right - corner},${bottom} V${bottom - corner} H${right} Z`);
  }

  if (sequence) {
    // Leave dashed lifelines intact. These short patterns belong to return messages.
    for (const element of svg.querySelectorAll('line, path')) {
      if (element.style.strokeDasharray.replaceAll(' ', '') === '2,2') {
        element.style.strokeDasharray = '6,4';
        element.setAttribute('data-uml-return', 'true');
      }
    }
    for (const head of svg.querySelectorAll('polygon')) {
      const points = head.getAttribute('points').split(',').map(Number);
      if (points.length !== 6) continue;
      const labels = [];
      let next = head.nextElementSibling;
      let response = head.previousElementSibling?.hasAttribute('data-uml-return') ?? false;
      while (next && ['line', 'path'].includes(next.localName)) {
        response ||= next.hasAttribute('data-uml-return');
        next = next.nextElementSibling;
      }
      while (next?.localName === 'text') {
        labels.push(next);
        next = next.nextElementSibling;
      }
      const top = Math.min(...labels.map(label => label.getBBox().y));
      for (const label of labels) {
        label.setAttribute('font-style', 'italic');
        if (response) {
          label.setAttribute('y', Number(label.getAttribute('y')) + points[3] + 3 - top);
          label.setAttribute('data-uml-return-label', 'below');
        }
      }
    }
    return;
  }

  for (const entity of svg.querySelectorAll('g.entity')) {
    if ([...entity.querySelectorAll('text')].some(text => text.textContent === '«interface»')) {
      for (const text of entity.querySelectorAll('text[font-style="italic"]')) {
        text.removeAttribute('font-style');
      }
    }
  }

  const occupiedLabels = [];
  const entities = [...svg.querySelectorAll('g.entity')].map(entity => entity.getBBox());
  for (const link of svg.querySelectorAll('g.link')) {
    // Navigability/dependency heads are open Vs; realization triangles stay hollow.
    for (const head of link.querySelectorAll('polygon[fill="#000000"]')) {
      const p = head.getAttribute('points').split(',').map(Number);
      if (p.length !== 10) throw new Error('Unrecognized PlantUML relationship head');
      const open = document.createElementNS(ns, 'polyline');
      open.setAttribute('points', `${p[2]},${p[3]} ${p[0]},${p[1]} ${p[6]},${p[7]}`);
      open.setAttribute('fill', 'none');
      open.setAttribute('style', head.getAttribute('style'));
      open.setAttribute('data-uml-head', 'open');
      link.querySelector('path').setAttribute('d',
        `${link.querySelector('path').getAttribute('d')} L${p[0]},${p[1]}`);
      head.replaceWith(open);
    }
    const labels = [...link.querySelectorAll('text')];
    if (labels.length === 2 && /^(?:\*|\d+(?:\.\.(?:\d+|\*))?)$/.test(labels[1].textContent)) {
      placeEndpointLabels(link.querySelector('path'), labels[0], labels[1]);
    }
  }

  // Put a role above its association and its multiplicity below (sheet page 1).
  function placeEndpointLabels(path, role, multiplicity) {
    const length = path.getTotalLength();
    const original = role.getBBox();
    const center = { x: original.x + original.width / 2, y: original.y + original.height / 2 };
    let nearest = length;
    let distance = Infinity;
    for (let position = Math.max(0, length - 220); position <= length; position += 1) {
      const point = path.getPointAtLength(position);
      const separation = Math.hypot(point.x - center.x, point.y - center.y);
      if (separation < distance) {
        distance = separation;
        nearest = position;
      }
    }
    const before = path.getPointAtLength(Math.max(0, nearest - 1));
    const after = path.getPointAtLength(Math.min(length, nearest + 1));
    const dx = after.x - before.x;
    const dy = after.y - before.y;
    const magnitude = Math.hypot(dx, dy);
    const clearance = (Math.abs(dx) * original.width + Math.abs(dy) * original.height) / magnitude / 2 + 10;
    nearest = length < 2 * clearance ? length / 2 : Math.max(clearance, Math.min(length - clearance, nearest));
    let normal = { x: dy / magnitude, y: -dx / magnitude };
    if (normal.y > 0) normal = { x: -normal.x, y: -normal.y };
    // Nearby incoming associations can put one role against another multiplicity.
    // Slide the pair back along its path until both labels have clear space.
    for (let position = nearest; position >= 0; position -= 16) {
      const point = path.getPointAtLength(position);
      const placements = [[role, 1], [multiplicity, -1]].map(([label, side]) => {
        const box = label.getBBox();
        const offset = (Math.abs(normal.x) * box.width + Math.abs(normal.y) * box.height) / 2 + 7;
        return { label, side, box, x: point.x + side * normal.x * offset - box.width / 2,
          y: point.y + side * normal.y * offset - box.height / 2, width: box.width, height: box.height };
      });
      const overlaps = (a, b) => a.x < b.x + b.width + 2 && a.x + a.width + 2 > b.x
        && a.y < b.y + b.height + 2 && a.y + a.height + 2 > b.y;
      if (placements.some(label => [...entities, ...occupiedLabels].some(box => overlaps(label, box)))) continue;
      for (const placement of placements) {
        const { label, side, box, x, y } = placement;
        label.setAttribute('x', Number(label.getAttribute('x')) + x - box.x);
        label.setAttribute('y', Number(label.getAttribute('y')) + y - box.y);
        label.setAttribute('data-uml-label', side === 1 ? 'role' : 'multiplicity');
        occupiedLabels.push(placement);
      }
      return;
    }
    throw new Error(`No clear endpoint label position for ${role.textContent}`);
  }
}
