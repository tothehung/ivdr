import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface User {
  id: string;
  userId: string;
  organizationId: string;
  email: string;
  fullName: string;
  role: string;
}

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  user: User | null;
  setAuth: (token: string, refreshToken: string, user: any) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      refreshToken: null,
      user: null,
      setAuth: (token, refreshToken, user) => {
        const mappedUser = user ? {
          ...user,
          id: user.id || user.userId,
          userId: user.userId || user.id
        } : null;
        set({ token, refreshToken, user: mappedUser });
      },
      logout: () => set({ token: null, refreshToken: null, user: null }),
    }),
    { name: 'auth-storage' }
  )
);
