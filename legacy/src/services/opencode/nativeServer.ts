import { registerPlugin } from '@capacitor/core'

export interface OpenCodeServerPlugin {
  setZenApiKey(options: { key: string }): Promise<{ success: boolean; message: string }>
  checkLocalServerHealth(): Promise<{ healthy: boolean; url: string; error?: string }>
  startLocalServer(): Promise<{ success: boolean; message: string }>
  stopLocalServer(): Promise<{ success: boolean; message: string }>
  getBootstrapStatus(): Promise<{ extracted: boolean; prefixPath: string }>
}

const OpenCodeServer = registerPlugin<OpenCodeServerPlugin>('OpenCodeServer')

export default OpenCodeServer
