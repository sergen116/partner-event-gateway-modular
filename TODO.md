# TODO

- [ ] Arrange startup SQL files under `src/main/resources/db/migration/` (or `docker/init/`) so a reviewer can spin the stack up cleanly without app-side bootstrap. Decide where pgmq/pg_partman extension creation belongs (compose init vs. Flyway V1) and remove the duplication.
  - When done, document the layout and the `docker compose up` flow in `README.md` (which init scripts run, when, and against which DB).
