<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <div class="logo-wrapper">
          <el-icon size="32" class="logo-icon"><DataAnalysis /></el-icon>
        </div>
        <h1 class="title">用户消费行为分析系统</h1>
        <p class="subtitle">洞察数据价值，驱动业务增长</p>
      </div>
      
      <el-form 
        ref="formRef" 
        :model="form" 
        :rules="rules" 
        class="login-form"
        @submit.prevent="handleLogin"
        size="large"
      >
        <el-form-item prop="username">
          <el-input 
            v-model="form.username" 
            placeholder="请输入用户名"
            :prefix-icon="User"
          />
        </el-form-item>
        
        <el-form-item prop="password">
          <el-input 
            v-model="form.password" 
            type="password"
            placeholder="请输入密码"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        
        <el-form-item>
          <el-button 
            type="primary" 
            :loading="loading"
            class="login-btn"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
        
        <div class="login-tips">
          <el-icon class="tip-icon"><InfoFilled /></el-icon>
          <span>测试账号: admin / admin123</span>
        </div>
      </el-form>
    </div>
    
    <div class="copyright">
      © 2026 电商用户消费行为分析系统 · All Rights Reserved
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { login } from '@/api/auth'
import { ElMessage } from 'element-plus'
import { DataAnalysis, User, Lock, InfoFilled } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: 'admin123'
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' }
  ]
}

const handleLogin = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  
  loading.value = true
  try {
    const res = await login(form)
    userStore.login(res.data)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (error) {
    console.error('登录失败:', error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  background-color: #f1f5f9;
  background-image: radial-gradient(#e2e8f0 1px, transparent 1px);
  background-size: 24px 24px;
}

.login-card {
  width: 100%;
  max-width: 420px;
  padding: 40px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  border: 1px solid #e2e8f0;
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo-wrapper {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-color);
  border-radius: 12px;
  color: #fff;
  box-shadow: 0 4px 6px -1px rgba(79, 70, 229, 0.2);
}

.title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 8px;
}

.subtitle {
  font-size: 14px;
  color: #64748b;
}

.login-form {
  margin-top: 24px;
  
  :deep(.el-input__wrapper) {
    box-shadow: 0 0 0 1px #e2e8f0 inset;
    
    &.is-focus {
      box-shadow: 0 0 0 1px var(--primary-color) inset;
    }
  }
}

.login-btn {
  width: 100%;
  font-weight: 600;
  height: 44px;
  font-size: 15px;
  margin-top: 8px;
}

.login-tips {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px;
  background: #eff6ff;
  border-radius: 6px;
  color: #3b82f6;
  font-size: 13px;
  
  .tip-icon {
    font-size: 16px;
  }
}

.copyright {
  margin-top: 40px;
  font-size: 12px;
  color: #94a3b8;
}
</style>
