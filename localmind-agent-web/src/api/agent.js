import request from '@/utils/request'
import { useUserStore } from '@/stores'
import { baseURL } from '@/utils/request'

export const sendRecommendationMessage = (data) =>
  request.post('/agent/recommendation/chat', data, { timeout: 120000 })

export const streamRecommendationMessage = async (data, handlers = {}) => {
  const userStore = useUserStore()
  const response = await fetch(`${baseURL}/agent/recommendation/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(userStore.token ? { Authorization: userStore.token } : {})
    },
    body: JSON.stringify(data)
  })
  if (!response.ok || !response.body) {
    throw new Error(`stream request failed: ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split(/\r?\n\r?\n/)
    buffer = events.pop() || ''
    for (const eventText of events) {
      await dispatchSseEvent(eventText, handlers)
    }
  }
  if (buffer.trim()) {
    await dispatchSseEvent(buffer, handlers)
  }
}

const dispatchSseEvent = async (eventText, handlers) => {
  const lines = eventText.split(/\r?\n/)
  let event = 'message'
  const dataLines = []
  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  })
  if (!dataLines.length) return
  const rawData = dataLines.join('\n')
  let payload = rawData
  try {
    payload = JSON.parse(rawData)
  } catch (error) {
    payload = rawData
  }
  await handlers[event]?.(payload)
}
