import { NavLink, Outlet } from 'react-router-dom'

const navItems = [
  { path: '/', label: 'Overview' },
  { path: '/cache', label: 'Cache' },
  { path: '/providers', label: 'Providers' },
  { path: '/keys', label: 'API Keys' },
]

function Layout() {
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <nav style={{
        width: 220,
        background: '#1a1a2e',
        color: '#fff',
        padding: '20px 0',
      }}>
        <h2 style={{ padding: '0 20px', marginBottom: 30, fontSize: 18 }}>
          LLM Gateway
        </h2>
        {navItems.map(item => (
          <NavLink
            key={item.path}
            to={item.path}
            style={({ isActive }) => ({
              display: 'block',
              padding: '10px 20px',
              color: isActive ? '#fff' : '#8888aa',
              background: isActive ? '#16213e' : 'transparent',
              textDecoration: 'none',
            })}
          >
            {item.label}
          </NavLink>
        ))}
      </nav>
      <main style={{ flex: 1, padding: 24, background: '#f5f5f5' }}>
        <Outlet />
      </main>
    </div>
  )
}

export default Layout
