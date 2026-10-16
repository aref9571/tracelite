import { describe, expect, it } from 'vitest';
import { classifyStackLine, splitStackTrace } from './stackTrace.ts';

describe('classifyStackLine', () => {
  it.each([
    ['java.lang.IllegalStateException: Cart is empty', 'summary'],
    ['\tat com.example.cart.CartService.checkout(CartService.java:42)', 'frame'],
    ['    at app//com.example.Main.main(Main.java:7)', 'frame'],
    ['Caused by: java.io.IOException: Broken pipe', 'cause'],
    ['\tSuppressed: java.lang.RuntimeException: close failed', 'cause'],
    ['\t... 12 more', 'omitted'],
    ['\t... 3 common frames omitted', 'omitted'],
    ['attempt failed', 'summary'],
  ])('classifies %j as %s', (line, kind) => {
    expect(classifyStackLine(line)).toBe(kind);
  });
});

describe('splitStackTrace', () => {
  it('splits on any line ending and drops trailing blank lines', () => {
    const lines = splitStackTrace('java.lang.Error: boom\r\n\tat a.B.c(B.java:1)\rCaused by: x.Y\n\n');

    expect(lines).toEqual([
      { text: 'java.lang.Error: boom', kind: 'summary' },
      { text: '\tat a.B.c(B.java:1)', kind: 'frame' },
      { text: 'Caused by: x.Y', kind: 'cause' },
    ]);
  });

  it('keeps a single-line trace as one summary line', () => {
    expect(splitStackTrace('java.lang.Error')).toEqual([{ text: 'java.lang.Error', kind: 'summary' }]);
  });
});
