package com.ivdr.domain.workspace.controller;

import com.ivdr.common.response.ApiResponse;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.AddMemberRequest;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.CreateWorkspaceRequest;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.MemberResponse;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.WorkspaceResponse;
import com.ivdr.domain.workspace.service.WorkspaceService;
import com.ivdr.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for workspace management endpoints.
 *
 * <p>Base path: {@code /workspaces}
 *
 * <table border="1" cellpadding="4">
 *   <tr><th>Method</th><th>Path</th><th>Status</th><th>Description</th></tr>
 *   <tr><td>POST</td>  <td>/workspaces</td>                       <td>201</td><td>Create a workspace</td></tr>
 *   <tr><td>GET</td>   <td>/workspaces</td>                       <td>200</td><td>List caller's workspaces (paginated)</td></tr>
 *   <tr><td>GET</td>   <td>/workspaces/{id}</td>                  <td>200</td><td>Get a single workspace</td></tr>
 *   <tr><td>DELETE</td><td>/workspaces/{id}</td>                  <td>204</td><td>Delete a workspace (OWNER only)</td></tr>
 *   <tr><td>POST</td>  <td>/workspaces/{id}/members</td>          <td>201</td><td>Add a member (OWNER only)</td></tr>
 *   <tr><td>DELETE</td><td>/workspaces/{id}/members/{userId}</td> <td>204</td><td>Remove a member (OWNER only)</td></tr>
 * </table>
 *
 * <p>All endpoints require an authenticated JWT — the Spring Security filter
 * chain populates {@link UserPrincipal} from the token and injects it via
 * {@code @AuthenticationPrincipal}.
 *
 * <p>Validation of request bodies is delegated to Bean Validation via
 * {@code @Valid}; the {@code GlobalExceptionHandler} converts constraint
 * violations into structured 400 responses.
 */
@RestController
@RequestMapping("/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    // =========================================================================
    // Workspace CRUD
    // =========================================================================

    /**
     * Creates a new workspace owned by the currently authenticated user.
     *
     * <p>The caller automatically becomes the initial {@code OWNER} member.
     *
     * @param req       the creation request body (validated)
     * @param principal the authenticated user injected by Spring Security
     * @return {@code 201 Created} with the workspace payload wrapped in {@link ApiResponse}
     */
    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {

        WorkspaceResponse response = workspaceService.createWorkspace(req, principal);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Workspace created successfully.", response));
    }

    /**
     * Returns a paginated list of workspaces to which the caller belongs.
     *
     * <p>The default page size is 20, sorted by creation date descending.
     * Clients can customise pagination with {@code ?page=0&size=10&sort=name,asc}
     * query parameters (Spring Data's {@link Pageable} resolver handles this).
     *
     * @param pageable  pagination and sorting parameters (resolved from query string)
     * @param principal the authenticated user
     * @return {@code 200 OK} with a {@link Page} of {@link WorkspaceResponse} objects
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<WorkspaceResponse>>> listWorkspaces(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {

        Page<WorkspaceResponse> page = workspaceService.listWorkspaces(principal, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    /**
     * Retrieves a single workspace by its UUID.
     *
     * <p>The caller must be a member of the workspace; otherwise a 403 is returned.
     *
     * @param id        the workspace UUID (path variable)
     * @param principal the authenticated user
     * @return {@code 200 OK} with the workspace details
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> getWorkspace(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        WorkspaceResponse response = workspaceService.getWorkspace(id, principal);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Permanently deletes a workspace and all of its membership records.
     *
     * <p>The caller must hold the {@code OWNER} role within the workspace.
     *
     * @param id        the UUID of the workspace to delete
     * @param principal the authenticated user (must be OWNER)
     * @return {@code 204 No Content} on success
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        workspaceService.deleteWorkspace(id, principal);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Membership management
    // =========================================================================

    /**
     * Adds a user to the specified workspace with the given role.
     *
     * <p>Only a workspace {@code OWNER} may invoke this endpoint.
     * The target user must not already be a member; duplicate membership
     * results in a {@code 409 Conflict}.
     *
     * @param id        the workspace UUID
     * @param req       the add-member request body (userId + role), validated
     * @param principal the authenticated caller (must be OWNER)
     * @return {@code 201 Created} with the new member's details
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<MemberResponse>> addMember(
            @PathVariable UUID id,
            @Valid @RequestBody AddMemberRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {

        MemberResponse member = workspaceService.addMember(id, req, principal);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Member added successfully.", member));
    }

    /**
     * Removes a user from the specified workspace.
     *
     * <p>Only a workspace {@code OWNER} may remove other members.
     * The last OWNER of a workspace cannot be removed — the service layer
     * enforces this guard and returns {@code 409 Conflict} if violated.
     *
     * @param id        the workspace UUID
     * @param userId    the UUID of the user to remove
     * @param principal the authenticated caller (must be OWNER)
     * @return {@code 204 No Content} on success
     */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {

        workspaceService.removeMember(id, userId, principal);
        return ResponseEntity.noContent().build();
    }
}
