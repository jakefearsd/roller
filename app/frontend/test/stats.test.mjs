import test from 'node:test';
import assert from 'node:assert/strict';
import { wordCount, readingMinutes } from '../src/stats.js';

test('counts words across lines and ignores markdown punctuation', () => {
  assert.equal(wordCount('# Title\n\nSome **bold** words, and `code`.'), 6);
  assert.equal(wordCount(''), 0);
  assert.equal(wordCount('   \n  '), 0);
});

test('reading time rounds up and never reads 0 for text', () => {
  assert.equal(readingMinutes(0), 0);
  assert.equal(readingMinutes(1), 1);
  assert.equal(readingMinutes(230), 1);
  assert.equal(readingMinutes(231), 2);
});
