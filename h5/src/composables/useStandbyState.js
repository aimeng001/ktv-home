import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 待机大屏状态机协调器（对标 Android TV / IKTV 50.0.4 待机屏保机制）
 * - 当无歌曲播放（nowPlaying == null）且无交互达设定时长时激活待机
 * - 任意触摸、点击、鼠标滑动或键盘按键立即退出待机
 * - 一旦有歌曲开播，瞬间退出待机
 */
export function createStandbyManager(options = {}) {
  const {
    getIsPlayingSong = () => false,
    timeoutMs = 30000,
    attachDomListeners = false,
  } = options

  const isStandby = ref(false)
  let timer = null

  function resetTimer() {
    clearTimeout(timer)
    if (getIsPlayingSong()) {
      isStandby.value = false
      return
    }
    timer = setTimeout(() => {
      if (!getIsPlayingSong()) {
        isStandby.value = true
      }
    }, timeoutMs)
  }

  function wake() {
    isStandby.value = false
    resetTimer()
  }

  function onSongChange(isPlaying) {
    if (isPlaying) {
      isStandby.value = false
      clearTimeout(timer)
    } else {
      resetTimer()
    }
  }

  function handleActivity() {
    wake()
  }

  if (attachDomListeners && typeof window !== 'undefined') {
    const events = ['pointerdown', 'mousemove', 'keydown', 'wheel', 'scroll']
    events.forEach(evt => window.addEventListener(evt, handleActivity, { passive: true }))
  }

  // 初始化计时
  resetTimer()

  function dispose() {
    clearTimeout(timer)
    if (attachDomListeners && typeof window !== 'undefined') {
      const events = ['pointerdown', 'mousemove', 'keydown', 'wheel', 'scroll']
      events.forEach(evt => window.removeEventListener(evt, handleActivity))
    }
  }

  return {
    isStandby,
    wake,
    resetTimer,
    onSongChange,
    dispose,
  }
}

export function useStandbyState(playerStore, timeoutMs = 30000) {
  const isPlayingSong = () => Boolean(playerStore?.nowPlaying?.song)
  const manager = createStandbyManager({
    getIsPlayingSong: isPlayingSong,
    timeoutMs,
    attachDomListeners: true,
  })

  watch(() => playerStore?.nowPlaying?.queueId, (newQId) => {
    manager.onSongChange(Boolean(newQId))
  })

  onBeforeUnmount(() => {
    manager.dispose()
  })

  return {
    isStandby: manager.isStandby,
    wake: manager.wake,
  }
}
