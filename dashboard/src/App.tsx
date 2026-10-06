import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/layout/Layout'
import Overview from './pages/Overview'
import CacheAnalytics from './pages/CacheAnalytics'
import Providers from './pages/Providers'
import ApiKeys from './pages/ApiKeys'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Overview />} />
          <Route path="/cache" element={<CacheAnalytics />} />
          <Route path="/providers" element={<Providers />} />
          <Route path="/keys" element={<ApiKeys />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
