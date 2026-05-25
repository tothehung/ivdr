import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface Workspace {
  id: string;
  name: string;
  role: string;
  description?: string;
  isPrivate?: boolean;
}

interface WorkspaceState {
  activeWorkspace: Workspace | null;
  setActiveWorkspace: (ws: Workspace) => void;
  clearWorkspace: () => void;
}

export const useWorkspaceStore = create<WorkspaceState>()(
  persist(
    (set) => ({
      activeWorkspace: null,
      setActiveWorkspace: (ws) => set({ activeWorkspace: ws }),
      clearWorkspace: () => set({ activeWorkspace: null }),
    }),
    { name: 'workspace-storage' }
  )
);
