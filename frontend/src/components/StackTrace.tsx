import { splitStackTrace } from '../stackTrace.ts';

export default function StackTrace({ stackTrace }: { stackTrace: string }) {
  const lines = splitStackTrace(stackTrace);

  return (
    <pre className="stack-trace">
      <code>
        {lines.map((line, index) => (
          <span key={index} className={`stack-line stack-${line.kind}`}>
            {line.text}
            {index < lines.length - 1 && '\n'}
          </span>
        ))}
      </code>
    </pre>
  );
}
