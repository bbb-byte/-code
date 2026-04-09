import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, handleAuthExpired, isAdminUser, isTokenExpired } from '@/utils/auth'

const routes = [
    {
        path: '/login',
        name: 'Login',
        component: () => import('@/views/Login.vue'),
        meta: { title: '登录', public: true }
    },
    {
        path: '/',
        component: () => import('@/layout/Layout.vue'),
        redirect: '/dashboard',
        children: [
            {
                path: 'dashboard',
                name: 'Dashboard',
                component: () => import('@/views/Dashboard.vue'),
                meta: { title: '数据看板', icon: 'DataBoard' }
            },
            {
                path: 'behavior',
                name: 'BehaviorAnalysis',
                component: () => import('@/views/BehaviorAnalysis.vue'),
                meta: { title: '行为分析', icon: 'TrendCharts' }
            },
            {
                path: 'profile',
                name: 'UserProfile',
                component: () => import('@/views/UserProfile.vue'),
                meta: { title: '用户画像', icon: 'User' }
            },
            {
                path: 'product',
                name: 'ProductAnalysis',
                component: () => import('@/views/ProductAnalysis.vue'),
                meta: { title: '商品分析', icon: 'Goods' }
            },
            {
                path: 'funnel',
                name: 'FunnelAnalysis',
                component: () => import('@/views/FunnelAnalysis.vue'),
                meta: { title: '转化漏斗', icon: 'Filter' }
            },
            {
                path: 'user-manage',
                name: 'UserManage',
                component: () => import('@/views/UserManage.vue'),
                meta: { title: '用户管理', icon: 'UserFilled', admin: true }
            },
            {
                path: 'data-manage',
                name: 'DataManage',
                component: () => import('@/views/DataManage.vue'),
                meta: { title: '数据管理', icon: 'FolderOpened', admin: true }
            }
        ]
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
    // 设置页面标题
    document.title = to.meta.title ? `${to.meta.title} - 电商用户行为分析系统` : '电商用户行为分析系统'

    const token = getToken()
    const hasValidToken = token && !isTokenExpired(token)

    if (to.meta.public) {
        if (to.path === '/login' && hasValidToken) {
            next((typeof to.query.redirect === 'string' && to.query.redirect) || '/dashboard')
            return
        }
        next()
    } else if (!hasValidToken) {
        if (token) {
            handleAuthExpired(router, {
                redirect: to.fullPath,
                notify: true
            })
            next(false)
            return
        }

        next({
            path: '/login',
            query: { redirect: to.fullPath }
        })
    } else if (to.meta.admin && !isAdminUser()) {
        ElMessage.error('无权限访问该页面')
        next('/dashboard')
    } else {
        next()
    }
})

export default router
