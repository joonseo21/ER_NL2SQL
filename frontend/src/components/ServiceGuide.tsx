import { useEffect, useRef } from 'react'
import type { KeyboardEvent } from 'react'
import { SERVICE_GUIDE, SERVICE_GUIDE_TITLE } from '../content/serviceGuide'

export const SERVICE_GUIDE_ID = 'service-guide'

interface Props {
  /** 닫기 버튼이나 Esc로 닫을 때 부른다. 초점은 연 쪽이 되돌린다. */
  onClose: () => void
}

/**
 * 질문 전에 읽는 서비스 안내다(spec D-25, M1-044). 머리글 아래에 펼쳐지는 패널이며 아래 화면을 덮거나 바꾸지
 * 않으므로 입력해 둔 질문·조건과 진행 중인 조회가 그대로 남는다. 제목마다 접고 펼 수 있다.
 * 열리면 제목으로 초점이 옮겨 오고, 패널 안에서 Esc를 누르면 닫힌다.
 */
export function ServiceGuide({ onClose }: Props) {
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
    }
  }

  return (
    <section
      id={SERVICE_GUIDE_ID}
      className="card guide"
      role="region"
      aria-labelledby="service-guide-title"
      onKeyDown={handleKeyDown}
    >
      <div className="guide-head">
        <h2 id="service-guide-title" ref={headingRef} tabIndex={-1}>
          {SERVICE_GUIDE_TITLE}
        </h2>
        <button type="button" className="link" onClick={onClose}>
          닫기
        </button>
      </div>
      <p className="muted small">제목을 누르면 내용을 펴고 접을 수 있습니다. Esc 키로 안내를 닫습니다.</p>

      {SERVICE_GUIDE.map((section, index) => (
        <details key={section.title} className="guide-section" open={index === 0}>
          <summary>
            <h3>{section.title}</h3>
          </summary>
          {section.lines.length === 1 ? (
            <p>{section.lines[0]}</p>
          ) : (
            <ul>
              {section.lines.map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
          )}
        </details>
      ))}
    </section>
  )
}
