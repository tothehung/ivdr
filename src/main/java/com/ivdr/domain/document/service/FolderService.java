package com.ivdr.domain.document.service;

import com.ivdr.common.exception.ApiException;
import com.ivdr.domain.document.dto.FolderDtos.CreateFolderRequest;
import com.ivdr.domain.document.dto.FolderDtos.FolderResponse;
import com.ivdr.domain.document.entity.Folder;
import com.ivdr.domain.document.repository.FolderRepository;
import com.ivdr.domain.workspace.entity.WorkspaceMember;
import com.ivdr.domain.workspace.repository.WorkspaceMemberRepository;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderService {

    private final FolderRepository folderRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    @Transactional
    public FolderResponse createFolder(UUID workspaceId, CreateFolderRequest req, UserPrincipal principal) {
        // Enforce role check: only OWNER or EDITOR can create folders
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot create folders. Only EDITOR or OWNER can create.");
        }

        // Check duplicate name in same hierarchy
        boolean exists = req.parentId() == null
                ? folderRepository.existsByWorkspaceIdAndParentIdIsNullAndName(workspaceId, req.name())
                : folderRepository.existsByWorkspaceIdAndParentIdAndName(workspaceId, req.parentId(), req.name());

        if (exists) {
            throw ApiException.badRequest("A folder with name '" + req.name() + "' already exists at this location.");
        }

        Folder folder = Folder.builder()
                .workspaceId(workspaceId)
                .name(req.name())
                .parentId(req.parentId())
                .createdBy(principal.userId())
                .build();

        folder = folderRepository.save(folder);
        log.info("Folder created: id={}, name={}, parentId={}, workspaceId={}", 
                folder.getId(), folder.getName(), folder.getParentId(), workspaceId);

        return toResponse(folder);
    }

    @Transactional(readOnly = true)
    public List<FolderResponse> listFolders(UUID workspaceId, UUID parentId, UserPrincipal principal) {
        // Check membership
        workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        List<Folder> folders = parentId == null
                ? folderRepository.findAllByWorkspaceIdAndParentIdIsNull(workspaceId)
                : folderRepository.findAllByWorkspaceIdAndParentId(workspaceId, parentId);

        return folders.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Resolves a directory path (e.g., "src/components") relative to the root,
     * creating folders along the path if they do not exist.
     * Used dynamically by recursive folder uploads.
     */
    @Transactional
    public UUID getOrCreateFolderByPath(UUID workspaceId, String path, UUID parentId, UserPrincipal principal) {
        if (path == null || path.isBlank()) return parentId;

        String[] parts = path.split("/");
        UUID currentParentId = parentId;

        for (String part : parts) {
            if (part.isBlank()) continue;

            final UUID currentParent = currentParentId;
            Optional<Folder> existing = currentParent == null
                ? folderRepository.findAllByWorkspaceIdAndParentIdIsNull(workspaceId).stream()
                    .filter(f -> f.getName().equalsIgnoreCase(part)).findFirst()
                : folderRepository.findAllByWorkspaceIdAndParentId(workspaceId, currentParent).stream()
                    .filter(f -> f.getName().equalsIgnoreCase(part)).findFirst();

            if (existing.isPresent()) {
                currentParentId = existing.get().getId();
            } else {
                Folder newFolder = Folder.builder()
                        .workspaceId(workspaceId)
                        .name(part)
                        .parentId(currentParent)
                        .createdBy(principal.userId())
                        .build();
                newFolder = folderRepository.save(newFolder);
                currentParentId = newFolder.getId();
            }
        }
        return currentParentId;
    }

    @Transactional
    public void deleteFolder(UUID workspaceId, UUID folderId, UserPrincipal principal) {
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot delete folders. Only EDITOR or OWNER can delete.");
        }

        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> ApiException.notFound("Folder not found."));

        if (!folder.getWorkspaceId().equals(workspaceId)) {
            throw ApiException.badRequest("Folder does not belong to this workspace.");
        }

        folderRepository.delete(folder);
        log.info("Folder deleted: id={}, name={}, workspaceId={}", folderId, folder.getName(), workspaceId);
    }

    private FolderResponse toResponse(Folder folder) {
        return new FolderResponse(
                folder.getId(),
                folder.getWorkspaceId(),
                folder.getName(),
                folder.getParentId(),
                folder.getCreatedBy(),
                folder.getCreatedAt()
        );
    }
}
