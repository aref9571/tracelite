export type StackLineKind = 'summary' | 'frame' | 'cause' | 'omitted';

export interface StackLine {
  text: string;
  kind: StackLineKind;
}

const FRAME_PATTERN = /^\s*at\s/;
const CAUSE_PATTERN = /^\s*(Caused by|Suppressed):/;
const OMITTED_PATTERN = /^\s*\.\.\. \d+ (more|common frames omitted)$/;

export function splitStackTrace(stackTrace: string): StackLine[] {
  return stackTrace
    .replace(/\r\n?/g, '\n')
    .replace(/\n+$/, '')
    .split('\n')
    .map((text) => ({ text, kind: classifyStackLine(text) }));
}

export function classifyStackLine(line: string): StackLineKind {
  if (FRAME_PATTERN.test(line)) {
    return 'frame';
  }
  if (CAUSE_PATTERN.test(line)) {
    return 'cause';
  }
  if (OMITTED_PATTERN.test(line)) {
    return 'omitted';
  }
  return 'summary';
}
