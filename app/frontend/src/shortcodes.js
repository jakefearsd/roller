function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

/** Ranges of [name ...], [/name] and [[name ...]] for the registered names. */
export function shortcodeRanges(text, names) {
  if (!names || names.length === 0) return [];
  const alternation = names.map(escapeRegex).join('|');
  const re = new RegExp('\\[\\[?\\/?(?:' + alternation + ')(?:\\s[^\\]]*)?\\]\\]?', 'g');
  const ranges = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    ranges.push({ from: m.index, to: m.index + m[0].length });
  }
  return ranges;
}
