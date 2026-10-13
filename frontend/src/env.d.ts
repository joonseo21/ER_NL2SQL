interface ImportMetaEnv {
  /** mock이면 가짜 응답(기본), http이면 실제 /api를 호출한다. */
  readonly VITE_API_MODE?: 'mock' | 'http'
}
