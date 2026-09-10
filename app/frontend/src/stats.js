const WORDS_PER_MINUTE = 230;

export function wordCount(text) {
  if (!text) return 0;
  const stripped = text
    .replace(/`[^`]*`/g, ' code ')
    .replace(/[#*_>\[\]()!\-]+/g, ' ');
  const words = stripped.trim().split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w));
  return words.length;
}

export function readingMinutes(words) {
  if (words <= 0) return 0;
  return Math.ceil(words / WORDS_PER_MINUTE);
}
