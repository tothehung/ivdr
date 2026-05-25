import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import { api } from '../../lib/api';

export default function Register() {
  const [step, setStep] = useState<'details' | 'otp' | 'password'>('details');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  // Form state
  const [organizationName, setOrganizationName] = useState('');
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [otp, setOtp] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [jobTitle, setJobTitle] = useState('');

  const navigate = useNavigate();
  const setAuth = useAuthStore(state => state.setAuth);

  const handleSendOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    try {
      await api.post('/auth/send-otp', { email, fullName, organizationName });
      setStep('otp');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to send OTP. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    try {
      await api.post('/auth/verify-otp', { email, otp });
      setStep('password');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Invalid or expired OTP code.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    try {
      const response = await api.post('/auth/register', { 
        organizationName, fullName, email, password, phone, jobTitle 
      });
      const { accessToken, refreshToken, user } = response.data.data || response.data;
      setAuth(accessToken, refreshToken, user);
      navigate('/app/workspaces');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create account.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md p-8 rounded-2xl bg-card border border-border shadow-xl transition-all duration-300">
        <div className="flex flex-col items-center mb-8">
          <img src="/logo.png" alt="Logo" className="w-12 h-12 rounded-lg mb-4" />
          <h1 className="text-2xl font-bold text-foreground">Create an account</h1>
          <p className="text-muted-foreground text-sm mt-1">
            {step === 'details' && "Step 1 of 3: Organization Details"}
            {step === 'otp' && "Step 2 of 3: Email Verification"}
            {step === 'password' && "Step 3 of 3: Setup Password"}
          </p>
        </div>

        {error && (
          <div className="mb-4 p-3 rounded bg-destructive/10 border border-destructive/20 text-destructive text-sm text-center">
            {error}
          </div>
        )}

        {step === 'details' && (
          <form onSubmit={handleSendOtp} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Organization Name</label>
              <input 
                type="text" 
                value={organizationName}
                onChange={e => setOrganizationName(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="Acme Corp"
                required
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Full Name</label>
              <input 
                type="text" 
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="John Doe"
                required
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Email</label>
              <input 
                type="email" 
                value={email}
                onChange={e => setEmail(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="you@example.com"
                required
              />
            </div>
            <button 
              type="submit" 
              disabled={isLoading}
              className="mt-2 bg-foreground text-background rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity"
            >
              {isLoading ? 'Sending OTP...' : 'Continue'}
            </button>
          </form>
        )}

        {step === 'otp' && (
          <form onSubmit={handleVerifyOtp} className="flex flex-col gap-4">
            <div className="p-3 bg-accent rounded text-sm text-center mb-2">
              We've sent a 6-digit code to <strong>{email}</strong>.
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground text-center">Enter Verification Code</label>
              <input 
                type="text" 
                value={otp}
                onChange={e => setOtp(e.target.value)}
                maxLength={6}
                className="bg-background border border-border rounded-lg px-4 py-3 text-center text-xl tracking-[0.5em] focus:outline-none focus:ring-2 focus:ring-ring mx-auto w-48 font-mono"
                placeholder="000000"
                required
              />
            </div>
            <div className="flex gap-2 mt-2">
              <button 
                type="button" 
                onClick={() => setStep('details')}
                className="flex-1 bg-accent text-foreground rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 transition-opacity"
              >
                Back
              </button>
              <button 
                type="submit" 
                disabled={isLoading || otp.length !== 6}
                className="flex-[2] bg-foreground text-background rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity"
              >
                {isLoading ? 'Verifying...' : 'Verify Code'}
              </button>
            </div>
          </form>
        )}

        {step === 'password' && (
          <form onSubmit={handleRegister} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Password</label>
              <input 
                type="password" 
                value={password}
                onChange={e => setPassword(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="Minimum 8 characters"
                required
                minLength={8}
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Job Title (Optional)</label>
              <input 
                type="text" 
                value={jobTitle}
                onChange={e => setJobTitle(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="e.g. Compliance Officer"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">Phone (Optional)</label>
              <input 
                type="tel" 
                value={phone}
                onChange={e => setPhone(e.target.value)}
                className="bg-background border border-border rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="+1 555 000 0000"
              />
            </div>
            <button 
              type="submit" 
              disabled={isLoading}
              className="mt-2 bg-foreground text-background rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 disabled:opacity-50 transition-opacity"
            >
              {isLoading ? 'Creating account...' : 'Complete Registration'}
            </button>
          </form>
        )}

        {step === 'details' && (
          <p className="text-center text-sm text-muted-foreground mt-6">
            Already have an account? <Link to="/login" className="text-foreground hover:underline">Sign in</Link>
          </p>
        )}
      </div>
    </div>
  );
}
