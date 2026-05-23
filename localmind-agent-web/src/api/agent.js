import request from '@/utils/request'

export const sendRecommendationMessage = (data) =>
  request.post('/agent/recommendation/chat', data, { timeout: 120000 })
