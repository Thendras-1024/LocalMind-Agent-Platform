<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores'
import { getUser, getUserInfo } from '@/api/user'

const router = useRouter()
const userStore = useUserStore()

// 数据定义
const user = ref({})
const info = ref({})

// 生命周期钩子
onMounted(() => {
  checkLogin()
})

// 方法定义
const checkLogin = () => {
  // 获取用户信息
  getUser()
    .then(({ data }) => {
      // 保存用户
      user.value = data
      // 查询用户详情
      getUserInfo(data.id)
        .then(({ data: userInfo }) => {
          if (userInfo) {
            info.value = userInfo
            // 保存到本地
            userStore.setUserInfo(userInfo)
          }
        })
        .catch(() => {
          ElMessage.error('获取用户详情失败')
        })
    })
    .catch(() => {
      ElMessage.error('获取用户信息失败')
      router.push('/login')
    })
}

const goBack = () => {
  router.back()
}
</script>

<template>
  <div class="info-edit">
    <div class="header">
      <div class="header-back-btn" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </div>
      <div class="header-title">资料编辑</div>
    </div>

    <div class="edit-container">
      <div class="info-box">
        <div class="info-item">
          <div class="info-label">头像</div>
          <div class="info-btn">
            <img
              width="35"
              :src="
                '/src/assets' + user.icon ||
                '/src/assets/imgs/icons/default-icon.png'
              "
              alt="用户头像"
            />
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
        <div class="divider"></div>
        <div class="info-item">
          <div class="info-label">昵称</div>
          <div class="info-btn">
            <div>{{ user.nickName }}</div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
        <div class="divider"></div>
        <div class="info-item">
          <div class="info-label">个人介绍</div>
          <div class="info-btn">
            <div style="overflow: hidden; width: 150px; text-align: right">
              {{ info.introduce || '介绍一下自己' }}
            </div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
      </div>

      <div class="info-box">
        <div class="info-item">
          <div class="info-label">性别</div>
          <div class="info-btn">
            <div>{{ info.gender || '选择' }}</div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
        <div class="divider"></div>
        <div class="info-item">
          <div class="info-label">城市</div>
          <div class="info-btn">
            <div>{{ info.city || '选择' }}</div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
        <div class="divider"></div>
        <div class="info-item">
          <div class="info-label">生日</div>
          <div class="info-btn">
            <div>{{ info.birthday || '添加' }}</div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
      </div>

      <div class="info-box">
        <div class="info-item">
          <div class="info-label">我的积分</div>
          <div class="info-btn">
            <div>查看积分</div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
        <div class="divider"></div>
        <div class="info-item">
          <div class="info-label">会员等级</div>
          <div class="info-btn">
            <div><a href="javascript:void(0)">成为VIP尊享特权</a></div>
            <div>
              <el-icon><ArrowRight /></el-icon>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.info-edit {
  display: flex;
  flex-direction: column;
  height: 100vh;
  color: var(--lm-text);
  background: var(--lm-bg);
}

.header {
  position: relative;
  min-height: var(--lm-header-height);
  display: flex;
  align-items: center;
  padding: 0 15px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid var(--lm-line);
  flex-shrink: 0;
  backdrop-filter: blur(14px);
}

.header-back-btn {
  font-size: 20px;
  color: var(--lm-primary);
}

.header-title {
  flex: 1;
  text-align: center;
  font-size: 16px;
  font-weight: 800;
}

.edit-container {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.info-box {
  background: var(--lm-surface);
  margin-bottom: 12px;
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-soft);
  overflow: hidden;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px;
}

.info-label {
  font-size: 16px;
  color: var(--lm-text);
  font-weight: 700;
}

.info-btn {
  display: flex;
  align-items: center;
  color: var(--lm-muted);
}

.info-btn img {
  margin-right: 10px;
  border-radius: 50%;
}

.divider {
  height: 1px;
  background: var(--lm-line);
  margin: 0 15px;
}

a {
  color: var(--lm-primary);
  text-decoration: none;
}
</style>
