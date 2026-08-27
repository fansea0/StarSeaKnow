import { createRouter, createWebHistory } from 'vue-router'
import { installGuards } from './guards'
import Agent from '../views/Agent.vue'
import Knowledge from '../views/Knowledge.vue'
import System from '../views/System.vue'
import Tools from '../views/Tools.vue'
import Login from '../views/Login.vue'
import AcceptInvite from '../views/AcceptInvite.vue'
import Forbidden from '../views/Forbidden.vue'
import TenantMembers from '../views/TenantMembers.vue'
import Overview from '../views/system/Overview.vue'
import Tenants from '../views/system/Tenants.vue'
import Register from '../views/Register.vue'
import ChangeInitialPassword from '../views/ChangeInitialPassword.vue'

const routes = [
  {
    path: '/',
    redirect: '/knowledge'
  },
  {
    path: '/agent',
    name: 'Agent',
    component: Agent
  },
  {
    path: '/knowledge',
    name: 'Knowledge',
    component: Knowledge
  },
  {
    path: '/system',
    name: 'System',
    component: System,
    redirect: '/system/overview',
    children: [
      {
        path: 'overview',
        name: 'SystemOverview',
        component: Overview,
        meta: { requiresPlatformAdmin: true }
      },
      {
        path: 'tenants',
        name: 'SystemTenants',
        component: Tenants,
        meta: { requiresPlatformAdmin: true }
      }
    ]
  },
  {
    path: '/tools',
    name: 'Tools',
    component: Tools,
    redirect: '/tools/md-converter',
    children: [
      {
        path: 'md-converter',
        name: 'MdConverter',
        component: () => import('../views/MdConverter.vue')
      }
    ]
  },
  {
    path: '/knowledge/:id',
    name: 'KnowledgeDetail',
    component: () => import('../views/KnowledgeDetail.vue')
  },
  {
    path: '/agent/:id',
    name: 'AgentDetail',
    component: () => import('../views/AgentDetail.vue')
  },
  { path: '/login', name: 'Login', component: Login, meta: { public: true } },
  { path: '/register', name: 'Register', component: Register, meta: { public: true } },
  { path: '/change-initial-password', name: 'ChangeInitialPassword', component: ChangeInitialPassword, meta: { requiresPlatformAdmin: true } },
  { path: '/accept-invite/:code', name: 'AcceptInvite', component: AcceptInvite, meta: { public: true } },
  { path: '/403', name: 'Forbidden', component: Forbidden, meta: { public: true } },
  { path: '/tenant/members', name: 'TenantMembers', component: TenantMembers, meta: { requiresAdmin: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

installGuards(router)

export default router
