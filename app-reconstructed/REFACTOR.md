# MainViewModel responsibility extraction — 2026-10-17

This is the first refactoring pass, not a rewrite of task dispatch or networking.
The existing ControlUi, AiAppUi and GalleryUi entry points and application ID are retained.

## Ownership

- `workflow/WorkflowGraph.kt`: graph normalization, scalar coercion, override application,
  seed replacement, node parsing, title resolution and prompt summaries. No Android/ViewModel.
- `ui/state/WorkflowEditorState.kt`: Control-screen graph editing, parameter overrides,
  custom labels, visibility and ordering. Agent and AI App session state remains separate.
- `data/WorkflowPreferences.kt`: backend/site preference namespaces, read-only legacy
  fallbacks, first-write migration and JSON conversion. Storage uses read/write callbacks.
- `ui/state/ConsoleLogStore.kt`: the 400-message redacted log buffer, text rendering,
  export and clear. Concurrent appends are serialized to avoid lost log entries.
- `ui/state/AppCacheManager.kt`: existing cache accounting, clearing and orphan retention.
  Coroutine scope, dispatcher selection and UI completion callbacks remain with the ViewModel.

MainViewModel is still a facade and still owns task submission, connection lifecycle and
cross-screen orchestration. Do not pass MainViewModel into the new components.
These remaining responsibilities should be extracted in later, separately verified passes.

## Behavior kept

- Connected graph inputs are not overwritten by literal parameter values.
- Integer/float conversion, editable-node defaults and LoRA-stack slot filtering are retained.
- Preferences keep their existing names and local / cloud@cn / cloud@ai namespaces.
- Cloud results keep the namespace associated with their original workflow.
- Drag reordering is persisted only on the same explicit save/update paths as before.
- Cache retention times, optional upload-preview removal and Main-thread callbacks are retained.
- No new libraries, permissions, UI routes, package-name changes or service endpoints.

## Tests and verification boundary

Four JVM test classes cover graph transformations, preference migration/isolation,
editor state/graph protection, and redacted bounded/concurrent logging. The existing tests
also remain in place. Real-device UI, cache deletion, network reconnect and generation-server
integration still need device testing. This pass makes no runtime-speed or memory-use claim.

The verification records, baseline snapshot, patch and guarded Python rollback script are in:
`C:\Users\x\Documents\ChatGPT\Android\build-evidence\viewmodel-refactor`

The previously identified TLS and bridge-plugin issues are unchanged in this pass.
