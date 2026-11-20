# Huddle selection hotfix 1.11.10

Implemented; scoped publication approval pending. Baseline exact deployed1.11.9 revision6133e7ce3133d2b6b20de415f3b8843c7dc15899. Isolated worktree protects existing work.

Root cause reproduced before fix: a direct pair room expanded by INVITE retained its original DIRECT key. Creating the original pair again reused the larger group. Direct PLATFORM/DIRECT reuse now requires exact participant set equality with creator plus selected teammates. Existing expanded room/history remain unchanged. Record-based reuse, tenant/access validation and participant limits unchanged. No schema changes or inventory operations.

CD-approved corrections: two-letter circular initials (first/last or first two letters of single name), header participant circles/full names, standard circular close, Pin/End for everyone removed from header, existing Save/Leave flow retained. Picker disabled label Select teammate; selected label Start Collaboration. Widget user-facing chat wording replaced by collaboration. Existing database persistence of rooms continues; no migration.

Verification: deterministic failure before fix;12 service/WebSocket tests pass after correction, including expanded-group isolation and exact-pair reuse. Three focused browser cases pass for picker state, selected participant payload, mentions, send, minimize, close/save and avatars. CD inspected rendered fixtures; actual served page font verification pending. New explicit HUDDLE_SELECTION focused CI mode runs these checks and packages same checkout; existing lanes/normalCI unchanged. Owner requested fast focused checks, no backup/broadUAT. This behavior fix is not misclassified as presentation-only.
