package com.ivdr.domain.document.repository;

import com.ivdr.domain.document.entity.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FolderRepository extends JpaRepository<Folder, UUID> {

    List<Folder> findAllByWorkspaceId(UUID workspaceId);

    List<Folder> findAllByWorkspaceIdAndParentId(UUID workspaceId, UUID parentId);

    List<Folder> findAllByWorkspaceIdAndParentIdIsNull(UUID workspaceId);

    Optional<Folder> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    boolean existsByWorkspaceIdAndParentIdAndName(UUID workspaceId, UUID parentId, String name);

    boolean existsByWorkspaceIdAndParentIdIsNullAndName(UUID workspaceId, String name);
}
