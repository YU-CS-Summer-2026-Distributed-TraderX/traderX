# Console runtime packaging specification

Status: candidate tested locally, not integrated. Dependency: accepted source at e274df81ff82bbba65238bd193c7a0dc54215f76.

- **FR-CRP-01:** The installed image includes the complete static local ESM dependency graph rooted at `server.mjs`. Its files match the selected source bytes. Current closure: server, desk-orders, desk-session, treasury-demo, eod-jobs, risk-demo and operator-control modules.
- **FR-CRP-02:** The image's default command starts without source mounts and serves `/healthz`, built Angular HTML and the referenced JavaScript assets. Existing signed-out auth and local cloud-read refusal endpoints remain available.
- **NFR-CRP-01:** Keep explicit runtime file selection, cloud SDK/kubectl bridge executables, production frontend build, environment defaults and default command. Do not add credentials, tests or the whole source workspace to the runtime image.
- **SC-CRP-01:** `scripts/test-console-image.py IMAGE --evidence NEW_DIRECTORY` resolves an immutable image identity, parses transitive static ESM imports with Node, compares installed module hashes, exercises real HTTP endpoints, records failures/logs and removes its owned container. It must reject the original image missing desk-orders.

The helper requires Docker, Python 3 and Node with `vm.SourceTextModule`. Its local profile uses a read-only container, 256 MiB, one CPU, 64 PIDs, an ephemeral host-loopback port and `LOCAL_ONLY=1`, with no credentials or mounts. Static dependency enumeration does not cover computed dynamic imports; none occur in the current runtime closure. Cloud/Kubernetes bridge connectivity, browser interaction and live orders remain separate validation scopes.
