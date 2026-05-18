<template>
  <div class="agent-page">
    <div class="agent-header">
      <button class="icon-btn" @click="goBack" aria-label="返回">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div class="agent-title">
        <div class="title-main">智能推荐导购</div>
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
      '你好，我是推荐小精灵。告诉我想去哪、几个人、预算、距离和时间，我来帮你筛店。'
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
  background: #f6f7f9;
}

.agent-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  color: #fff;
  background: #ff6633;
}

.icon-btn,
.send-btn {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.icon-btn {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
}

.agent-title {
  flex: 1;
  min-width: 0;
}

.title-main {
  font-size: 17px;
  font-weight: 700;
}

.title-sub {
  margin-top: 2px;
  font-size: 12px;
  opacity: 0.85;
}

.agent-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 12px;
}

.message-row {
  display: flex;
  margin-bottom: 10px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: 8px;
  line-height: 1.5;
  font-size: 14px;
  background: #fff;
  color: #333;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.message-row.user .message-bubble {
  color: #fff;
  background: #ff6633;
}

.recommend-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 4px 0 14px;
}

.recommend-card {
  display: flex;
  gap: 10px;
  padding: 10px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 5px rgba(0, 0, 0, 0.08);
  cursor: pointer;
}

.shop-cover {
  width: 86px;
  height: 86px;
  border-radius: 6px;
  object-fit: cover;
  background: #eee;
  flex-shrink: 0;
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
  font-weight: 700;
  color: #222;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-tag {
  flex-shrink: 0;
  padding: 2px 5px;
  border-radius: 4px;
  color: #ff6633;
  background: #fff0e9;
  font-size: 11px;
}

.shop-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: #666;
}

.shop-reason {
  margin-top: 6px;
  color: #444;
  font-size: 12px;
  line-height: 1.45;
}

.quick-prompts {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 8px 12px;
  background: #fff;
  border-top: 1px solid #eee;
}

.prompt-chip {
  flex: 0 0 auto;
  max-width: 220px;
  padding: 7px 10px;
  border: 1px solid #ffd2bf;
  border-radius: 8px;
  color: #ff6633;
  background: #fff;
  font-size: 12px;
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
  background: #fff;
}

.send-btn {
  flex-shrink: 0;
  color: #fff;
  background: #ff6633;
}

.send-btn:disabled {
  cursor: not-allowed;
  background: #c8c9cc;
}
</style>
