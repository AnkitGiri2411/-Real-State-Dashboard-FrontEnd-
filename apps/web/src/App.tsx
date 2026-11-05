import { useEffect } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { ProtectedRoute } from './components/common'
import { ToastProvider } from './components/ui'
import { setSessionExpiredHandler } from './auth/session'
import { useAuthStore } from './auth/authStore'
import AppShell from './layout/AppShell'

import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import PropertiesPage from './pages/PropertiesPage'
import PropertyDetailPage from './pages/PropertyDetailPage'
import UnitsPage from './pages/UnitsPage'
import OwnersPage from './pages/OwnersPage'
import PortfoliosPage from './pages/PortfoliosPage'
import TenantsPage from './pages/TenantsPage'
import BuyersPage from './pages/BuyersPage'
import AgentsPage from './pages/AgentsPage'
import OfficesPage from './pages/OfficesPage'
import ListingsPage from './pages/ListingsPage'
import ViewingsPage from './pages/ViewingsPage'
import ApplicationsPage from './pages/ApplicationsPage'
import LeasesPage from './pages/LeasesPage'
import LeaseDetailPage from './pages/LeaseDetailPage'
import PaymentsPage from './pages/PaymentsPage'
import LeadsPage from './pages/LeadsPage'
import LeadDetailPage from './pages/LeadDetailPage'
import OffersPage from './pages/OffersPage'
import SalesPage from './pages/SalesPage'
import MaintenancePage from './pages/MaintenancePage'
import ExpensesPage from './pages/ExpensesPage'
import DocumentsPage from './pages/DocumentsPage'
import CommunicationPage from './pages/CommunicationPage'
import NotificationsPage from './pages/NotificationsPage'
import ReportsPage from './pages/ReportsPage'
import AuditLogsPage from './pages/AuditLogsPage'
import ImportsPage from './pages/ImportsPage'
import SettingsPage from './pages/SettingsPage'
import ProfilePage from './pages/ProfilePage'
import NotFoundPage from './pages/NotFoundPage'

/**
 * The route table.
 *
 * Everything except sign-in sits behind {@link ProtectedRoute}, and every operational screen
 * declares the permission it needs through `PageAccessGuard` - so a route reached by typing its
 * address still checks, rather than relying on the sidebar having hidden it. The backend remains
 * authoritative: the guard explains a refusal, the API enforces it.
 *
 * Detail routes nest under their register's path so a bookmarked record survives a reload.
 */
export default function App() {
  const navigate = useNavigate()
  const hydrate = useAuthStore((state) => state.hydrate)

  // Restore the session once, from the token already in storage. Until it resolves, `ProtectedRoute`
  // holds the frame rather than flashing the sign-in page at someone who is signed in.
  useEffect(() => { void hydrate() }, [hydrate])

  /*
   * When the refresh token genuinely cannot be renewed the API client reports the session over. The
   * reader is sent to sign-in with a notice, rather than left on a screen whose every request fails.
   */
  useEffect(() => {
    setSessionExpiredHandler(() => navigate('/login', { replace: true, state: { expired: true } }))
    return () => setSessionExpiredHandler(null)
  }, [navigate])

  return (
    <ToastProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<ProtectedRoute />}>
          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />

            <Route path="/properties" element={<PropertiesPage />} />
            <Route path="/properties/:propertyId" element={<PropertyDetailPage />} />
            <Route path="/units" element={<UnitsPage />} />
            <Route path="/owners" element={<OwnersPage />} />
            <Route path="/portfolios" element={<PortfoliosPage />} />

            <Route path="/tenants" element={<TenantsPage />} />
            <Route path="/buyers" element={<BuyersPage />} />
            <Route path="/agents" element={<AgentsPage />} />
            <Route path="/offices" element={<OfficesPage />} />

            <Route path="/listings" element={<ListingsPage />} />
            <Route path="/viewings" element={<ViewingsPage />} />
            <Route path="/applications" element={<ApplicationsPage />} />
            <Route path="/leases" element={<LeasesPage />} />
            <Route path="/leases/:leaseId" element={<LeaseDetailPage />} />
            <Route path="/payments" element={<PaymentsPage />} />

            <Route path="/leads" element={<LeadsPage />} />
            <Route path="/leads/:leadId" element={<LeadDetailPage />} />
            <Route path="/offers" element={<OffersPage />} />
            <Route path="/sales" element={<SalesPage />} />

            <Route path="/maintenance" element={<MaintenancePage />} />
            <Route path="/expenses" element={<ExpensesPage />} />
            <Route path="/documents" element={<DocumentsPage />} />

            <Route path="/communication" element={<CommunicationPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />

            <Route path="/reports" element={<ReportsPage />} />
            <Route path="/audit-logs" element={<AuditLogsPage />} />

            <Route path="/imports" element={<ImportsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/profile" element={<ProfilePage />} />

            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Route>
      </Routes>
    </ToastProvider>
  )
}
