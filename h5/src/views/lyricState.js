export function lyricViewState(song, lines = [], error = '') {
  if (error) return 'error'
  if (lines.length) return 'lyrics'
  return song ? 'empty' : 'idle'
}

/**
 * 返回已经开始播放的最后一行歌词；首句时间之前返回 -1。
 *
 * Return the last lyric line whose timestamp has been reached. Before the
 * first timestamp there is no current line, so the result is -1.
 */
export function currentLyricIndex(lines = [], positionMs = 0) {
  let index = -1
  for (let i = 0; i < lines.length; i += 1) {
    if (lines[i].time <= positionMs) index = i
    else break
  }
  return index
}
