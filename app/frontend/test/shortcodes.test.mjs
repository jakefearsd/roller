import test from 'node:test';
import assert from 'node:assert/strict';
import { shortcodeRanges } from '../src/shortcodes.js';

test('finds opening, closing and escaped shortcodes for registered names only', () => {
  const text = 'a [image id="1"] b [/faq] c [[gallery dir="x"]] d [unknown x] e';
  const ranges = shortcodeRanges(text, ['image', 'faq', 'gallery']);
  assert.deepEqual(ranges, [
    { from: 2, to: 16 },
    { from: 19, to: 25 },
    { from: 28, to: 47 }
  ]);
});
