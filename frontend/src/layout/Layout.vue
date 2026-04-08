<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: isCollapsed }">
      <div class="logo">
        <div class="logo-icon">
          <el-icon size="24"><DataAnalysis /></el-icon>
        </div>
        <transition name="fade">
          <span v-show="!isCollapsed" class="logo-text">用户行为分析</span>
        </transition>
      </div>
      
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapsed"
        router
        class="sidebar-menu"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>数据看板</span>
        </el-menu-item>
        
        <el-menu-item index="/behavior">
          <el-icon><TrendCharts /></el-icon>
          <span>行为分析</span>
        </el-menu-item>
        
        <el-menu-item index="/profile">
          <el-icon><User /></el-icon>
          <span>用户画像</span>
        </el-menu-item>
        
        <el-menu-item index="/product">
          <el-icon><Goods /></el-icon>
          <span>商品分析</span>
        </el-menu-item>
        
        <el-menu-item index="/funnel">
          <el-icon><Filter /></el-icon>
          <span>转化漏斗</span>
        </el-menu-item>
        
        <el-sub-menu index="admin" v-if="userStore.isAdmin">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item index="/user-manage">
            <el-icon><UserFilled /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/data-manage">
            <el-icon><FolderOpened /></el-icon>
            <span>数据管理</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
      
      <div class="sidebar-footer" v-show="!isCollapsed">
        <span>v1.0.0</span>
      </div>
    </aside>

    <!-- 主内容区 -->
    <div class="main-container">
      <!-- 顶部栏 -->
      <header class="header">
        <div class="header-left">
          <el-button text class="toggle-btn" @click="toggleSidebar">
            <el-icon size="20"><Fold v-if="!isCollapsed" /><Expand v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        
        <div class="header-right">
          <el-dropdown trigger="click" @command="handleCommand">
            <div class="user-info">
              <el-avatar :size="32" class="user-avatar" :style="{ backgroundColor: 'var(--primary-color)' }">
                {{ userStore.username?.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="user-name">{{ userStore.username }}</span>
              <el-icon class="arrow-icon"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区 -->
      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessageBox } from 'element-plus'
import {
  DataAnalysis, DataBoard, TrendCharts, User, Goods, Filter,
  Setting, UserFilled, FolderOpened, Fold, Expand, ArrowDown, SwitchButton
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isCollapsed = ref(false)

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || '')

const toggleSidebar = () => {
  isCollapsed.value = !isCollapsed.value
}

const handleCommand = (command) => {
  if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(() => {
      userStore.logout()
      router.push('/login')
    }).catch(() => {})
  }
}
</script>

<style scoped lang="scss">
.layout {
  display: flex;
  height: 100vh;
  background: var(--bg-body);
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  background: var(--bg-sidebar); /* 深色侧边栏 */
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  box-shadow: 2px 0 8px rgba(0,0,0,0.05);
  z-index: 10;
  
  &.collapsed {
    width: 64px;
    
    .logo-text {
      display: none;
    }
  }
}

/* Logo */
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  background: rgba(255,255,255,0.05);
  border-bottom: 1px solid rgba(255,255,255,0.05);
  overflow: hidden;
}

.logo-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-color);
  border-radius: 6px;
  color: #fff;
  flex-shrink: 0;
}

.logo-text {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  white-space: nowrap;
}

/* 侧边栏菜单 */
.sidebar-menu {
  flex: 1;
  border-right: none !important;
  background: transparent !important;
  padding: 16px 8px;
  overflow-y: auto;
  
  :deep(.el-menu-item) {
    height: 44px;
    line-height: 44px;
    margin: 4px 0;
    border-radius: 6px;
    color: #94a3b8;
    
    &:hover {
      background: rgba(255,255,255,0.05);
      color: #fff;
    }
    
    &.is-active {
      background: var(--primary-color);
      color: #fff;
    }
    
    .el-icon {
      font-size: 18px;
      margin-right: 10px;
    }
  }

  :deep(.el-sub-menu__title) {
    height: 44px;
    line-height: 44px;
    border-radius: 6px;
    color: #94a3b8;
    
    &:hover {
      background: rgba(255,255,255,0.05);
      color: #fff;
    }
  }
  
  :deep(.el-sub-menu .el-menu) {
    background: transparent;
  }
}

.sidebar-footer {
  padding: 16px;
  text-align: center;
  color: #64748b;
  font-size: 12px;
  border-top: 1px solid rgba(255,255,255,0.05);
}

/* 主容器 */
.main-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部栏 */
.header {
  height: 64px;
  background: var(--bg-container);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--border-color);
  box-shadow: var(--shadow-sm);
  z-index: 9;
  
  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;
  }
  
  .toggle-btn {
    padding: 0;
    height: auto;
    color: var(--text-regular);
    
    &:hover {
      color: var(--primary-color);
      background: transparent;
    }
  }
  
  .header-right {
    display: flex;
    align-items: center;
  }
  
  .user-info {
    display: flex;
    align-items: center;
    gap: 10px;
    cursor: pointer;
    padding: 4px 8px;
    border-radius: 6px;
    transition: background-color 0.2s;
    
    &:hover {
      background: var(--bg-hover);
    }
  }
  
  .user-name {
    color: var(--text-main);
    font-weight: 500;
    font-size: 14px;
  }
  
  .arrow-icon {
    color: var(--text-secondary);
    font-size: 12px;
  }
}

/* 内容区 */
.content {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  background: var(--bg-body);
}

/* 页面过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
