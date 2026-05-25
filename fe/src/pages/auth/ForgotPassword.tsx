import { useState } from 'react';
import { Link } from 'react-router-dom';

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [isSubmitted, setIsSubmitted] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // Mock functionality since API doesn't exist yet
    setIsSubmitted(true);
  };

  return (
    <div className="min-h-screen bg-background flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md p-8 rounded-2xl bg-card border border-border shadow-xl">
        <div className="flex flex-col items-center mb-8">
          <img src="/logo.png" alt="Logo" className="w-12 h-12 rounded-lg mb-4" />
          <h1 className="text-2xl font-bold text-foreground">Reset Password</h1>
          <p className="text-muted-foreground text-sm mt-1 text-center">
            Enter your email and we'll send you a link to reset your password.
          </p>
        </div>

        {isSubmitted ? (
          <div className="flex flex-col items-center gap-4 text-center">
            <div className="p-3 rounded bg-primary/10 border border-primary/20 text-primary text-sm">
              If an account exists for {email}, you will receive a password reset link shortly.
            </div>
            <Link to="/login" className="text-sm text-foreground hover:underline mt-4">
              Return to Sign In
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
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
              className="mt-2 bg-foreground text-background rounded-lg px-4 py-2.5 text-sm font-semibold hover:opacity-90 transition-opacity"
            >
              Send Reset Link
            </button>
          </form>
        )}

        {!isSubmitted && (
          <p className="text-center text-sm text-muted-foreground mt-6">
            Remember your password? <Link to="/login" className="text-foreground hover:underline">Sign in</Link>
          </p>
        )}
      </div>
    </div>
  );
}
