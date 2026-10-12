# mootmaker-api

The GraphQL API: AppSync in front of Java 25 Lambdas over DynamoDB, plus the Cognito user pool, the
avatars bucket and CDN, and the reset, repair and history-cleanup Lambdas.

**Read [README.md](README.md) first**, then [docs/architecture.md](docs/architecture.md). Keep both, and
[docs/api-reference.md](docs/api-reference.md) and [docs/data-model.md](docs/data-model.md), true in the
same pull request as any change that affects them.

## Working here

- **`api/mootmaker.graphql` is the contract.** Both apps generate code from the published package. A schema
  change bumps `api/package.json`'s version in the same pull request, and must not break installed Android
  apps: deprecate rather than remove (the `schema-compatible` check enforces it).
- **Error enums exist twice**: in the schema and as Java enums with exactly the same constant names. Change
  both. Both apps need wording for a new code.
- **Validation failures are returned, refusals are thrown.** A broken rule goes in the result's `errors`
  list (all of them, not just the first); "not allowed at all" is an exception, which AppSync turns into a
  GraphQL error.
- **Every write to meetings goes through `DayRepository`**, which owns the versioned read-modify-write loop,
  the pointers and retention. Do not write day items any other way.
- **New fields in handler responses must respect `SelectionSet`**: fetch only what was selected.
- **Limits live in `Limits.java`**, with a consistency check that runs when the Lambda initialises, so a
  bad change fails the deploy. Change limits there and keep the arithmetic in its comments true.
- **Times are zone-less local date-times.** Parse with `LocalDateTime`. "Now" on the server is UTC
  (mootmaker-api#113).
- **Format with `mvn -f impl/pom.xml spotless:apply`.** The `code-style` check also runs Checkstyle and ecj.
  Editor setup: `../mootmaker/tools/install-workspace-config.sh --force`; if formatting silently stops
  working, read [Java code style](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/java-code-style.md).
- **Deploy this before the web app and demo-data** in a new environment; they read the settings it
  publishes to SSM.
- **`verify.sh` resets the target environment's data.** Run it against an ephemeral environment.
- **New Java AWS SDK clients run locally** (in `verify/`) need the `sso` and `ssooidc` modules to use SSO
  credentials.

---

## Project-wide rules

This repository is part of **Mootmaker**. The hub repository, checked out as a sibling at
`../mootmaker`, holds what applies everywhere:

- How work is done: [`../mootmaker/docs/process/README.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md).
  Read it before any non-trivial work.
- How the system fits together: [`../mootmaker/docs/overview/`](https://github.com/geoffweatherall/mootmaker/tree/main/docs/overview).
- What to do when something is wrong: [`../mootmaker/docs/runbooks/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/runbooks/README.md).

The short version:

- Work of any real size starts with a **design** (`../mootmaker/designs/`); a bug or small change starts
  with a **GitHub issue in this repository**, so `Closes #N` works.
- Everything lands on a **branch** through a **pull request**. Reading the diff is the review; merging is
  the approval.
- **A green acceptance run against a real deployed environment** is the definition of working.
- **`test` and `production` change only through a release** (`release.yml` in mootmaker-release). Use an
  ephemeral environment for your own work, and tear it down when you finish.
- **Say what something costs in AWS before creating it.** Under NZ$1 one-off, go ahead and state it;
  more, or anything recurring, ask first.
- **Keep documentation and comments true and current.** If a change makes a document wrong, fixing it is
  part of the change. Describe what is, not how it got here; a comment says what and why.
- **Verify against reality, not your own output**, and **say what actually happened**, including
  failures and skipped steps.

Something not installed? [`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh).

`CLAUDE.md` in this repository is a symlink to this file.
