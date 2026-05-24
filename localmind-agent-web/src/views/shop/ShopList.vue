<template>
  <div class="shop-list-container">
    <!-- 头部 -->
    <div class="header">
      <div class="header-back-btn" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="header-title">{{ typeName }}</div>
      <div class="header-search">
        <el-icon><Search /></el-icon>
      </div>
    </div>

    <!-- 排序栏 -->
    <div class="sort-bar">
      <div class="sort-item">
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="el-dropdown-link">
            {{ typeName
            }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in types" :key="t.id" :command="t">
                {{ t.name }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <div class="sort-item" @click="sortAndQuery('')">
        距离 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
      <div class="sort-item" @click="sortAndQuery('comments')">
        人气 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
      <div class="sort-item" @click="sortAndQuery('score')">
        评分 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </div>
    </div>

    <!-- 店铺列表 -->
    <div class="shop-list" @scroll="onScroll">
      <div
        class="shop-box"
        v-for="s in shops"
        :key="s.id"
        @click="toDetail(s.id)"
      >
        <div class="shop-img"><img :src="s.images" alt="" /></div>
        <div class="shop-info">
          <div class="shop-title shop-item">{{ s.name }}</div>
          <div class="shop-rate shop-item">
            <el-rate
              v-model="s.score"
              disabled
              :max="5"
              :value="s.score / 10"
              text-color="#ff6b35"
              show-score
            />
            <span>{{ s.comments }}条</span>
          </div>
          <div class="shop-area shop-item">
            <span>{{ s.area }}</span>
            <span v-if="formatDistance(s.distance)">
              {{ formatDistance(s.distance) }}
            </span>
          </div>
          <div class="shop-avg shop-item">￥{{ s.avgPrice }}/人</div>
          <div class="shop-address shop-item">
            <el-icon><Location /></el-icon>
            <span>{{ s.address }}</span>
          </div>
        </div>
      </div>
      <div v-if="!loading && !shops.length" class="list-state">当前分类暂无店铺</div>
      <div v-if="loading" class="list-state">正在加载...</div>
      <div v-if="!loading && shops.length && isReachBottom" class="list-state">已经到底了</div>
    </div>
    <div class="pagination-bar">
      <div
        class="page-btn"
        :class="{ disabled: loading || params.current <= 1 }"
        @click="changePage(params.current - 1)"
      >
        上一页
      </div>
      <span class="page-info">第{{ params.current }}页 / 共{{ totalPages }}页</span>
      <div
        class="page-btn"
        :class="{ disabled: loading || params.current >= totalPages }"
        @click="changePage(params.current + 1)"
      >
        下一页
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ArrowLeft, ArrowDown, Search, Location } from '@element-plus/icons-vue'
import { getShopList, getShopTypeList, getShopLocationConfig } from '@/api/shop'
import { throttle } from '@/utils/scroll'

const router = useRouter()
const route = useRoute()

// 响应式数据
const types = ref([])
const shops = ref([])
const typeName = ref('')
const isReachBottom = ref(false)
const loading = ref(false)
const total = ref(0)
let shopQueryToken = 0
const PAGE_SIZE = 5
const params = ref({
  typeId: 0,
  current: 1,
  sortBy: '',
  x: undefined,
  y: undefined
})

// 获取店铺类型列表
const queryTypes = async () => {
  if (types.value.length > 0) {
    return
  }
  try {
    const { data } = await getShopTypeList()
    types.value = data
    console.log('getShopTypeList', types.value)
  } catch (error) {
    console.error(error)
    ElMessage.error('获取店铺类型失败')
  }
}

// 获取店铺列表
const queryShops = async ({ reset = false } = {}) => {
  const token = ++shopQueryToken
  const requestParams = { ...params.value }
  loading.value = true
  try {
    const result = await getShopList(requestParams)
    if (token !== shopQueryToken || requestParams.typeId !== params.value.typeId) {
      return
    }
    const data = result.data
    if (!data) return
    data.forEach((s) => (s.images = s.images.split(',')[0]))
    shops.value = data
    total.value = Number(result.total ?? data.length)
    isReachBottom.value = params.value.current >= totalPages.value || data.length === 0
  } catch (error) {
    console.error(error)
    ElMessage.error('获取店铺列表失败')
  } finally {
    if (token === shopQueryToken) {
      loading.value = false
    }
  }
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

const resolveLocation = async () => {
  try {
    const { data } = await getShopLocationConfig()
    if (!data?.realCoordinateEnabled) {
      params.value.x = data?.mockX
      params.value.y = data?.mockY
      return
    }
    await useBrowserLocation()
  } catch (error) {
    console.error(error)
    params.value.x = 120.149993
    params.value.y = 30.334229
  }
}

const useBrowserLocation = () =>
  new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve()
      return
    }
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        params.value.x = coords.longitude
        params.value.y = coords.latitude
        resolve()
      },
      () => resolve(),
      {
        enableHighAccuracy: true,
        timeout: 3000,
        maximumAge: 60000
      }
    )
  })

const formatDistance = (distance) => {
  const value = Number(distance)
  if (!Number.isFinite(value) || value <= 0) {
    return ''
  }
  return value < 1000 ? `${value.toFixed(1)}m` : `${(value / 1000).toFixed(1)}km`
}

// 处理类型选择
const handleCommand = (type) => {
  console.log('handleCommand', type)
  if (!type?.id || Number(type.id) === params.value.typeId) {
    return
  }
  router.replace({
    path: '/shopList',
    query: {
      type: type.id,
      name: type.name
    }
  })
}

// 排序和查询
const sortAndQuery = (sortBy) => {
  params.value.sortBy = sortBy
  params.value.current = 1
  shops.value = []
  isReachBottom.value = false
  queryShops({ reset: true })
}

const changePage = (page) => {
  if (loading.value) {
    return
  }
  const nextPage = Math.min(Math.max(1, page), totalPages.value)
  if (nextPage === params.value.current) {
    return
  }
  params.value.current = nextPage
  shops.value = []
  isReachBottom.value = false
  queryShops({ reset: true })
}

// 返回上一页
const goBack = () => {
  router.replace('/index')
}

// 跳转到店铺详情
const toDetail = (id) => {
  router.push(`/shopDetail/${id}`)
}

// 滚动加载
const onScroll = throttle((e) => {
  const { scrollTop, offsetHeight, scrollHeight } = e.target
  if (scrollTop + offsetHeight + 1 > scrollHeight && !isReachBottom.value && !loading.value) {
    params.value.current++
    queryShops({ reset: true })
  }
}, 200)

// 初始化
const initData = async () => {
  shopQueryToken++
  params.value.typeId = Number(route.query.type) || 0
  shops.value = [] // 清空店铺列表
  total.value = 0
  params.value.current = 1 // 重置页码
  await resolveLocation()
  await queryTypes()
  const selectedType = types.value.find((type) => Number(type.id) === params.value.typeId)
  typeName.value = route.query.name || selectedType?.name || ''
  await queryShops({ reset: true })
}

// 监听路由参数变化
watch(
  () => route.query,
  () => {
    initData()
  }
)
onMounted(() => {
  initData()
})
</script>

<style scoped>
.shop-list-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  color: var(--lm-text);
  background: var(--lm-bg);
}

.header {
  display: flex;
  align-items: center;
  min-height: var(--lm-header-height);
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid var(--lm-line);
  position: sticky;
  top: 0;
  z-index: 100;
  backdrop-filter: blur(14px);
}

.header-back-btn {
  width: 38px;
  color: var(--lm-primary);
  font-size: 21px;
  cursor: pointer;
}

.header-title {
  flex: 1;
  text-align: center;
  font-size: 17px;
  font-weight: 800;
}

.header-search {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  color: var(--lm-primary);
  background: var(--lm-primary-soft);
  cursor: pointer;
}

.sort-bar {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  background: var(--lm-surface);
  border-bottom: 1px solid var(--lm-line);
}

.sort-item {
  flex: 1;
  text-align: center;
  padding: 8px 4px;
  border-radius: 999px;
  color: var(--lm-text-soft);
  background: #fff8f2;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.shop-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 12px 76px;
}

.list-state {
  padding: 12px 0 18px;
  text-align: center;
  color: var(--lm-muted);
  font-size: 13px;
}

.pagination-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.94);
  border-top: 1px solid var(--lm-line);
}

.page-btn {
  min-width: 74px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  background: var(--lm-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.page-btn.disabled {
  color: var(--lm-muted);
  background: #f1ebe6;
  cursor: not-allowed;
}

.page-info {
  flex: 1;
  text-align: center;
  color: var(--lm-text-soft);
  font-size: 13px;
  font-weight: 700;
}

.shop-box {
  display: flex;
  gap: 11px;
  padding: 10px;
  margin-bottom: 12px;
  background: var(--lm-surface);
  border: 1px solid rgba(240, 228, 220, 0.74);
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-soft);
  cursor: pointer;
}

.shop-img {
  width: 92px;
  height: 92px;
  flex-shrink: 0;
}

.shop-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 14px;
}

.shop-info {
  flex: 1;
}

.shop-item {
  margin-bottom: 5px;
}

.shop-title {
  font-size: 16px;
  font-weight: 800;
  color: var(--lm-text);
}

.shop-rate {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--lm-muted);
}

.shop-area {
  color: var(--lm-text-soft);
  font-size: 14px;
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.shop-avg {
  color: var(--lm-primary);
  font-size: 14px;
  font-weight: 700;
}

.shop-address {
  color: var(--lm-muted);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
}

.shop-address span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
