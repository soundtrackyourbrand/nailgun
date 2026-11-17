Releasing
=========

Releases are published to Soundtrack's public Maven repository,
`https://us-maven.pkg.dev/syb-common-images/maven-public`, which anyone can read without
credentials.

1. Set the new version in `pom.xml`, `nailgun-server/pom.xml` and `nailgun-examples/pom.xml`,
   and commit.
2. Tag the commit `v<version>` and push the tag.

The `Release` workflow then publishes `nailgun-server` and its parent POM, `nailgun-all`. It
fails if the tag does not match the version in `pom.xml`. A version can be published once.
