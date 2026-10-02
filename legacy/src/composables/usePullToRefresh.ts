import { ref, type Ref } from 'vue'

const AXIS_LOCK_THRESHOLD_PX = 8
const MAX_OFFSET_PX = 76
const RESISTANCE_PX = 96
const RELEASE_DURATION_MS = 520
const RELEASE_EASING_X1 = 0.16
const RELEASE_EASING_Y1 = 1
const RELEASE_EASING_X2 = 0.3
const RELEASE_EASING_Y2 = 1
const REVEAL_START_PX = 8
const REVEAL_END_PX = 34
const ICON_FULL_SIZE = 24
const ICON_HIDDEN_SCALE = 0.5
const ICON_HOLD_MS = 1600
const ICON_HIDE_DURATION_MS = 1000

type GestureAxis = 'x' | 'y' | null

function getTopPullOffset(deltaY: number): number {
  return MAX_OFFSET_PX * (1 - Math.exp(-deltaY / RESISTANCE_PX))
}

function getRevealProgress(offsetPx: number): number {
  return Math.min(Math.max((offsetPx - REVEAL_START_PX) / (REVEAL_END_PX - REVEAL_START_PX), 0), 1)
}

function sampleCubicBezier(cp1: number, cp2: number, t: number): number {
  const it = 1 - t
  return 3 * it * it * t * cp1 + 3 * it * t * t * cp2 + t * t * t
}

function sampleCubicBezierDerivative(cp1: number, cp2: number, t: number): number {
  const it = 1 - t
  return 3 * it * it * cp1 + 6 * it * t * (cp2 - cp1) + 3 * t * t * (1 - cp2)
}

function getCubicBezierProgress(x: number): number {
  if (x <= 0) return 0
  if (x >= 1) return 1
  let t = x
  for (let i = 0; i < 6; i++) {
    const cx = sampleCubicBezier(RELEASE_EASING_X1, RELEASE_EASING_X2, t)
    const cd = sampleCubicBezierDerivative(RELEASE_EASING_X1, RELEASE_EASING_X2, t)
    if (Math.abs(cx - x) < 0.0001 || cd === 0) break
    t -= (cx - x) / cd
  }
  const ct = Math.min(1, Math.max(0, t))
  return sampleCubicBezier(RELEASE_EASING_Y1, RELEASE_EASING_Y2, ct)
}

function setIconStyle(svg: SVGSVGElement, reveal: number): void {
  const scale = ICON_HIDDEN_SCALE + reveal * (1 - ICON_HIDDEN_SCALE)
  const px = ICON_FULL_SIZE * scale
  svg.style.opacity = String(reveal)
  svg.style.width = `${px}px`
  svg.style.height = `${px}px`
}

export function usePullToRefresh(
  elementRef: Ref<HTMLElement | null>,
  svgRef: Ref<SVGSVGElement | null>,
  onRefresh: () => void,
) {
  const offsetPx = ref(0)
  const isPulling = ref(false)
  const isSpinning = ref(false)
  const isHiding = ref(false)

  let gestureAxis: GestureAxis = null
  let gestureStartX = 0
  let gestureStartY = 0
  let gestureTracking = false
  let resetAnimFrameId = 0
  let resetAnimStartOffset = 0
  let resetAnimStartedAt = 0
  let holdTimeoutId = 0
  let hideAnimFrameId = 0
  let hideAnimStartedAt = 0

  function cancelResetAnimation(): void {
    if (resetAnimFrameId) {
      window.cancelAnimationFrame(resetAnimFrameId)
      resetAnimFrameId = 0
    }
    resetAnimStartOffset = 0
    resetAnimStartedAt = 0
  }

  function cancelHold(): void {
    if (holdTimeoutId) {
      window.clearTimeout(holdTimeoutId)
      holdTimeoutId = 0
    }
  }

  function cancelHideAnimation(): void {
    if (hideAnimFrameId) {
      window.cancelAnimationFrame(hideAnimFrameId)
      hideAnimFrameId = 0
    }
    hideAnimStartedAt = 0
  }

  function clearGestureTracking(): void {
    gestureAxis = null
    gestureStartX = 0
    gestureStartY = 0
    gestureTracking = false
  }

  function resetIconStyle(): void {
    if (svgRef.value) setIconStyle(svgRef.value, 0)
  }

  function resetGesture(): void {
    cancelResetAnimation()
    cancelHold()
    cancelHideAnimation()
    clearGestureTracking()
    offsetPx.value = 0
    isPulling.value = false
    isSpinning.value = false
    isHiding.value = false
    resetIconStyle()
  }

  function stepResetAnimation(timestamp: number): void {
    if (resetAnimStartedAt === 0) resetAnimStartedAt = timestamp
    const elapsed = timestamp - resetAnimStartedAt
    const progress = Math.min(elapsed / RELEASE_DURATION_MS, 1)
    const eased = getCubicBezierProgress(progress)
    offsetPx.value = resetAnimStartOffset * (1 - eased)
    if (progress >= 1) {
      resetAnimFrameId = 0
      resetAnimStartOffset = 0
      resetAnimStartedAt = 0
      offsetPx.value = 0
      return
    }
    resetAnimFrameId = window.requestAnimationFrame(stepResetAnimation)
  }

  function stepHideAnimation(timestamp: number): void {
    if (hideAnimStartedAt === 0) hideAnimStartedAt = timestamp
    const elapsed = timestamp - hideAnimStartedAt
    const progress = Math.min(elapsed / ICON_HIDE_DURATION_MS, 1)
    const eased = getCubicBezierProgress(progress)
    const reveal = 1 - eased
    if (svgRef.value) setIconStyle(svgRef.value, reveal)
    if (progress >= 1) {
      hideAnimFrameId = 0
      hideAnimStartedAt = 0
      isPulling.value = false
      isSpinning.value = false
      isHiding.value = false
      if (svgRef.value) setIconStyle(svgRef.value, 0)
      return
    }
    hideAnimFrameId = window.requestAnimationFrame(stepHideAnimation)
  }

  function releaseGesture(): void {
    clearGestureTracking()
    if (offsetPx.value <= 0) { resetGesture(); return }

    onRefresh()
    isSpinning.value = true
    isHiding.value = false
    if (svgRef.value) setIconStyle(svgRef.value, 1)

    cancelResetAnimation()
    resetAnimStartOffset = offsetPx.value
    resetAnimFrameId = window.requestAnimationFrame(stepResetAnimation)

    cancelHold()
    holdTimeoutId = window.setTimeout(() => {
      holdTimeoutId = 0
      isHiding.value = true
      cancelHideAnimation()
      hideAnimStartedAt = 0
      hideAnimFrameId = window.requestAnimationFrame(stepHideAnimation)
    }, ICON_HOLD_MS)
  }

  function onTouchStart(event: TouchEvent): void {
    if (elementRef.value == null) return
    const scrollTop = elementRef.value.scrollTop ?? 0
    if (scrollTop > 0 || event.touches.length !== 1) {
      resetGesture()
      return
    }
    const touch = event.touches[0]
    if (!touch) { resetGesture(); return }

    cancelResetAnimation()
    cancelHold()
    cancelHideAnimation()
    gestureAxis = null
    gestureStartX = touch.clientX
    gestureStartY = touch.clientY
    gestureTracking = true
    offsetPx.value = 0
    isPulling.value = true
    isSpinning.value = false
    isHiding.value = false
    resetIconStyle()
  }

  function onTouchMove(event: TouchEvent): void {
    if (!gestureTracking || event.touches.length !== 1) return
    const touch = event.touches[0]
    if (!touch) return
    const deltaX = touch.clientX - gestureStartX
    const deltaY = touch.clientY - gestureStartY

    if (!gestureAxis) {
      if (Math.abs(deltaX) < AXIS_LOCK_THRESHOLD_PX && Math.abs(deltaY) < AXIS_LOCK_THRESHOLD_PX) return
      gestureAxis = Math.abs(deltaY) > Math.abs(deltaX) ? 'y' : 'x'
    }

    if (gestureAxis !== 'y') { resetGesture(); return }

    if (elementRef.value == null) return
    const scrollTop = elementRef.value.scrollTop ?? 0
    if (deltaY <= 0 || scrollTop > 0) {
      offsetPx.value = 0
      if (svgRef.value) setIconStyle(svgRef.value, 0)
      return
    }

    offsetPx.value = getTopPullOffset(deltaY)
    if (svgRef.value) setIconStyle(svgRef.value, getRevealProgress(offsetPx.value))

    if (event.cancelable) event.preventDefault()
  }

  function onTouchEnd(): void {
    releaseGesture()
  }

  function cleanup(): void {
    cancelResetAnimation()
    cancelHold()
    cancelHideAnimation()
    clearGestureTracking()
    offsetPx.value = 0
    isPulling.value = false
    isSpinning.value = false
    isHiding.value = false
    resetIconStyle()
  }

  return { offsetPx, isPulling, isSpinning, isHiding, onTouchStart, onTouchMove, onTouchEnd, cleanup }
}
