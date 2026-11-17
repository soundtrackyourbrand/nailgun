Releasing
=========

Releases are published to Soundtrack's public Maven repository,
`https://us-maven.pkg.dev/syb-common-images/maven-public`, which anyone can read without
credentials.

1. Set the new version in `pom.xml`, `nailgun-server/pom.xml` and `nailgun-examples/pom.xml`,
   and commit.
2. Tag the commit `v<version>` and push the tag.

Only repository admins can push a `v*` tag. The `Release` workflow then tests the tag and
publishes `nailgun-server` and its parent POM, `nailgun-all`. It fails if the tag does not
match the version in `pom.xml`. A version can be published once.

Publishing authenticates to Google Cloud through workload identity federation, which trusts
one thing: a job in this repository's `release` environment, on a tag. The environment
admits `v*` tags only. Keep both settings, or any workflow anyone pushes could publish.
