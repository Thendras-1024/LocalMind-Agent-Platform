<template>
  <div class="agent-page">
    <div class="agent-header">
      <button class="icon-btn" @click="goBack" aria-label="返回">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div class="agent-title">
        <div class="title-main">LocalMind 智慧精灵</div>
        <div class="title-sub">{{ locationText }}</div>
      </div>
      <button class="icon-btn" @click="locate" aria-label="重新定位">
        <el-icon><Position /></el-icon>
      </button>
    </div>

    <div class="agent-body" ref="bodyRef">
      <div
        v-for="item in messages"
        :key="item.id"
        class="message-row"
        :class="item.role"
      >
        <div class="message-bubble">
          {{ item.content }}
        </div>
      </div>

      <div v-if="recommendations.length" class="recommend-list">
        <div
          v-for="shop in recommendations"
          :key="shop.shopId"
          class="recommend-card"
          @click="toShopDetail(shop.shopId)"
        >
          <img :src="resolveAssetPath(shop.image)" alt="" class="shop-cover" />
          <div class="shop-info">
            <div class="shop-title-line">
              <span class="shop-name">{{ shop.name }}</span>
              <span v-if="shop.hasHistoryOrder" class="history-tag">去过</span>
            </div>
            <div class="shop-meta">
              <span>{{ shop.score || '-' }}分</span>
              <span>{{ formatDistance(shop.distance) }}</span>
              <span>约{{ shop.estimatedTotalPrice || '-' }}元</span>
            </div>
            <div class="shop-reason">{{ shop.reason }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="quick-prompts">
      <button
        v-for="prompt in quickPrompts"
        :key="prompt"
        class="prompt-chip"
        @click="usePrompt(prompt)"
      >
        {{ prompt }}
      </button>
    </div>

    <div class="agent-input-bar">
      <el-input
        v-model="inputText"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 3 }"
        resize="none"
        placeholder="说说人数、类型、距离、预算和时间"
        @keydown.enter.prevent="sendMessage"
      />
      <button class="send-btn" :disabled="loading || !inputText.trim()" @click="sendMessage">
        <el-icon><Promotion /></el-icon>
      </button>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Position, Promotion } from '@element-plus/icons-vue'
import { sendRecommendationMessage } from '@/api/agent'
import { resolveAssetPath } from '@/utils/asset'

const router = useRouter()
const bodyRef = ref(null)
const inputText = ref('')
const loading = ref(false)
const recommendations = ref([])
const location = ref({ x: null, y: null })
const locationText = ref('正在获取位置')
const sessionId = ref(sessionStorage.getItem('recommendAgentSessionId') || '')
const messages = ref([
  {
    id: Date.now(),
    role: 'assistant',
    content:
      '你好，我是 LocalMind 智慧精灵。告诉我想去哪、几个人、预算、距离和时间，我来帮你筛店。'
  }
])
const quickPrompts = [
  '我和父母今天下午5点到8点想找5公里内评分高、总价150元以下的KTV',
  '两个人晚上想找近一点、评价好的美食店',
  '帮我找附近比较实惠的唱歌店'
]

const goBack = () => {
  router.back()
}

const locate = () => {
  if (!navigator.geolocation) {
    locationText.value = '当前浏览器不支持定位'
    return
  }
  locationText.value = '正在获取位置'
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      location.value = {
        x: pos.coords.longitude,
        y: pos.coords.latitude
      }
      locationText.value = '已获取当前位置'
    },
    () => {
      locationText.value = '定位未授权'
    },
    {
      enableHighAccuracy: true,
      timeout: 6000,
      maximumAge: 60000
    }
  )
}

const usePrompt = (prompt) => {
  inputText.value = prompt
}

const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  messages.value.push({
    id: Date.now(),
    role: 'user',
    content: text
  })
  inputText.value = ''
  loading.value = true
  await scrollToBottom()
  try {
    const { data } = await sendRecommendationMessage({
      sessionId: sessionId.value,
      message: text,
      x: location.value.x,
      y: location.value.y
    })
    if (data?.sessionId) {
      sessionId.value = data.sessionId
      sessionStorage.setItem('recommendAgentSessionId', data.sessionId)
    }
    recommendations.value = data?.recommendations || []
    messages.value.push({
      id: Date.now() + 1,
      role: 'assistant',
      content: data?.reply || '我暂时没有找到合适结果，可以换个条件再试试。'
    })
  } catch (error) {
    console.error(error)
    messages.value.push({
      id: Date.now() + 2,
      role: 'assistant',
      content: '导购服务暂时开小差了，稍后再试一次。'
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

const toShopDetail = (shopId) => {
  router.push(`/shopDetail/${shopId}`)
}

const formatDistance = (distance) => {
  if (!distance && distance !== 0) return '距离未知'
  if (distance < 1000) return `${Math.round(distance)}m`
  return `${(distance / 1000).toFixed(1)}km`
}

const scrollToBottom = async () => {
  await nextTick()
  if (bodyRef.value) {
    bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  }
}

onMounted(() => {
  locate()
})
</script>

<style scoped>
.agent-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  color: var(--lm-text);
  background:
    radial-gradient(circle at 18% 8%, rgba(21, 184, 166, 0.13), transparent 28%),
    linear-gradient(180deg, #fff8f2 0%, #f7f4ef 100%);
}

.agent-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 12px 14px;
  color: #fff;
  background:
    linear-gradient(135deg, var(--lm-primary), #ff8f57 58%, #15b8a6);
  box-shadow: 0 14px 28px rgba(255, 107, 53, 0.22);
}

.icon-btn,
.send-btn {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.icon-btn {
  color: #fff;
  background: rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(8px);
}

.agent-title {
  flex: 1;
  min-width: 0;
}

.title-main {
  font-size: 17px;
  font-weight: 800;
}

.title-sub {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.85;
}

.agent-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 12px;
}

.message-row {
  display: flex;
  margin-bottom: 12px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 82%;
  padding: 11px 13px;
  border-radius: 16px;
  line-height: 1.5;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--lm-text);
  box-shadow: var(--lm-shadow-soft);
}

.message-row.user .message-bubble {
  color: #fff;
  background: linear-gradient(135deg, var(--lm-primary), #ff8f57);
  border-bottom-right-radius: 6px;
}

.message-row.assistant .message-bubble {
  border-bottom-left-radius: 6px;
  box-shadow: var(--lm-shadow-soft), inset 3px 0 0 var(--lm-ai);
}

.recommend-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 4px 0 14px;
}

.recommend-card {
  display: flex;
  gap: 11px;
  padding: 10px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.98), rgba(232, 251, 248, 0.66));
  border: 1px solid rgba(21, 184, 166, 0.14);
  border-radius: 18px;
  box-shadow: var(--lm-shadow-soft);
  cursor: pointer;
}

.shop-cover {
  width: 86px;
  height: 86px;
  border-radius: 14px;
  object-fit: cover;
  background: #eee;
  flex-shrink: 0;
  box-shadow: 0 8px 18px rgba(36, 28, 24, 0.12);
}

.shop-info {
  flex: 1;
  min-width: 0;
}

.shop-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.shop-name {
  font-size: 15px;
  font-weight: 800;
  color: var(--lm-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-tag {
  flex-shrink: 0;
  padding: 2px 5px;
  border-radius: 999px;
  color: var(--lm-primary);
  background: var(--lm-primary-soft);
  font-size: 11px;
}

.shop-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--lm-text-soft);
}

.shop-reason {
  margin-top: 6px;
  color: var(--lm-text);
  font-size: 12px;
  line-height: 1.45;
}

.quick-prompts {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 9px 12px;
  background: rgba(255, 255, 255, 0.9);
  border-top: 1px solid var(--lm-line);
  backdrop-filter: blur(14px);
}

.prompt-chip {
  flex: 0 0 auto;
  max-width: 240px;
  padding: 8px 11px;
  border: 1px solid rgba(255, 107, 53, 0.2);
  border-radius: 999px;
  color: var(--lm-primary-deep);
  background: var(--lm-surface);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.agent-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 12px 14px;
  background: rgba(255, 255, 255, 0.94);
  border-top: 1px solid var(--lm-line);
  box-shadow: 0 -10px 26px rgba(53, 35, 25, 0.06);
}

.send-btn {
  flex-shrink: 0;
  color: #fff;
  background: linear-gradient(135deg, var(--lm-primary), var(--lm-ai));
  box-shadow: 0 10px 20px rgba(21, 184, 166, 0.18);
}

.send-btn:disabled {
  cursor: not-allowed;
  background: #c8c9cc;
}
</style>
