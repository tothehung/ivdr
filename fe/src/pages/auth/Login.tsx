import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import { api } from '../../lib/api';

interface Organization {
  id: string;
  name: string;
  slug: string;
  plan: string;
}

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [selectedOrgId, setSelectedOrgId] = useState<string>('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const navigate = useNavigate();
  const setAuth = useAuthStore(state => state.setAuth);

  const checkOrganizations = async (emailVal: string) => {
    if (!emailVal || !emailVal.includes('@')) {
      setOrganizations([]);
      setSelectedOrgId('');
      return;
    }
    try {
      const response = await api.get(`/auth/orgs?email=${encodeURIComponent(emailVal.trim())}`);
      const orgsList = response.data.data || response.data || [];
      setOrganizations(orgsList);
      if (orgsList.length === 1) {
        setSelectedOrgId(orgsList[0].id);
      } else if (orgsList.length > 1) {
        if (!orgsList.some((o: any) => o.id === selectedOrgId)) {
          setSelectedOrgId(orgsList[0].id);
        }
      } else {
        setSelectedOrgId('');
      }
    } catch (err) {
      console.error('Failed to fetch organizations:', err);
    }
  };

  const handleEmailBlur = () => {
    checkOrganizations(email);
  };

  const handleEmailChange = (val: string) => {
    setEmail(val);
    if (organizations.length > 0) {
      setOrganizations([]);
      setSelectedOrgId('');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');

    try {
      let orgId = selectedOrgId;
      if (organizations.length === 0) {
        const response = await api.get(`/auth/orgs?email=${encodeURIComponent(email.trim())}`);
        const orgsList = response.data.data || response.data || [];
        if (orgsList.length > 0) {
          setOrganizations(orgsList);
          orgId = orgsList[0].id;
          setSelectedOrgId(orgId);
          if (orgsList.length > 1) {
            setError('Please select an organization to proceed');
            setIsLoading(false);
            return;
          }
        }
      }

      // Connect to Spring Boot backend
      const response = await api.post('/auth/login', { 
        email: email.trim(), 
        password,
        organizationId: orgId || null
      });
      
      const { accessToken, refreshToken, user } = response.data.data || response.data;
      setAuth(accessToken, refreshToken, user);
      
      navigate('/app/workspaces');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to login');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md p-8 rounded-2xl bg-card border border-border shadow-xl">
        <div className="flex flex-col items-center mb-8">
          <img src="/logo.png" alt="Logo" className="w-12 h-12 rounded-lg mb-4" />
          <h1 className="text-2xl font-bold text-foreground">Welcome back</h1>
          <p className="text-muted-foreground text-sm mt-1">Enter your credentials to access your account</p>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded bg-destructive/10 border border-destructive/20 text-destructive text-sm text-center">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium text-foreground">Email</label>
            <input 
              type="email" 
              value={email}
              onChange={e => handleEmailChange(e.target.value)}
              onBlur={handleEmailBlur}
              className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="you@example.com"
              required
            />
          </div>

          {organizations.length > 0 && (
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Organization</label>
              <select
                value={selectedOrgId}
                onChange={e => setSelectedOrgId(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring text-foreground"
                required
              >
                {organizations.map(org => (
                  <option key={org.id} value={org.id}>
                    {org.name} ({org.slug})
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">Password</label>
              <Link to="/forgot-password" className="text-xs text-muted-foreground hover:text-foreground hover:underline">Forgot password?</Link>
            </div>
            <input 
              type="password" 
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="••••••••"
              required
            />
          </div>
          <button 
            type="submit" 
            disabled={isLoading}
            className="mt-2 bg-foreground text-background rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity"
          >
            {isLoading ? 'Signing in...' : 'Sign In'}
          </button>
        </form>

        <p className="text-center text-sm text-muted-foreground mt-6">
          Don't have an account? <Link to="/register" className="text-foreground hover:underline">Sign up</Link>
        </p>
      </div>
    </div>
  );
}
