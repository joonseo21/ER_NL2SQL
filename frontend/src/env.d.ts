interface ImportMetaEnv {
  /** mock이면 가짜 응답, http이면 실제 /api를 호출한다. 비우면 dev·test는 mock, build는 http다. */
  readonly VITE_API_MODE?: string
}
