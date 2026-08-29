const labels = {
  COPIED: '扫描直入',
  TRANSCODED: '转码入库',
  EXTERNAL_READ_ONLY: '外部只读',
  UNKNOWN: '历史曲库'
}

export function sourceLabel(value) {
  return labels[value] || '历史曲库'
}
