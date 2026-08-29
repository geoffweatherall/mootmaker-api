# mootmaker-api

The GraphQL API: AWS AppSync backed by Java 25 Lambda handlers over DynamoDB, plus the Cognito user
pool every frontend authenticates against.

**Start by reading [README.md](README.md).** It describes the data model, the AppSync/Lambda/DynamoDB
architecture, the directory structure, the bash scripts (`deploy`, `undeploy`, `verify`,
`authenticate`), the AWS cost model, and the backend validation rules. Keep it up to date when any of
those change — it is load-bearing, and both people and agents rely on it being current.

## Working here

- **`api/mootmaker.graphql` is the source of truth for the API contract.** The webapp keeps a
  hand-maintained mirror in `webapp/src/graphql/types.ts`; nothing enforces that they agree, so a
  schema change means changing both in step.
- **Error enums are mirrored in two places.** Each entity has a GraphQL error enum (`RoomError`,
  `PersonError`, `MeetingError`) and a Java enum with exactly matching constant names. Adding a case
  means adding it to both.
- **Deploy this before the webapp.** The webapp reads this environment's Terraform outputs — the
  GraphQL URL and Cognito IDs — via `authenticate.sh`.
- **Java 25**, Maven, `mvn -f impl/pom.xml test` for unit tests.
- **Cold starts are real.** Java Lambdas take several seconds on first invocation, which has caused
  test timeouts before. Consider it before concluding something is broken.

---

## Project-wide rules

This repository is part of the **mootmaker** project. The workflow rules that apply everywhere live
in the hub repository, which you should find checked out as a sibling directory:

    ../mootmaker/docs/process/README.md

On GitHub: <https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md>

**Read it before doing any non-trivial work here.** The short version:

- Work of any real size starts with a **design document** (`../mootmaker/designs/`), not with code.
- Bugs and small changes start with a **GitHub issue in this repository**, so `Closes #N` works.
- All work happens on a **branch** and lands via a **pull request**. There is no approval step —
  reading the diff is the review, merging is the approval.
- **A green acceptance run against a real deployed environment** is the definition of working — not
  a passing unit suite, and not a successful deploy.
- **Environments are `production` or ephemeral.** Tear down any ephemeral environment you create;
  that is part of finishing, not a tidy-up afterwards.
- **If your change makes a document wrong, fixing it is part of the change.**
- **Verify against reality, not your own output.** A script exiting zero is not evidence that the
  thing it was meant to do happened.
- **Say what actually happened.** Failing tests get reported with their output; skipped steps get
  named.

Also useful: [`../mootmaker/docs/roles/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/roles/)
for which kind of work you are doing, and
[`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh)
if something is not installed.

`CLAUDE.md` in this repository is a symlink to this file.
