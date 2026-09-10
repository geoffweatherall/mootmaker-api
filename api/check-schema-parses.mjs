// Refuses a schema that standard GraphQL tooling cannot parse.
//
// WHY THIS EXISTS. api/mootmaker.graphql is published as @mootmaker/schema, so every consumer
// parses it with ordinary tooling - graphql-js in the webapp's codegen, and anything else that
// installs the package. AppSync itself is more forgiving than that: it knows @aws_iam,
// @aws_cognito_user_pools and @aws_subscribe implicitly and accepts a schema that never declares
// them. So a schema can deploy perfectly and still be unparseable everywhere else.
//
// That is not hypothetical. Version 3.1.0 was published to npm without the three `directive @aws_*`
// declarations, and `buildSchema` rejects it outright:
//
//     Unknown directive "@aws_iam".
//
// Nothing caught it, because nothing in this repository had ever parsed the schema the way its
// consumers do. A published package cannot be unpublished, so the cost of missing this is a
// permanently broken version on a public registry.
//
// Deliberately parses the file this repository is about to publish, rather than the installed
// package, so it fails on the pull request that introduces the problem rather than after release.
import { buildSchema } from 'graphql'
import { readFileSync } from 'node:fs'

const schemaPath = process.argv[2] ?? 'api/mootmaker.graphql'

try {
  buildSchema(readFileSync(schemaPath, 'utf8'))
  console.log(`${schemaPath}: parses with graphql-js`)
} catch (error) {
  console.error(`${schemaPath} cannot be parsed by standard GraphQL tooling:\n  ${error.message}`)
  console.error(
    '\nIf this names an unknown @aws_* directive, declare it at the top of the schema.' +
      '\nAppSync accepts explicit declarations of its own directives - verified before relying on it.',
  )
  process.exit(1)
}
