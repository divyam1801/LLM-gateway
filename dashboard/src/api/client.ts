import axios from 'axios'

const api = axios.create({
  baseURL: '/api/admin',
  auth: {
    username: 'admin',
    password: localStorage.getItem('adminPassword') || 'admin',
  },
})

export function setAdminPassword(password: string) {
  localStorage.setItem('adminPassword', password)
  api.defaults.auth = { username: 'admin', password }
}

export default api
