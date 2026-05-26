package com.ivdr.domain.document.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.UUID;

public final class FolderDtos {

    private FolderDtos() {}

    public record CreateFolderRequest(
            @NotBlank(message = "Folder name must not be blank")
            String name,
            UUID parentId
    ) {}

    public record FolderResponse(
            UUID id,
            UUID workspaceId,
            String name,
            UUID parentId,
            UUID createdBy,
            LocalDateTime createdAt
    ) {}
}
