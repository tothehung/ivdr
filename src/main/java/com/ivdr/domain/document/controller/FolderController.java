package com.ivdr.domain.document.controller;

import com.ivdr.common.response.ApiResponse;
import com.ivdr.domain.document.dto.FolderDtos.CreateFolderRequest;
import com.ivdr.domain.document.dto.FolderDtos.FolderResponse;
import com.ivdr.domain.document.service.FolderService;
import com.ivdr.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workspaces/{workspaceId}/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @PostMapping
    public ResponseEntity<ApiResponse<FolderResponse>> createFolder(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateFolderRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {

        FolderResponse response = folderService.createFolder(workspaceId, req, principal);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Folder created successfully.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listFolders(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID parentId,
            @AuthenticationPrincipal UserPrincipal principal) {

        List<FolderResponse> response = folderService.listFolders(workspaceId, parentId, principal);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/path")
    public ResponseEntity<ApiResponse<UUID>> getOrCreateFolderByPath(
            @PathVariable UUID workspaceId,
            @RequestParam String path,
            @RequestParam(required = false) UUID parentId,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID folderId = folderService.getOrCreateFolderByPath(workspaceId, path, parentId, principal);
        return ResponseEntity.ok(ApiResponse.ok("Folder resolved successfully.", folderId));
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<ApiResponse<Void>> deleteFolder(
            @PathVariable UUID workspaceId,
            @PathVariable UUID folderId,
            @AuthenticationPrincipal UserPrincipal principal) {

        folderService.deleteFolder(workspaceId, folderId, principal);
        return ResponseEntity.ok(ApiResponse.ok("Folder deleted successfully.", null));
    }
}
