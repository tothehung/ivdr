import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import LandingPage from './pages/LandingPage';
import { useAuthStore } from './store/useAuthStore';
import AppLayout from './components/layout/AppLayout';

import Login from './pages/auth/Login';

import Workspaces from './pages/app/Workspaces';
import Documents from './pages/app/Documents';
import Analytics from './pages/app/Analytics';
import AuditLogs from './pages/app/AuditLogs';
import AiChat from './pages/app/AiChat';
import Register from './pages/auth/Register';
import ForgotPassword from './pages/auth/ForgotPassword';

// Protected Route Component
const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const token = useAuthStore((state) => state.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
};

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/forgot-password" element={<ForgotPassword />} />
        
        {/* Protected App Routes */}
        <Route 
          path="/app" 
          element={
            <ProtectedRoute>
              <AppLayout />
            </ProtectedRoute>
          } 
        >
          <Route index element={<Navigate to="/app/workspaces" replace />} />
          <Route path="workspaces" element={<Workspaces />} />
          <Route path="documents" element={<Documents />} />
          <Route path="ai-chat" element={<AiChat />} />
          <Route path="analytics" element={<Analytics />} />
          <Route path="logs" element={<AuditLogs />} />
        </Route>
      </Routes>
    </Router>
  );
}

export default App;
