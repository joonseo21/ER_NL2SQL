/** 실제로 실행된 SQL이다. 길어서 기본은 접어 두고, 열면 그대로 보여 준다. */
export function SqlBlock({ sql }: { sql: string }) {
  return (
    <details className="sql">
      <summary>실행된 SQL 보기</summary>
      <pre>
        <code>{sql}</code>
      </pre>
    </details>
  )
}
