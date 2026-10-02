import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useServerStore } from '@/stores/serverStore'

const POLL_INTERVAL = 5000

export function useServerHealthPoll() {
  const serverStore = useServerStore()
  const route = useRoute()

  let timer: ReturnType<typeof setInterval> | null = null

  function goOffline() {
    serverStore.profiles.forEach((p) => {
      serverStore.reachable[p.id] = false
    })
  }

  function goOnline() {
    serverStore.checkAllServers()
  }

  function isWebviewPage(): boolean {
    return route.path.startsWith('/connect/')
  }

  function start() {
    stop()
    if (isWebviewPage()) return

    timer = setInterval(() => {
      if (isWebviewPage()) {
        stop()
        return
      }
      serverStore.checkAllServers()
    }, POLL_INTERVAL)
  }

  function stop() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function pollNow() {
    if (!isWebviewPage()) {
      serverStore.checkAllServers()
    }
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible' && !isWebviewPage()) {
      pollNow()
    }
  }

  function onWindowFocus() {
    if (!isWebviewPage()) {
      pollNow()
    }
  }

  onMounted(() => {
    start()
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('focus', onWindowFocus)
  })

  onUnmounted(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('focus', onWindowFocus)
  })

  watch(() => route.path, () => {
    if (!isWebviewPage() && timer === null) {
      start()
    }
  })

  window.addEventListener('offline', goOffline)
  window.addEventListener('online', goOnline)

  return { start, stop, pollNow }
}
