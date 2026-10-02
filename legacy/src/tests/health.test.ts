import { beforeEach, describe, expect, it, vi } from 'vitest'

const { capacitorHttpRequest, isNativePlatform } = vi.hoisted(() => ({
  capacitorHttpRequest: vi.fn(),
  isNativePlatform: vi.fn(() => true),
}))

vi.mock('@capacitor/core', () => ({
  Capacitor: {
    isNativePlatform: () => isNativePlatform(),
  },
  CapacitorHttp: {
    request: capacitorHttpRequest,
  },
}))

import { checkServerHealth } from '@/services/opencode/health'

describe('checkServerHealth', () => {
  beforeEach(() => {
    capacitorHttpRequest.mockReset()
    isNativePlatform.mockReset()
    isNativePlatform.mockReturnValue(true)
  })

  it('falls back to GET on native when HEAD rejects and preserves wrong_credentials', async () => {
    capacitorHttpRequest
      .mockRejectedValueOnce(new Error('HEAD request failed'))
      .mockResolvedValueOnce({ status: 401 })

    const result = await checkServerHealth({
      baseUrl: 'http://localhost:4096',
      username: 'opencode',
      password: 'wrong-password',
      timeoutMs: 3500,
    })

    expect(capacitorHttpRequest).toHaveBeenNthCalledWith(1, expect.objectContaining({
      url: 'http://localhost:4096/',
      method: 'HEAD',
    }))
    expect(capacitorHttpRequest).toHaveBeenNthCalledWith(2, expect.objectContaining({
      url: 'http://localhost:4096/',
      method: 'GET',
    }))
    expect(result).toEqual({
      reachable: true,
      status: 'wrong_credentials',
      statusCode: 401,
    })
  })

  it('falls back to GET on native when HEAD rejects and preserves auth_required', async () => {
    capacitorHttpRequest
      .mockRejectedValueOnce(new Error('HEAD request failed'))
      .mockResolvedValueOnce({ status: 401 })

    const result = await checkServerHealth({
      baseUrl: 'http://localhost:4096',
      timeoutMs: 3500,
    })

    expect(result).toEqual({
      reachable: true,
      status: 'auth_required',
      statusCode: 401,
    })
  })
})
